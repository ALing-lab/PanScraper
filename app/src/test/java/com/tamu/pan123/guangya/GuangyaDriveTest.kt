package com.tamu.pan123.guangya

import com.tamu.pan123.data.guangya.GuangyaDrive
import com.tamu.pan123.data.guangya.GyCreds
import com.tamu.pan123.ui.screens.GuangyaLogin
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 全部走本地假服务器；不连接任何真实光鸭账号。 */
class GuangyaDriveTest {
    private lateinit var srv: FakeGuangyaServer
    private lateinit var store: MemTokenStore
    private lateinit var drive: GuangyaDrive

    @Before
    fun setUp() {
        srv = FakeGuangyaServer().start()
        srv.dir("A", "电影")
        srv.dir("B", "剧集")
        store = MemTokenStore(GyCreds("AT-1", "RT-1", System.currentTimeMillis() + 3_600_000, "dev0123456789abcdef0123456789abcd", "sub-001"))
        drive = srv.drive(store)
    }

    @After
    fun tearDown() = srv.shutdown()

    private fun lastBody(suffix: String) = JSONObject(srv.bodies.last { it.first.endsWith(suffix) }.second)

    @Test
    fun listRootSendsHeadersAndPages() = runBlocking {
        repeat(150) { srv.file("x$it", "file%03d.mkv".format(it), "A") }
        val root = drive.list("")
        assertEquals(listOf("剧集", "电影").sorted(), root.map { it.fileName }.sorted())
        assertTrue(root.all { it.isDir })
        val req = srv.calls.first()
        assertEquals("Bearer AT-1", req.getHeader("Authorization"))
        assertEquals("dev0123456789abcdef0123456789abcd", req.getHeader("did"))
        assertEquals("4", req.getHeader("dt"))
        assertEquals("/userres/v1/file/get_file_list", req.path)
        assertEquals("", JSONObject(srv.bodies.first().second).getString("parentId"))

        val files = drive.list("A")
        assertEquals(150, files.size)
        assertEquals(150, files.map { it.fileId }.toSet().size)
        assertFalse(files.first().isDir)
        assertEquals(2, srv.bodies.count { it.first.endsWith("get_file_list") && JSONObject(it.second).getString("parentId") == "A" })
    }

    @Test
    fun renameAndChineseErrors() = runBlocking {
        srv.file("f1", "a.mkv", "A")
        srv.file("f2", "b.mkv", "A")
        assertEquals("f1", drive.rename("f1", "盗梦空间.mkv").getOrThrow())
        assertEquals("盗梦空间.mkv", srv.nodes["f1"]!!.name)
        assertEquals("盗梦空间.mkv", lastBody("rename").getString("newName"))

        val dup = drive.rename("f2", "盗梦空间.mkv")
        assertTrue(dup.isFailure)
        assertTrue(dup.exceptionOrNull()!!.message!!.contains("同名文件已存在"))

        srv.forcedErrors["/v1/file/rename"] = 430 to ""
        val vip = drive.rename("f2", "c.mkv")
        assertTrue(vip.exceptionOrNull()!!.message!!.startsWith("该操作需要光鸭会员"))
    }

    @Test
    fun mkdirReusesExistingAndCreatesNew() = runBlocking {
        srv.dir("S1", "Season 01", "B")
        assertEquals("S1", drive.mkdir("B", "Season 01").getOrThrow())
        assertFalse(srv.bodies.any { it.first.endsWith("create_dir") })
        val id = drive.mkdir("B", "Season 02").getOrThrow()
        assertEquals("Season 02", srv.nodes[id]!!.name)
        assertEquals("B", lastBody("create_dir").getString("parentId"))
    }

    @Test
    fun moveWaitsForBackgroundTask() = runBlocking {
        srv.file("f1", "e01.mkv", "B")
        srv.dir("S1", "Season 01", "B")
        srv.movePendingPolls = 2
        assertEquals("f1", drive.move("f1", "S1").getOrThrow())
        assertEquals("S1", srv.nodes["f1"]!!.parent)
        assertEquals(3, srv.bodies.count { it.first.endsWith("get_task_status") })
        assertEquals("S1", lastBody("move_file").getString("parentId"))
        assertEquals("f1", lastBody("move_file").getJSONArray("fileIds").getString(0))
    }

    @Test
    fun moveTaskFailureIsReported() = runBlocking {
        srv.file("f1", "e01.mkv", "B")
        srv.moveFails = true
        val r = drive.move("f1", "A")
        assertTrue(r.isFailure)
        assertEquals("光鸭后台移动任务失败", r.exceptionOrNull()!!.message)
    }

