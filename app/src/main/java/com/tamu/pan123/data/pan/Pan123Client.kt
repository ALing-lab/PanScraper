package com.tamu.pan123.data.pan

import com.tamu.pan123.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 123 网页 API 客户端。写操作仅由执行引擎在用户确认后调用。
 * 密钥/token 绝不写入日志。开发机上的单元测试应使用 FakePanApi，不要打到真实网盘。
 */
object Pan123Client {
    private const val BASE = "https://123pan.com/b/api/"
    private const val LOGIN = "https://login.123pan.com/api/user/sign_in"
    private const val LIST_SLEEP_MS = 500L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val media = "application/json; charset=utf-8".toMediaType()

    private lateinit var http: OkHttpClient

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    fun init() {
        http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .addInterceptor(com.tamu.pan123.data.net.NetLog.interceptor("123"))
            .build()
    }

    private fun authHeaders(token: String): Map<String, String> = mapOf(
        "authorization" to "Bearer $token",
        "platform" to "open_platform",
        "app-version" to "3",
        "accept" to "*/*",
        "user-agent" to "Mozilla/5.0 AppleWebKit/600 Safari/600 Chrome/124.0.0.0 Edg/124.0.0.0",
    )

    private fun requireToken(): String {
        val t = SecureStore.session.value.token
        if (t.isBlank()) error("未登录 123 云盘")
        return t
    }

    private suspend fun request(
        url: String,
        method: String = "GET",
        query: Map<String, String> = emptyMap(),
        body: JSONObject? = null,
        token: String? = null,
    ): PanResponse = withContext(Dispatchers.IO) {
        val builder = url.toHttpUrl().newBuilder()
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        val req = Request.Builder().url(builder.build()).method(
            method,
            body?.toString()?.toRequestBody(media),
        )
        val tok = token ?: requireToken()
        authHeaders(tok).forEach { (k, v) -> req.header(k, v) }
        if (body != null) req.header("content-type", "application/json;charset=UTF-8")
        http.newCall(req.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            try {
                json.decodeFromString(PanResponse.serializer(), text)
            } catch (e: Exception) {
                PanResponse(code = resp.code, message = "响应解析失败 HTTP ${resp.code}")
            }
        }
    }

