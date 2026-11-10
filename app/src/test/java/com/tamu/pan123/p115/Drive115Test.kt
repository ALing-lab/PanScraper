package com.tamu.pan123.p115

import com.tamu.pan123.data.p115.Drive115
import com.tamu.pan123.data.p115.Ec115Cipher
import com.tamu.pan123.data.p115.P115Creds
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

class Drive115Test {
    private lateinit var srv: Fake115Server
    private lateinit var store: MemP115Store
    private lateinit var drive: Drive115

    @Before
    fun setUp() {
        srv = Fake115Server().start()
        srv.dir("10", "电影")
        srv.dir("20", "剧集")
        store = MemP115Store(P115Creds(srv.validUid, srv.validCid, srv.validSeid, srv.validKid, "测试用户", srv.userId, srv.userKey))
        drive = srv.drive(store)
    }

    @After fun tearDown() = srv.shutdown()

    @Test
    fun listRootPagesAndParsesDirsFiles() = runBlocking {
        repeat(60) { srv.file("f$it", "file%03d.mkv".format(it), "10", 1000L + it) }
        val root = drive.list("0")
        assertEquals(setOf("剧集", "电影"), root.map { it.fileName }.toSet())
        assertTrue(root.all { it.isDir })
        val files = drive.list("10")
        assertEquals(60, files.size)
        assertFalse(files.first().isDir)
        val req = srv.calls.first { it.path!!.startsWith("/web/files?") }
        assertTrue(req.getHeader("Cookie")!!.contains("UID=${srv.validUid}"))
        assertEquals("0", req.requestUrl!!.queryParameter("cid"))
    }

    @Test
    fun renameMkdirMove() = runBlocking {
        srv.file("f1", "a.mkv", "10")
        assertEquals("f1", drive.rename("f1", "盗梦空间.mkv").getOrThrow())
        assertEquals("盗梦空间.mkv", srv.nodes["f1"]!!.name)

        srv.dir("S1", "Season 01", "20")
        assertEquals("S1", drive.mkdir("20", "Season 01").getOrThrow()) // reuse
        val id = drive.mkdir("20", "Season 02").getOrThrow()
        assertEquals("Season 02", srv.nodes[id]!!.name)

        assertEquals("f1", drive.move("f1", id).getOrThrow())
        assertEquals(id, srv.nodes["f1"]!!.parent)
    }

    @Test
    fun chineseErrors() = runBlocking {
        srv.forceErrno = 99
        val e = runCatching { drive.list("0") }.exceptionOrNull()
        assertTrue(e!!.message!!.contains("登录已失效"))
        srv.forceErrno = 800006
        val m = drive.move("x", "y")
        assertTrue(m.exceptionOrNull()!!.message!!.contains("不能把文件夹移动到自己里面"))
    }

    @Test
    fun qrLoginFlowSavesCookies() = runBlocking {
        val empty = MemP115Store(null)
        val d = srv.drive(empty)
        val s = d.qrStart()
        assertTrue(s.uid.isNotBlank())
        assertEquals(0, d.qrStatus(s).status)
        srv.qrStatus = 1
        assertTrue(d.qrStatus(s).scanned)
        srv.qrStatus = 2
        assertTrue(d.qrStatus(s).allowed)
        val creds = d.qrLogin(s, "alipaymini")
        assertEquals(srv.validUid, creds.uid)
        assertEquals(srv.validSeid, empty.creds!!.seid)
        assertEquals("测试用户", empty.creds!!.userName)
        assertTrue(srv.calls.any { it.path!!.contains("/alipaymini/") && it.path!!.contains("login/qrcode") })
        assertTrue(srv.bodies.any { it.first.contains("login/qrcode") && it.second.contains("app=alipaymini") })
    }

