package com.tamu.pan123.data.p115

import com.tamu.pan123.data.drive.AccountStore
import com.tamu.pan123.data.drive.CloudDrive
import com.tamu.pan123.data.drive.CloudEntry
import com.tamu.pan123.data.drive.DriveAccount
import com.tamu.pan123.data.drive.ProviderKind
import com.tamu.pan123.data.guangya.GuangyaDrive
import kotlinx.coroutines.Dispatchers
import com.tamu.pan123.data.net.fetch
import com.tamu.pan123.data.net.standardTimeouts
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 115 扫码登录后保存的 cookies（加密存账号密钥，绝不写日志）。 */
@Serializable
data class P115Creds(
    val uid: String,
    val cid: String,
    val seid: String,
    val kid: String = "",
    val userName: String = "",
    val userId: Long = 0,
    /** 上传用 userkey；可懒加载。 */
    val userKey: String = "",
)

interface P115TokenStore {
    fun load(): P115Creds?
    fun save(c: P115Creds)
}

class P115ApiException(val code: Int, message: String) : IOException(message)

data class QrSession(val uid: String, val time: Long, val sign: String, val content: String)
data class QrStatus(val status: Int, val msg: String) {
    val waiting get() = status == 0
    val scanned get() = status == 1
    val allowed get() = status == 2
    val expired get() = status == -1
    val canceled get() = status == -2
}

/**
 * 115 网盘（Cookie 网页接口）。移植自 SheltonZhu/115driver。
 *
 * - 登录：扫码，设备身份默认 [LOGIN_APP]=alipaymini（支付宝小程序），不挤掉电脑网页会话。
 * - ID 稳定；根目录 "0"。
 * - 上传：ec115 ECDH 握手 → 秒传 / 阿里云 OSS PutObject（带 callback）。
 * - 限频：所有请求间隔 [pathIntervalMs]（默认 500ms）。
 */