    /** 账号密码登录。返回 token；失败抛异常（消息给用户看，不含密钥）。 */
    suspend fun loginPassport(passport: String, password: String): String = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("passport", passport.trim())
            .put("password", password)
            .put("remember", true)
        val req = Request.Builder()
            .url(LOGIN)
            .post(payload.toString().toRequestBody(media))
            .header("content-type", "application/json;charset=UTF-8")
            .header("platform", "open_platform")
            .header("app-version", "3")
            .header("user-agent", "Mozilla/5.0 AppleWebKit/600 Safari/600 Chrome/124.0.0.0 Edg/124.0.0.0")
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val obj = try { JSONObject(text) } catch (_: Exception) {
                error("登录返回无法识别（HTTP ${resp.code}）")
            }
            val code = obj.optInt("code", -1)
            val data = obj.optJSONObject("data")
            val token = data?.optString("token").orEmpty().ifBlank { data?.optString("Token").orEmpty() }
            if (code !in listOf(0, 200) || token.isBlank()) {
                val msg = obj.optString("message").ifBlank { obj.optString("msg") }.ifBlank { "未知错误" }
                error(explainLogin(code, msg))
            }
            com.tamu.pan123.data.drive.AccountStore.save123Login(token, passport = passport.trim())
            token
        }
    }

    private fun explainLogin(code: Int, message: String): String {
        val hints = when {
            listOf("验证码", "短信", "安全验证", "滑块", "人机").any { it in message } ||
                "captcha" in message.lowercase() ->
                "123 要求短信/图形验证码。请改用「网页登录」完成验证，或先在官网登录一次后再试。"
            listOf("密码", "账号", "用户不存在", "不正确").any { it in message } ->
                "账号或密码不对（手机号不要带 +86）。"
            "频繁" in message || "稍后" in message || code == 429 ->
                "登录太频繁，请隔一段时间再试。"
            code == 7012 ->
                "登录被风控（海外 IP 常见）。请改用「网页登录」，或在中国大陆网络环境下用密码登录。"
            else -> "如果网页登录正常而这里失败，可能是账号开启了额外验证，请改用网页登录。"
        }
        return "登录失败（code=$code）：$message\n$hints"
    }

    /** 列目录（自动翻页）。只读。 */
    suspend fun listFiles(folderId: Long, pageSize: Int = 100, token: String? = null): List<PanItem> {
        val out = mutableListOf<PanItem>()
        var page = 1
        delay(LIST_SLEEP_MS)
        while (true) {
            val resp = request(
                BASE + "file/list/new", token = token,
                method = "GET",
                query = mapOf(
                    "driveId" to "0",
                    "parentFileId" to folderId.toString(),
                    "limit" to pageSize.toString(),
                    "Page" to page.toString(),
                    "orderBy" to "file_name",
                    "orderDirection" to "asc",
                    "trashed" to "false",
                    "inDirectSpace" to "false",
                    "event" to "homeListFile",
                    "OnlyLookAbnormalFile" to "0",
                ),
            )
            if (!resp.ok) {
                _lastError.value = resp.err()
                error("列目录失败：${resp.err()}")
            }
            val data = resp.data?.let { json.decodeFromJsonElement(ListData.serializer(), it) }
                ?: ListData()
            val batch = data.infoList.map { it.copy(parentFileId = folderId) }
            out += batch
            if (batch.isEmpty() || batch.size < pageSize || data.next == "-1") break
            page++
            delay(LIST_SLEEP_MS)
        }
        _lastError.value = null
        return out
    }

    /** 用当前 token 探测是否有效（列根目录第一页）。 */
    suspend fun ping(token: String? = null): Boolean = try {
        listFiles(0, pageSize = 1, token = token)
        true
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    suspend fun rename(fileId: Long, newName: String, token: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = request(
                BASE + "file/rename", token = token, method = "POST",
                body = JSONObject()
                    .put("driveId", 0)
                    .put("FileId", fileId)
                    .put("fileName", newName)
                    .put("duplicate", 0)
                    .put("event", "fileRename"),
            )
            if (!resp.ok) error(resp.err())
        }
    }

    suspend fun mkdir(parentId: Long, name: String, token: String? = null): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = request(
                BASE + "file/upload_request", token = token, method = "POST",
                body = JSONObject()
                    .put("driveId", 0)
                    .put("duplicate", 0)
                    .put("etag", "")
                    .put("fileName", name)
                    .put("parentFileId", parentId)
                    .put("size", 0)
                    .put("type", 1)
                    .put("NotReuse", true),
            )
            val data = resp.data
            var fid: Long? = null
            if (data != null) {
                val obj = JSONObject(data.toString())
                val info = obj.optJSONObject("Info")
                fid = when {
                    info != null && info.has("FileId") -> info.getLong("FileId")
                    obj.has("FileId") -> obj.getLong("FileId")
                    else -> null
                }
            }
            if (fid == null && resp.code == 5060) {
                fid = listFiles(parentId, token = token).firstOrNull { it.isDir && it.fileName == name }?.fileId
            }
            if (!resp.ok && fid == null) error(resp.err())
            fid ?: error("mkdir 未返回 FileId：${resp.err()}")
        }
    }

    suspend fun move(fileId: Long, destParentId: Long, token: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = request(
                BASE + "file/mod_pid", token = token, method = "POST",
                body = JSONObject()
                    .put("fileIdList", org.json.JSONArray().put(JSONObject().put("FileId", fileId)))
                    .put("parentFileId", destParentId)
                    .put("event", "fileMove"),
            )
            if (!resp.ok) error(resp.err())
        }
    }

    /** 移入回收站（123 网页端「删除」同款接口，可在回收站恢复）。 */
    suspend fun trash(fileId: Long, token: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = request(
                BASE + "file/trash", token = token, method = "POST",
                body = JSONObject()
                    .put("driveId", 0)
                    .put("fileTrashInfoList", org.json.JSONArray().put(JSONObject().put("FileId", fileId)))
                    .put("operation", true)
                    .put("event", "intoRecycle"),
            )
            if (!resp.ok) error(resp.err())
        }
    }

    suspend fun uploadBytes(parentId: Long, name: String, data: ByteArray, token: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val md5 = java.security.MessageDigest.getInstance("MD5")
                    .digest(data).joinToString("") { "%02x".format(it) }
                val resp = request(
                    BASE + "file/upload_request", token = token, method = "POST",
                    body = JSONObject()
                        .put("driveId", 0)
                        .put("duplicate", 0)
                        .put("etag", md5)
                        .put("fileName", name)
                        .put("parentFileId", parentId)
                        .put("size", data.size)
                        .put("type", 0),
                )
                if (!resp.ok) error("upload_request ${resp.err()}")
                val ud = JSONObject(resp.data?.toString() ?: "{}")
                if (ud.optBoolean("Reuse")) return@runCatching "秒传"
                val bucket = ud.getString("Bucket")
                val key = ud.getString("Key")
                val node = ud.getString("StorageNode")
                val uploadId = ud.getString("UploadId")
                val fileId = ud.getLong("FileId")
                val sliceSize = ud.optLong("SliceSize", data.size.toLong()).coerceAtLeast(1)
                if (data.size.toLong() > sliceSize) {
                    // multipart: s3_repare_upload_parts_batch per slice
                    var offset = 0
                    var part = 1
                    while (offset < data.size) {
                        val end = minOf(offset + sliceSize.toInt(), data.size)
                        val chunk = data.copyOfRange(offset, end)
                        val prep = request(
                            BASE + "file/s3_repare_upload_parts_batch", token = token, method = "POST",
                            body = JSONObject()
                                .put("bucket", bucket).put("key", key)
                                .put("storageNode", node).put("uploadId", uploadId)
                                .put("partNumberStart", part).put("partNumberEnd", part + 1),
                        )
                        if (!prep.ok) error("prepare ${prep.err()}")
                        val pd = prep.data ?: error("123 返回的分片上传地址为空")
                        val urls = JSONObject(pd.toString()).getJSONObject("presignedUrls")
                        val putUrl = urls.getString(part.toString())
                        putToStorage(putUrl, chunk)
                        offset = end
                        part++
                    }
                    completeUpload(fileId, bucket, key, node, uploadId, multipart = true, token = token)
                } else {
                    val auth = request(
                        BASE + "file/s3_upload_object/auth", token = token, method = "POST",
                        body = JSONObject()
                            .put("bucket", bucket).put("key", key)
                            .put("storageNode", node).put("uploadId", uploadId),
                    )
                    if (!auth.ok) error("auth ${auth.err()}")
                    val ad = auth.data ?: error("123 返回的上传地址为空")
                    val putUrl = JSONObject(ad.toString())
                        .getJSONObject("presignedUrls").getString("1")
                    putToStorage(putUrl, data)
                    completeUpload(fileId, bucket, key, node, uploadId, multipart = false, token = token)
                }
                "ok"
            }
        }

    private fun putToStorage(url: String, data: ByteArray) {
        val req = Request.Builder().url(url)
            .put(data.toRequestBody("application/octet-stream".toMediaType()))
            // 存储节点 PUT 不要带 authorization
            .header("authorization", "")
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code !in listOf(200, 201, 204)) {
                error("上传存储节点失败 HTTP ${resp.code}（本机网络可能连不上 123 存储服务器）")
            }
        }
    }

    private suspend fun completeUpload(
        fileId: Long, bucket: String, key: String, node: String, uploadId: String, multipart: Boolean,
        token: String? = null,
    ) {
        val resp = request(
            BASE + "file/upload_complete/v2", token = token, method = "POST",
            body = JSONObject()
                .put("fileId", fileId)
                .put("bucket", bucket)
                .put("key", key)
                .put("storageNode", node)
                .put("uploadId", uploadId)
                .put("isMultipart", multipart),
        )
        if (!resp.ok) error("complete ${resp.err()}")
    }
}