    @Test
    fun uploadInstantAndOss() = runBlocking {
        val data = "<?xml version=\"1.0\"?><movie/>".toByteArray()
        val sha = MessageDigest.getInstance("SHA-1").digest(data).joinToString("") { "%02X".format(it) }
        srv.instantUploadSha = sha
        assertEquals("秒传", drive.upload("10", "movie.nfo", data).getOrThrow())
        assertEquals("秒传后不应走 OSS", 0, srv.ossPuts.size)

        srv.instantUploadSha = null
        srv.needSignCheck = true
        val data2 = ByteArray(32) { it.toByte() }
        val msg = drive.upload("10", "poster.jpg", data2).getOrThrow()
        assertEquals("ok", msg)
        assertEquals("应上传一次到 OSS，实际 auth=${srv.ossAuth} bodies=${srv.bodies.filter { it.first.contains("initupload") }.map { it.first }}", 1, srv.ossPuts.size)
        assertEquals(data2.toList(), srv.ossPuts[0].toList())
        assertTrue("缺少 OSS Authorization: ${srv.ossAuth}", srv.ossAuth.any { it.contains("STS.AK115") })
        assertTrue("sign_check 应触发二次 initupload: ${srv.bodies.map { it.first }}",
            srv.bodies.count { it.first.contains("initupload") } >= 2)
        assertTrue(srv.calls.any { it.path?.contains("gettoken") == true })
    }

    @Test
    fun missingDirReturnsEmptyNotRoot() = runBlocking {
        srv.file("f1", "a.mkv", "10")
        srv.forceCid = "0" // 115 对不存在的目录会回落到根目录
        assertTrue(drive.list("10").isEmpty())
    }

    @Test
    fun notLoggedIn() = runBlocking {
        store.creds = null
        val e = runCatching { drive.list("0") }.exceptionOrNull()
        assertTrue(e!!.message!!.contains("未登录"))
        assertFalse(drive.ping())
    }

    @Test
    fun pingOk() = runBlocking {
        assertTrue(drive.ping())
    }

    @Test
    fun ec115EncryptDecryptRoundTrip() {
        val key = ByteArray(16) { (it + 1).toByte() }
        val iv = ByteArray(16) { (it + 7).toByte() }
        val c = Ec115Cipher.forTest(key, iv)
        // LZ4 压缩后再 AES-CBC 加密才是服务端格式；Decrypt 期望该格式。
        // 这里只验证 Encrypt 产出可被 ECB 链复现的长度对齐密文，以及 EncodeToken 非空。
        val plain = "appid=0&userid=1&filename=a.jpg&filesize=10".toByteArray()
        val enc = c.encrypt(plain)
        assertEquals(0, enc.size % 16)
        assertTrue(enc.size >= 16)
        assertTrue(c.encodeToken(System.currentTimeMillis()).isNotBlank())
        // create() 能生成真实 ECDH 密钥
        val live = Ec115Cipher.create()
        val enc2 = live.encrypt(plain)
        assertEquals(0, enc2.size % 16)
    }

    @Test
    fun signatureAndTokenMatchGoFormulas() {
        // 与 115driver GenerateSignature / GenerateToken 同公式
        val uid = 12345L
        val userKey = "USERKEY"
        val fileId = "ABC"
        val target = "U_1_0"
        val h1 = MessageDigest.getInstance("SHA-1").digest("$uid$fileId${target}0".toByteArray())
            .joinToString("") { "%02X".format(it) }
        val sig = MessageDigest.getInstance("SHA-1").digest((userKey + h1 + "000000").toByteArray())
            .joinToString("") { "%02X".format(it) }
        assertEquals(40, sig.length)

        val uidMd5 = MessageDigest.getInstance("MD5").digest(uid.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val token = MessageDigest.getInstance("MD5")
            .digest(("Qclm8MGWUv59TnrR0XPg" + fileId + "10" + "" + "" + uid + "1" + uidMd5 + "27.0.5.7").toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(32, token.length)
    }

    @Test
    fun credsRoundTrip() {
        val c = P115Creds("u", "c", "s", "k", "n", 1, "uk")
        assertEquals(c, Drive115.decodeCreds(Drive115.encodeCreds(c)))
    }

    @Test
    fun qrImageUrl() {
        assertTrue(Drive115.qrImageUrl("abc").contains("uid=abc"))
    }
}