    @Test
    fun moveTaskTimeout() = runBlocking {
        srv.file("f1", "e01.mkv", "B")
        srv.movePendingPolls = 100
        val r = drive.move("f1", "A")
        assertTrue(r.exceptionOrNull()!!.message!!.contains("超时"))
    }

    @Test
    fun refreshesOnHttp401AndSavesNewTokens() = runBlocking {
        // 服务器认为 AT-1 已失效 → 401 → 用 RT-1 刷新 → 新 token AT-2
        srv.validToken = "AT-only-server-knows"
        // 刷新回调会把 validToken 设成 AT-2
        val list = drive.list("")
        assertEquals(2, list.size)
        assertEquals(1, srv.refreshCount)
        assertEquals("AT-2", store.creds!!.accessToken)
        assertEquals("RT-2", store.creds!!.refreshToken)
        assertTrue(store.creds!!.expiresAt > System.currentTimeMillis())
        val r = srv.calls.first { it.path == "/acct/v1/auth/token" }
        assertEquals("aMe-8VSlkrbQXpUR", r.getHeader("X-Client-Id"))
        assertEquals("wdi10.dev0123456789abcdef0123456789abcd", r.getHeader("X-Device-Sign"))
        val rb = JSONObject(srv.bodies.first { it.first == "/acct/v1/auth/token" }.second)
        assertEquals("refresh_token", rb.getString("grant_type"))
        assertEquals("aMe-8VSlkrbQXpUR", rb.getString("client_id"))
    }

    @Test
    fun refreshesOnBusinessCode117() = runBlocking {
        srv.tokenInvalidAsCode = true
        srv.validToken = "stale"
        assertEquals(2, drive.list("").size)
        assertEquals(1, srv.refreshCount)
    }

    @Test
    fun proactiveRefreshWhenAboutToExpire() = runBlocking {
        store.creds = store.creds!!.copy(expiresAt = System.currentTimeMillis() + 10_000)
        srv.validToken = "unused"
        drive.list("")
        assertEquals(1, srv.refreshCount)
        assertEquals("Bearer AT-2", srv.calls.last().getHeader("Authorization"))
    }

    @Test
    fun refreshFailureAsksToLoginAgain() = runBlocking {
        srv.validToken = "other"
        srv.refreshTokenExpected = "RT-different"
        val e = runCatching { drive.list("") }.exceptionOrNull()
        assertNotNull(e)
        assertTrue(e!!.message!!.contains("重新登录"))
    }

    @Test
    fun notLoggedIn() = runBlocking {
        store.creds = null
        val e = runCatching { drive.list("") }.exceptionOrNull()
        assertTrue(e!!.message!!.contains("未登录"))
        assertFalse(drive.ping())
        assertTrue(srv.calls.isEmpty())
    }

    @Test
    fun uploadViaOssMultipart() = runBlocking {
        val data = "<?xml version=\"1.0\"?><movie/>".toByteArray()
        val msg = drive.upload("A", "movie.nfo", data).getOrThrow()
        assertEquals("ok", msg)
        val tok = lastBody("get_res_center_token")
        assertEquals(2, tok.getInt("capacity"))
        assertEquals("movie.nfo", tok.getString("name"))
        assertEquals("A", tok.getString("parentId"))
        assertEquals(data.size, tok.getJSONObject("res").getInt("fileSize"))
        assertEquals(32, tok.getJSONObject("res").getString("md5").length)
        assertEquals("/userres/v2/get_res_center_token", srv.calls.first { it.path!!.contains("res_center") }.path)
        // OSS：初始化 / 分片 / 完成，每次都带 STS token 和 OSS 签名
        assertEquals(3, srv.ossAuth.size)
        assertTrue(srv.ossAuth.all { it.startsWith("OSS STS.AKTEST:") })
        assertTrue(srv.ossStsHeaders.all { it == "STSTOKEN" })
        assertEquals(String(data), String(srv.ossParts.values.single()))
        val complete = srv.bodies.last { it.first.contains("uploadId=UP1") && !it.first.contains("partNumber") }.second
        assertTrue(complete.contains("<ETag>\"ETAG1\"</ETag>"))
        assertTrue(srv.childrenOf("A").any { it.name == "movie.nfo" })
    }