class Drive115(
    override val accountId: String,
    private val store: P115TokenStore,
    private val apiBase: String = WEB_API,
    private val qrBase: String = QR_API,
    private val passportBase: String = PASSPORT_API,
    private val uploadBase: String = UPLOAD_API,
    private val proApiBase: String = PRO_API,
    http: OkHttpClient? = null,
    /** 115 风控较严：所有请求间隔 ≥500ms（与 OpenList 默认 2 次/秒一致）。 */
    private val pathIntervalMs: Long = 500,
    /** 测试：把 OSS 端点重写到本地假服务器。 */
    private val ossUrlOverride: String? = null,
    private val ossEndpoint: String = OSS_ENDPOINT,
    private val statusUrl: String = "https://my.115.com/",
    /** 测试：跳过 ec115，initupload 明文往来（假服务器用）。 */
    private val skipEcdh: Boolean = false,
    private val cipherFactory: () -> Ec115Cipher = { Ec115Cipher.create() },
) : CloudDrive {
    override val kind = ProviderKind.P115
    override val writeIntervalMs: Long get() = pathIntervalMs

    private val http: OkHttpClient = (http?.newBuilder() ?: OkHttpClient.Builder()
        .standardTimeouts(callSec = 60)
        .followRedirects(false))
        .addInterceptor(com.tamu.pan123.data.net.NetLog.interceptor("115"))
        .build()
    private val rateLock = Mutex()
    private var lastCall = 0L
    private var cachedUserKey: String? = null
    private var cachedUserId: Long = 0

    // ---------------- 凭据 ----------------

    private fun creds(): P115Creds = store.load()?.takeIf { it.uid.isNotBlank() && it.cid.isNotBlank() && it.seid.isNotBlank() }
        ?: throw P115ApiException(99, "115 网盘未登录，请在「网盘账号」里扫码登录")

    private fun cookieHeader(c: P115Creds): String = buildString {
        append("UID=").append(c.uid).append("; CID=").append(c.cid).append("; SEID=").append(c.seid)
        if (c.kid.isNotBlank()) append("; KID=").append(c.kid)
    }

    // ---------------- 请求 ----------------

    private suspend fun pace() {
        if (pathIntervalMs <= 0) return
        val w = rateLock.withLock {
            val now = System.currentTimeMillis()
            val wait = (lastCall + pathIntervalMs - now).coerceAtLeast(0)
            lastCall = now + wait
            wait
        }
        if (w > 0) delay(w)
    }

    private suspend fun get(url: String, query: Map<String, String> = emptyMap(), withCookie: Boolean = true): Pair<Int, String> {
        pace()
        val b = url.toHttpUrl().newBuilder()
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        val rb = Request.Builder().url(b.build()).header("User-Agent", UA).header("Accept", "application/json")
        if (withCookie) rb.header("Cookie", cookieHeader(creds()))
        return withContext(Dispatchers.IO) {
            try {
                http.fetch(rb.get().build()).let { r -> r.code to r.text }
            } catch (e: IOException) {
                throw IOException("连接 115 网盘失败：${e.message ?: "网络错误"}", e)
            }
        }
    }

    private suspend fun postForm(url: String, form: Map<String, String>, withCookie: Boolean = true): Pair<Int, String> {
        pace()
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val rb = Request.Builder().url(url).post(body)
            .header("User-Agent", UA).header("Accept", "application/json")
        if (withCookie) rb.header("Cookie", cookieHeader(creds()))
        return withContext(Dispatchers.IO) {
            try {
                http.fetch(rb.build()).let { r -> r.code to r.text }
            } catch (e: IOException) {
                throw IOException("连接 115 网盘失败：${e.message ?: "网络错误"}", e)
            }
        }
    }

    private fun requireOk(status: Int, text: String, label: String): JSONObject {
        if (status == 401 || status == 403) throw P115ApiException(99, "115 登录已失效，请重新扫码登录")
        if (status == 405 || status == 429) throw P115ApiException(status, "115 请求太频繁，被风控拦截，请稍后再试")
        if (status !in 200..299) throw P115ApiException(status, "115 网盘服务异常（HTTP $status）")
        return runCatching { JSONObject(text) }.getOrNull()
            ?: throw P115ApiException(-1, "115 网盘返回了无法解析的数据（可能需要在网页端完成安全验证）")
    }

    private fun checkState(o: JSONObject, fallbackCode: Int = -1) {
        val state = when {
            o.has("state") && o.get("state") is Boolean -> o.getBoolean("state")
            o.has("state") -> o.optInt("state") == 1
            else -> true
        }
        if (state) return
        val code = sequenceOf(o.optInt("errno", 0), o.optInt("errNo", 0), o.optInt("code", 0))
            .firstOrNull { it != 0 } ?: fallbackCode
        val msg = listOf("error", "message", "msg").map { o.optString(it) }.firstOrNull { it.isNotBlank() }
        throw P115ApiException(code, errorText(code, msg))
    }

    // ---------------- 扫码登录（无账号 cookie） ----------------

    suspend fun qrStart(): QrSession {
        val (_, text) = get("$qrBase/api/1.0/web/1.0/token", withCookie = false)
        val o = requireOk(200, text, "token")
        if (o.optInt("state") != 1) throw P115ApiException(o.optInt("code"), errorText(o.optInt("code"), o.optString("message")))
        val d = o.getJSONObject("data")
        return QrSession(
            uid = d.getString("uid"),
            time = d.optLong("time"),
            sign = d.getString("sign"),
            content = d.optString("qrcode"),
        )
    }

    suspend fun qrStatus(s: QrSession): QrStatus {
        val (_, text) = get(
            "$qrBase/get/status/",
            mapOf("uid" to s.uid, "time" to s.time.toString(), "sign" to s.sign, "_" to System.currentTimeMillis().toString()),
            withCookie = false,
        )
        val o = requireOk(200, text, "status")
        if (o.optInt("state") != 1) {
            // 过期等也会走 data
            if (o.has("data")) {
                val d = o.getJSONObject("data")
                return QrStatus(d.optInt("status"), d.optString("msg"))
            }
            throw P115ApiException(o.optInt("code"), errorText(o.optInt("code"), o.optString("message")))
        }
        val d = o.getJSONObject("data")
        return QrStatus(d.optInt("status"), d.optString("msg"))
    }

    /**
     * 扫码确认后换 cookie。[app] 默认 alipaymini，不挤掉电脑上的网页登录。
     * 返回保存后的凭据。
     */
    suspend fun qrLogin(s: QrSession, app: String = LOGIN_APP): P115Creds {
        val url = "$passportBase/app/1.0/$app/1.0/login/qrcode"
        val (_, text) = postForm(url, mapOf("account" to s.uid, "app" to app), withCookie = false)
        val o = requireOk(200, text, "login")
        if (o.optInt("state") != 1) throw P115ApiException(o.optInt("code"), errorText(o.optInt("code"), o.optString("message").ifBlank { o.optString("error") }))
        val d = o.getJSONObject("data")
        val cookie = d.getJSONObject("cookie")
        val c = P115Creds(
            uid = cookie.getString("UID"),
            cid = cookie.getString("CID"),
            seid = cookie.getString("SEID"),
            kid = cookie.optString("KID"),
            userName = d.optString("user_name"),
            userId = d.optLong("user_id"),
        )
        store.save(c)
        cachedUserId = c.userId
        return c
    }

    // ---------------- CloudDrive ----------------

    override suspend fun list(folderId: String): List<CloudEntry> {
        val cid = folderId.ifBlank { "0" }
        val out = ArrayList<CloudEntry>()
        var offset = 0
        val limit = 1150
        while (true) {
            val (_, text) = get(
                "$apiBase/files",
                mapOf(
                    "aid" to "1", "cid" to cid, "o" to "user_ptime", "asc" to "1",
                    "offset" to offset.toString(), "show_dir" to "1", "limit" to limit.toString(),
                    "snap" to "0", "natsort" to "0", "record_open_time" to "1", "format" to "json", "fc_mix" to "0",
                ),
            )
            val o = requireOk(200, text, "list")
            checkState(o)
            // cid 校验：被踢到别的目录时 115 会返回别的 cid
            // 目录不存在时 115 会返回别的目录（通常是根目录）的内容：按空目录处理，避免误操作
            val returnedCid = o.opt("cid")?.toString()?.trim('"') ?: cid
            if (returnedCid != cid) return out
            val count = o.optInt("count")
            val arr = o.optJSONArray("data") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val it = arr.getJSONObject(i)
                val isFile = it.has("fid") && it.optString("fid").isNotBlank()
                val id = if (isFile) it.getString("fid") else it.opt("cid")?.toString()?.trim('"').orEmpty()
                if (id.isBlank()) continue
                out += CloudEntry(
                    fileId = id,
                    fileName = it.optString("n"),
                    isDir = !isFile,
                    size = it.optLong("s"),
                    parentFileId = if (isFile) it.opt("cid")?.toString()?.trim('"') else it.optString("pid").ifBlank { cid },
                    etag = it.optString("sha").ifBlank { null },
                )
            }
            offset += arr.length()
            if (arr.length() == 0 || offset >= count) break
            if (offset > 200_000) break
        }
        return out
    }

    override suspend fun rename(fileId: String, newName: String): Result<String> = runCatching {
        val (_, text) = postForm(
            "$apiBase/files/batch_rename",
            mapOf("fid" to fileId, "file_name" to newName, "files_new_name[$fileId]" to newName),
        )
        checkState(requireOk(200, text, "rename"))
        fileId
    }

    override suspend fun mkdir(parentId: String, name: String): Result<String> = runCatching {
        val pid = parentId.ifBlank { "0" }
        list(pid).firstOrNull { it.isDir && it.fileName.equals(name, true) }?.let { return@runCatching it.fileId }
        val (_, text) = postForm("$apiBase/files/add", mapOf("pid" to pid, "cname" to name))
        val o = requireOk(200, text, "mkdir")
        try {
            checkState(o)
        } catch (e: P115ApiException) {
            if (e.code == 20004) {
                list(pid).firstOrNull { it.isDir && it.fileName.equals(name, true) }?.let { return@runCatching it.fileId }
            }
            throw e
        }
        o.opt("cid")?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
            ?: o.optString("file_id").takeIf { it.isNotBlank() }
            ?: list(pid).firstOrNull { it.isDir && it.fileName == name }?.fileId
            ?: throw P115ApiException(-1, "115 新建文件夹「$name」后没有拿到文件夹 ID")
    }

    override suspend fun move(fileId: String, destParentId: String): Result<String> = runCatching {
        val dest = destParentId.ifBlank { "0" }
        val (_, text) = postForm("$apiBase/files/move", mapOf("pid" to dest, "fid[0]" to fileId))
        checkState(requireOk(200, text, "move"))
        fileId
    }

    override val trashSupport = com.tamu.pan123.data.drive.TrashSupport.RECYCLE

    /** 115 的「删除」= 移入回收站（rb/delete）。 */
    override suspend fun trash(fileId: String): Result<String> = runCatching {
        val (_, text) = postForm("$apiBase/rb/delete", mapOf("fid[0]" to fileId, "ignore_warn" to "1"))
        checkState(requireOk(200, text, "delete"))
        "已移入回收站"
    }

    override suspend fun upload(parentId: String, name: String, data: ByteArray): Result<String> = runCatching {
        val pid = parentId.ifBlank { "0" }
        ensureUploadMeta()
        val digest = digest(data)
        val init = rapidUpload(pid, name, digest, data)
        when (init.status) {
            2 -> return@runCatching "秒传"
            1 -> {
                // 需要走 OSS
                val token = ossToken()
                putOss(init, token, data)
                // 用 sha1 确认出现在目录里（最多等几秒）
                repeat(5) {
                    if (list(pid).any { !it.isDir && it.etag.equals(digest.quickId, true) }) return@runCatching "ok"
                    delay(400)
                }
                "ok"
            }
            else -> throw P115ApiException(init.status, "115 上传初始化返回异常状态 ${init.status}")
        }
    }

    override suspend fun ping(): Boolean = runCatching {
        val (_, text) = get(statusUrl, mapOf("ct" to "guide", "ac" to "status", "_" to System.currentTimeMillis().toString()))
        // 这个接口返回 {state:true/false}；也可能是 HTML。再退回列根目录
        val o = runCatching { JSONObject(text) }.getOrNull()
        if (o != null && o.has("state")) return@runCatching o.optBoolean("state")
        list("0"); true
    }.getOrDefault(false)

    // ---------------- 上传 ----------------

    data class Digest(val size: Long, val preId: String, val quickId: String)

    data class InitUpload(
        val status: Int,
        val pickCode: String = "",
        val bucket: String = "",
        val `object`: String = "",
        val callback: String = "",
        val callbackVar: String = "",
        val sha1: String = "",
        val signKey: String = "",
        val signCheck: String = "",
    )

    data class OssToken(val ak: String, val sk: String, val token: String, val expiration: String = "")

    private fun digest(data: ByteArray): Digest {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val pre = data.copyOfRange(0, minOf(data.size, 128 * 1024))
        val preId = sha1.digest(pre).joinToString("") { "%02X".format(it) }
        sha1.reset()
        val quick = sha1.digest(data).joinToString("") { "%02X".format(it) }
        return Digest(data.size.toLong(), preId, quick)
    }

    private suspend fun ensureUploadMeta() {
        if (!cachedUserKey.isNullOrBlank() && cachedUserId != 0L) return
        val c = creds()
        if (c.userKey.isNotBlank() && c.userId != 0L) {
            cachedUserKey = c.userKey; cachedUserId = c.userId; return
        }
        val (_, text) = postForm("$proApiBase/app/uploadinfo", emptyMap())
        val o = requireOk(200, text, "uploadinfo")
        checkState(o)
        cachedUserId = o.optLong("user_id").takeIf { it != 0L } ?: c.userId
        cachedUserKey = o.optString("userkey")
        if (cachedUserKey.isNullOrBlank()) throw P115ApiException(-1, "115 上传信息缺少 userkey，可能账号未开通上传权限")
        store.save(c.copy(userId = cachedUserId, userKey = cachedUserKey!!))
    }

    private fun signature(fileId: String, target: String): String {
        val uid = cachedUserId.toString()
        val h1 = Ec115Cipher.sha1HexUpper((uid + fileId + target + "0").toByteArray())
        return Ec115Cipher.sha1HexUpper((cachedUserKey + h1 + "000000").toByteArray())
    }

    private fun token(fileId: String, preId: String, t: String, size: String, signKey: String, signVal: String): String {
        val uid = cachedUserId.toString()
        val uidMd5 = Ec115Cipher.md5Hex(uid.toByteArray())
        return Ec115Cipher.md5Hex((MD5_SALT + fileId + size + signKey + signVal + uid + t + uidMd5 + APP_VER).toByteArray())
    }

    private fun digestRange(data: ByteArray, rangeSpec: String): String {
        val parts = rangeSpec.split("-")
        val start = parts[0].toLong()
        val end = parts[1].toLong()
        val slice = data.copyOfRange(start.toInt(), (end + 1).toInt().coerceAtMost(data.size))
        return Ec115Cipher.sha1HexUpper(slice)
    }

    private suspend fun rapidUpload(dirId: String, fileName: String, d: Digest, data: ByteArray): InitUpload {
        val ecdh = if (skipEcdh) null else cipherFactory()
        val target = "U_1_$dirId"
        val sizeStr = d.size.toString()
        var signKey = ""
        var signVal = ""
        var attempt = 0
        while (attempt++ < 4) {
            val t = System.currentTimeMillis()
            val kEc = ecdh?.encodeToken(t).orEmpty()
            val form = LinkedHashMap<String, String>().apply {
                put("appid", "0"); put("appversion", APP_VER)
                put("userid", cachedUserId.toString()); put("filename", fileName)
                put("filesize", sizeStr); put("fileid", d.quickId); put("target", target)
                put("sig", signature(d.quickId, target)); put("topupload", "true")
                put("t", t.toString())
                put("token", token(d.quickId, d.preId, t.toString(), sizeStr, signKey, signVal))
                if (signKey.isNotBlank()) { put("sign_key", signKey); put("sign_val", signVal) }
            }
            val body = form.entries.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            }.toByteArray()
            val payload = if (ecdh == null) body else ecdh.encrypt(body)
            pace()
            val urlB = "$uploadBase/4.0/initupload.php".toHttpUrl().newBuilder()
            if (!skipEcdh) urlB.addQueryParameter("k_ec", kEc)
            val (status, raw) = withContext(Dispatchers.IO) {
                val req = Request.Builder().url(urlB.build())
                    .header("User-Agent", UA).header("Cookie", cookieHeader(creds()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .post(payload.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .build()
                http.newCall(req).execute().use { r -> r.code to (r.body?.bytes() ?: ByteArray(0)) }
            }
            if (status !in 200..299) throw P115ApiException(status, "115 上传初始化失败（HTTP $status）")
            val plain = if (ecdh == null) raw else try { ecdh.decrypt(raw) } catch (e: Exception) {
                throw P115ApiException(-4, "115 上传握手解密失败：${e.message}")
            }
            val o = JSONObject(String(plain))
            val code = o.optInt("statuscode")
            if (code != 0 && code != 701) throw P115ApiException(code, errorText(code, o.optString("statusmsg")))
            val st = when (val v = o.opt("status")) {
                is Boolean -> if (v) 1 else -1
                else -> o.optInt("status")
            }
            val cb = o.optJSONObject("callback")
            val init = InitUpload(
                status = st,
                pickCode = o.optString("pickcode"),
                bucket = o.optString("bucket"),
                `object` = o.optString("object"),
                callback = cb?.optString("callback").orEmpty(),
                callbackVar = cb?.optString("callback_var").orEmpty(),
                sha1 = d.quickId,
                signKey = o.optString("sign_key"),
                signCheck = o.optString("sign_check"),
            )
            if (st == 7) {
                // 需要二次摘要校验
                signKey = init.signKey
                signVal = digestRange(data, init.signCheck)
                continue
            }
            return init
        }
        throw P115ApiException(-4, "115 上传初始化重试次数过多")
    }

    private suspend fun ossToken(): OssToken {
        val (_, text) = get("$uploadBase/3.0/gettoken.php")
        val o = requireOk(200, text, "osstoken")
        if (o.optString("StatusCode") != "200" && !o.has("AccessKeyID")) {
            throw P115ApiException(-4, "115 获取上传凭证失败")
        }
        return OssToken(
            ak = o.getString("AccessKeyID"),
            sk = o.getString("AccessKeySecret"),
            token = o.getString("SecurityToken"),
            expiration = o.optString("Expiration"),
        )
    }

    private suspend fun putOss(init: InitUpload, tok: OssToken, data: ByteArray) {
        if (init.bucket.isBlank() || init.`object`.isBlank()) {
            throw P115ApiException(-4, "115 上传凭证不完整（缺少 bucket/object）")
        }
        val base = ossUrlOverride?.trimEnd('/')
            ?: "https://${init.bucket}.$ossEndpoint"
        val encKey = init.`object`.split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val url = "$base/$encKey"
        val date = GuangyaDrive.httpDate()
        val cbB64 = Base64.getEncoder().encodeToString(init.callback.toByteArray())
        val cbVarB64 = Base64.getEncoder().encodeToString(init.callbackVar.toByteArray())
        val ossHeaders = sortedMapOf(
            "x-oss-security-token" to tok.token,
            "x-oss-callback" to cbB64,
            "x-oss-callback-var" to cbVarB64,
        )
        val resource = "/${init.bucket}/${init.`object`}"
        val contentType = "application/octet-stream"
        val sig = GuangyaDrive.ossSignV1(tok.sk, "PUT", "", contentType, date, ossHeaders, resource)
        val req = Request.Builder().url(url.toHttpUrl())
            .put(data.toRequestBody(contentType.toMediaType()))
            .header("Date", date)
            .header("Authorization", "OSS ${tok.ak}:$sig")
            .header("User-Agent", OSS_UA)
            .header("Content-Type", contentType)
            .apply { ossHeaders.forEach { (k, v) -> header(k, v) } }
            .build()
        withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    val t = r.body?.string().orEmpty()
                    val c = Regex("<Code>([^<]+)</Code>").find(t)?.groupValues?.get(1) ?: "HTTP ${r.code}"
                    throw P115ApiException(-4, "115 存储上传失败：$c")
                }
            }
        }
    }

    companion object {
        const val WEB_API = "https://webapi.115.com"
        const val QR_API = "https://qrcodeapi.115.com"
        const val PASSPORT_API = "https://passportapi.115.com"
        const val UPLOAD_API = "https://uplb.115.com"
        const val PRO_API = "https://proapi.115.com"
        const val LOGIN_APP = "alipaymini"
        const val APP_VER = "27.0.5.7"
        const val UA = "Mozilla/5.0 115Browser/$APP_VER"
        const val OSS_ENDPOINT = "cn-shenzhen.oss.aliyuncs.com"
        const val OSS_UA = "aliyun-sdk-android/2.9.1"
        const val MD5_SALT = "Qclm8MGWUv59TnrR0XPg"
        const val QR_IMAGE = "https://qrcodeapi.115.com/api/1.0/mac/1.0/qrcode?uid=%s"

        private val jsonStatic = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun from(acct: DriveAccount): Drive115 = Drive115(acct.id, AccountP115Store(acct.id))

        fun encodeCreds(c: P115Creds): String = jsonStatic.encodeToString(P115Creds.serializer(), c)
        fun decodeCreds(s: String?): P115Creds? =
            s?.let { runCatching { jsonStatic.decodeFromString(P115Creds.serializer(), it) }.getOrNull() }

        fun errorText(code: Int, msg: String?): String {
            val base = when (code) {
                99, 990001 -> "115 登录已失效，请重新扫码登录"
                20004, 21003 -> "目标已存在"
                800006 -> "不能把文件夹移动到自己里面"
                91002 -> "不能复制到自己里面"
                40101035 -> "账号在其它设备登录，本机已下线"
                40101037 -> "115 会话已退出，请重新扫码登录"
                40199002 -> "二维码已过期，请刷新"
                402, 400 -> "115 上传签名无效"
                1001, 200900, 990002, 40100000 -> "115 请求参数不合法"
                else -> "115 网盘返回错误"
            }
            val m = msg?.trim().orEmpty()
            return if (m.isEmpty()) {
                if (code == 0 || code == -1) base else "$base（code $code）"
            } else "$base：$m（code $code）"
        }

        fun qrImageUrl(uid: String): String = QR_IMAGE.format(Locale.US, uid)
    }
}

class AccountP115Store(private val accountId: String) : P115TokenStore {
    override fun load(): P115Creds? = Drive115.decodeCreds(AccountStore.secretOf(accountId))
    override fun save(c: P115Creds) = AccountStore.saveSecret(accountId, Drive115.encodeCreds(c))
}