    /** 真机：上传海报 / NFO 报「NoSuchUpload」，3 次重试都一样。UploadId 里的 + / = 没有 URL 编码，服务器把 + 当成空格 → 找不到上传任务。 */
    @Test
    fun uploadIdWithSpecialCharsIsEncoded() = runBlocking {
        srv.ossUploadId = "0004B9+894A9/1E5=="
        val msg = drive.upload("A", "poster.jpg", ByteArray(64) { it.toByte() }).getOrThrow()
        assertEquals("ok", msg)
        assertTrue(srv.calls.any { it.requestUrl!!.encodedQuery.orEmpty().contains("uploadId=0004B9%2B894A9%2F1E5%3D%3D") })
    }

    @Test
    fun uploadInstantShortcut() = runBlocking {
        srv.instantUpload = true
        srv.uploadProcessingPolls = 2
        assertEquals("秒传", drive.upload("A", "poster.jpg", ByteArray(10) { 1 }).getOrThrow())
        assertTrue(srv.ossAuth.isEmpty())
        assertEquals(3, srv.bodies.count { it.first.endsWith("get_info_by_task_id") })
    }

    @Test
    fun fallsBackToLegacyPrefixOn404() = runBlocking {
        srv.onlyLegacyPrefix = true
        assertEquals(2, drive.list("").size)
        assertTrue(srv.calls.any { it.path == "/nd.bizuserres.s/v1/file/get_file_list" })
        srv.file("f1", "a.mkv", "A")
        drive.upload("A", "x.nfo", "x".toByteArray()).getOrThrow()
        assertTrue(srv.calls.any { it.path == "/nd.bizuserres.s/v1/get_res_center_token" })
    }

    @Test
    fun ossSignatureMatchesAliyunDocVector() {
        // 阿里云 OSS V1 签名文档示例
        val sig = GuangyaDrive.ossSignV1(
            "OtxrzxIsfpFjA7SwPzILwy8Bw21TLhquhboDYROV", "PUT",
            "ODBGOERFMDMzQTczRUY3NUE3NzA5QzdFNUYzMDQxNEM=", "text/html",
            "Thu, 17 Nov 2005 18:49:58 GMT",
            mapOf("X-OSS-Meta-Author" to "foo@bar.com", "X-OSS-Magic" to "abracadabra"),
            "/oss-example/nelson",
        )
        assertEquals("26NBxoKdsyly4EDv6inkoDft/yA=", sig)
    }

    @Test
    fun ossEndpointNormalization() {
        val t = GuangyaDrive.OssTarget("bkt", "https://oss-cn-hangzhou.aliyuncs.com/", "a/b", "ak", "sk", "")
        val d = GuangyaDrive("x", MemTokenStore(null))
        assertEquals("https://bkt.oss-cn-hangzhou.aliyuncs.com", d.ossBase(t))
        assertEquals("https://bkt.oss-cn-hangzhou.aliyuncs.com", d.ossBase(t.copy(endpoint = "bkt.oss-cn-hangzhou.aliyuncs.com")))
    }

    @Test
    fun parsesWebLocalStorageCredentials() {
        val raw = """{"token_type":"Bearer","access_token":"eyJ.a.b","refresh_token":"r1","expires_in":7200,"sub":"12345678","expires_at":"2026-11-09T12:00:00.000Z"}"""
        val c = GuangyaDrive.parseWebCredentials(raw, "web-device-uuid")!!
        assertEquals("eyJ.a.b", c.accessToken)
        assertEquals("r1", c.refreshToken)
        assertEquals("web-device-uuid", c.deviceId)
        assertEquals("12345678", c.sub)
        assertEquals(1794225600000L, c.expiresAt)
        assertNull(GuangyaDrive.parseWebCredentials("""{"refresh_token":"r"}""", null))
        assertNull(GuangyaDrive.parseWebCredentials("not json", null))
        // 没有网页设备 ID 时生成 32 位十六进制
        assertEquals(32, GuangyaDrive.parseWebCredentials(raw, "")!!.deviceId.length)

        // evaluateJavascript 回调值是 JSON 编码过的字符串
        val inner = JSONObject().put("c", raw).put("d", "dev-1").toString()
        val cb = JSONObject.quote(inner)
        val parsed = GuangyaLogin.parseJsResult(cb)!!
        assertEquals("eyJ.a.b", parsed.accessToken)
        assertEquals("dev-1", parsed.deviceId)
        assertNull(GuangyaLogin.parseJsResult("\"\""))
        assertNull(GuangyaLogin.parseJsResult("null"))
    }

    @Test
    fun credsRoundTripDoesNotLoseFields() {
        val c = GyCreds("a", "r", 5, "d", "s")
        assertEquals(c, GuangyaDrive.decodeCreds(GuangyaDrive.encodeCreds(c)))
    }
}
