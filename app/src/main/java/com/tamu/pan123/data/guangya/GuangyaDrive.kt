package com.tamu.pan123.data.guangya

import com.tamu.pan123.data.drive.AccountStore
import com.tamu.pan123.data.drive.CloudDrive
import com.tamu.pan123.data.drive.CloudEntry
import com.tamu.pan123.data.drive.DriveAccount
import com.tamu.pan123.data.drive.ProviderKind
import com.tamu.pan123.data.net.NetLog
import com.tamu.pan123.data.net.fetch
import com.tamu.pan123.data.net.standardTimeouts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 光鸭网页登录后保存的凭据（存在加密的账号密钥里，绝不写日志）。 */
@Serializable
data class GyCreds(
    val accessToken: String,
    val refreshToken: String = "",
    /** 过期时间，毫秒时间戳；0 表示未知。 */
    val expiresAt: Long = 0,
    /** 设备 ID：优先取网页的 swangpan_web_device_id，没有则随机 32 位十六进制。 */
    val deviceId: String = "",
    val sub: String = "",
) {
    /** 不在日志 / 崩溃报告里泄露令牌。 */
    override fun toString() = "GyCreds(accessToken=***, refreshToken=${if (refreshToken.isBlank()) "" else "***"}, expiresAt=$expiresAt, sub=$sub)"
}

/** 凭据读写；默认存账号密钥，测试可注入内存实现。 */
interface GyTokenStore {
    fun load(): GyCreds?
    fun save(c: GyCreds)
}

class GuangyaApiException(val code: Int, message: String) : IOException(message)

/**
 * 光鸭云盘（网页版接口）。参考 OpenList guangyapan 驱动 + www.guangyapan.com 前端 JS。
 *
 * - 根目录 ID 为 ""；ID 稳定（改名 / 移动不变）。
 * - 业务接口：POST https://api.guangyapan.com/userres/v1/...，Bearer + did + dt:4。
 *   若某路径返回 HTTP 404，回退到 OpenList 使用的 /nd.bizuserres.s/v1/... 同名接口。
 * - 移动是后台任务：拿 taskId 后轮询 get_task_status（2 完成，3/-1 失败）。
 * - 上传：get_res_center_token（capacity=2 只要阿里云 OSS）→ 秒传(156) 或 OSS 分片上传（STS + V1 签名）
 *   → 轮询 get_info_by_task_id 拿到 fileId。
 * - access_token 将过期 / 401 / 403 / 业务码 117 时用 refresh_token 刷新一次并重试。
 * - 限频：同一接口两次调用至少间隔 [pathIntervalMs]（OpenList 用 500ms）。
 */
class GuangyaDrive(
    override val accountId: String,
    private val store: GyTokenStore,
    private val apiBase: String = API_BASE,
    private val accountBase: String = ACCOUNT_BASE,
    http: OkHttpClient? = null,
    private val pathIntervalMs: Long = 500,
    private val pollIntervalMs: Long = 1000,
    private val maxPolls: Int = 60,
    /** 测试用：把 OSS 端点重写到本地假服务器。 */
    private val ossUrlOverride: String? = null,
    /** 单个请求最长耗时（含 DNS / 连接 / 读取），超时即报错，不会无限等待。 */
    private val callTimeoutMs: Long = 60_000,
    /** 列目录最多翻页数（每页 100 条），防止分页参数不生效时死循环。 */
    private val maxPages: Int = 300,
    /** 只读请求（列目录 / 查任务）遇到网络错误、429、5xx 时的重试等待。 */
    private val retryDelaysMs: List<Long> = listOf(2_000, 5_000),
) : CloudDrive {
    override val kind = ProviderKind.GUANGYA
    override val writeIntervalMs: Long get() = pathIntervalMs

    private val http: OkHttpClient = (http?.newBuilder() ?: OkHttpClient.Builder().standardTimeouts())
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .addInterceptor(NetLog.interceptor(TAG))
        .build()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonType = "application/json".toMediaType()
    private val refreshLock = Mutex()
    private val lastCall = HashMap<String, Long>()
    private val rateLock = Mutex()

    // ---------------- 凭据 ----------------

    private fun creds(): GyCreds = store.load()?.takeIf { it.accessToken.isNotBlank() }
        ?: throw GuangyaApiException(-401, "光鸭云盘未登录，请在「网盘账号」里重新登录")

    private fun deviceId(c: GyCreds) = c.deviceId.ifBlank { DEFAULT_DEVICE }

    /** 刷新 access_token。并发调用只刷一次。 */
    internal suspend fun refresh(stale: String?): GyCreds = refreshLock.withLock {
        val cur = creds()
        if (stale != null && cur.accessToken != stale) return@withLock cur // 别的协程已经刷新过
        if (cur.refreshToken.isBlank()) throw GuangyaApiException(-401, "光鸭登录已过期，请重新登录")
        val body = buildJsonObject {
            put("client_id", CLIENT_ID)
            put("grant_type", "refresh_token")
            put("refresh_token", cur.refreshToken)
        }.toString()
        val req = Request.Builder().url(accountBase.trimEnd('/') + "/v1/auth/token")
            .post(body.toRequestBody(jsonType))
            .apply { accountHeaders(deviceId(cur)).forEach { (k, v) -> header(k, v) } }
            .build()
        val res = try { http.fetch(req) } catch (e: CancellationException) { throw e } catch (e: IOException) {
            NetLog.error(TAG, "refresh ${netReason(e)}")
            throw GuangyaApiException(-6, "刷新光鸭登录失败：${netReason(e)}")
        }
        if (res.code !in 200..299) {
            NetLog.error(TAG, "refresh HTTP ${res.code}")
            if (res.code in 400..499) store.save(cur.copy(expiresAt = 1)) // 标记已过期，账号页显示「已过期」
            throw GuangyaApiException(-401, "光鸭登录已过期，请重新登录（刷新失败 HTTP ${res.code}）")
        }
        val text = res.text
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw GuangyaApiException(-401, "光鸭刷新登录返回异常，请重新登录")
        val at = o.str("access_token")
        if (at.isNullOrBlank()) throw GuangyaApiException(-401, "光鸭登录已过期，请重新登录")
        val exIn = o.long("expires_in") ?: 0
        val next = cur.copy(
            accessToken = at,
            refreshToken = o.str("refresh_token")?.takeIf { it.isNotBlank() } ?: cur.refreshToken,
            expiresAt = if (exIn > 0) System.currentTimeMillis() + exIn * 1000 else 0,
            sub = o.str("sub")?.takeIf { it.isNotBlank() } ?: cur.sub,
        )
        store.save(next)
        next
    }

    private suspend fun validCreds(): GyCreds {
        val c = creds()
        if (c.expiresAt > 0 && c.expiresAt - System.currentTimeMillis() < 60_000 && c.refreshToken.isNotBlank()) {
            return refresh(c.accessToken)
        }
        return c
    }

    // ---------------- 请求 ----------------

    private suspend fun pace(path: String) {
        if (pathIntervalMs <= 0) return
        val wait = rateLock.withLock {
            val now = System.currentTimeMillis()
            val last = lastCall[path] ?: 0
            val w = (last + pathIntervalMs - now).coerceAtLeast(0)
            lastCall[path] = now + w
            w
        }
        if (wait > 0) delay(wait)
    }

    /**
     * 调用业务接口，返回完整响应对象（含 code/msg/data）。
     * [allow] 中的业务码视为正常返回（如上传的 156 秒传）。
     */
    internal suspend fun api(path: String, body: JsonObject, allow: Set<Int> = emptySet(), idempotent: Boolean = false): JsonObject {
        val primary = "/userres$path"
        return try {
            call(primary, body, allow, idempotent)
        } catch (e: NotFound) {
            call("/nd.bizuserres.s$path".replace("/v2/", "/v1/"), body, allow, idempotent)
        }
    }

    /** 连不上服务器（TCP 连接失败 / 连接超时 / DNS 失败）：重试意义不大，立即报错。 */
    internal fun isConnectFailure(e: IOException): Boolean =
        e is java.net.ConnectException || e is java.net.UnknownHostException || e is java.net.NoRouteToHostException ||
            (e is java.net.SocketTimeoutException && e.message.orEmpty().startsWith("failed to connect", ignoreCase = true)) ||
            e.message.orEmpty().startsWith("failed to connect", ignoreCase = true)

    private fun netReason(e: IOException): String = when {
        e is java.net.UnknownHostException -> "无法解析光鸭服务器地址，请检查网络 / DNS"
        isConnectFailure(e) -> "网络无法连接光鸭服务器（${e.message.orEmpty().substringAfter("to ").substringBefore("/").ifBlank { "api.guangyapan.com" }}），请检查 Wi-Fi、VPN 或代理设置后重试"
        e is java.io.InterruptedIOException -> "请求超时（${callTimeoutMs / 1000} 秒内无完整响应）"
        else -> e.message ?: "网络错误"
    }

    private class NotFound : IOException("404")

    private suspend fun call(path: String, body: JsonObject, allow: Set<Int>, idempotent: Boolean = false): JsonObject {
        var c = validCreds()
        var retried = false
        var attempt = 0
        suspend fun backoff(why: String): Boolean {
            if (!idempotent || attempt >= retryDelaysMs.size) return false
            val wait = retryDelaysMs[attempt++]
            NetLog.retrying(TAG, "$why，第 $attempt/${retryDelaysMs.size} 次重试", wait)
            delay(wait)
            NetLog.clearRetry()
            return true
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            pace(path)
            val req = Request.Builder().url(apiBase.trimEnd('/') + path)
                .post(body.toString().toRequestBody(jsonType))
                .header("Authorization", "Bearer ${c.accessToken}")
                .header("did", deviceId(c))
                .header("dt", "4")
                .header("traceparent", traceparent())
                .header("Content-Type", "application/json")
                .build()
            val res = try {
                http.fetch(req)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                val why = netReason(e)
                NetLog.error(TAG, "$path $why")
                if (isConnectFailure(e)) throw GuangyaApiException(-6, why)
                if (backoff(why)) continue
                throw IOException("连接光鸭云盘失败：$why", e)
            }
            val status = res.code
            val text = res.text
            if (status == 404) throw NotFound()
            if ((status == 401 || status == 403) && !retried) {
                c = refresh(c.accessToken); retried = true; continue
            }
            if (status == 429 || status >= 500) {
                val why = if (status == 429) "请求太频繁（HTTP 429）" else "服务异常（HTTP $status）"
                NetLog.error(TAG, "$path $why")
                if (backoff(why)) continue
                if (status == 429) throw GuangyaApiException(429, "光鸭云盘请求太频繁，请稍后再试")
            }
            if (status !in 200..299) throw GuangyaApiException(status, "光鸭云盘服务异常（HTTP $status）")
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: throw GuangyaApiException(-1, "光鸭云盘返回了无法解析的数据")
            val code = o.long("code")?.toInt() ?: 0
            if (code in allow) return o
            if (code == CODE_TOKEN_INVALID && !retried) {
                c = refresh(c.accessToken); retried = true; continue
            }
            if (code != 0) throw GuangyaApiException(code, errorText(code, o.str("msg")))
            return o
        }
    }

    // ---------------- 读 ----------------

    override suspend fun list(folderId: String): List<CloudEntry> {
        val out = ArrayList<CloudEntry>()
        val seen = HashSet<String>()
        var page = 0
        var prevSig: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            if (page >= maxPages) {
                NetLog.error(TAG, "list parent=$folderId 超过 $maxPages 页，停止")
                throw GuangyaApiException(-5, "光鸭目录分页异常：超过 $maxPages 页仍未结束，已停止（目录可能过大或接口分页不生效）")
            }
            val o = api("/v1/file/get_file_list", buildJsonObject {
                put("parentId", folderId)
                put("page", page)
                put("pageSize", PAGE_SIZE)
                put("orderBy", 0)
                put("sortType", 0)
            }, idempotent = true)
            val data = o["data"] as? JsonObject
            if (data == null) {
                NetLog.note(TAG, "list parent=$folderId page=$page data=null")
                break
            }
            val items = (data["list"] as? JsonArray).orEmpty()
            var added = 0
            val ids = ArrayList<String>(items.size)
            items.forEach { el ->
                val it = el as? JsonObject ?: return@forEach
                val id = it.str("fileId") ?: return@forEach
                ids += id
                if (!seen.add(id)) return@forEach
                added++
                out += CloudEntry(
                    fileId = id,
                    fileName = it.str("fileName").orEmpty(),
                    isDir = it.long("resType") == 2L,
                    size = it.long("fileSize") ?: 0,
                    parentFileId = it.str("parentId") ?: folderId,
                )
            }
            val total = data.long("total") ?: 0
            NetLog.note(TAG, "list parent=$folderId page=$page items=${items.size} new=$added total=$total")
            page++
            if (items.isEmpty() || items.size < PAGE_SIZE || (total > 0 && out.size >= total)) break
            // 循环检测：本页与上一页完全相同，或没有任何新条目 → 接口没在翻页，停止而不是死循环
            val sig = ids.joinToString(",")
            if (sig == prevSig || added == 0) {
                NetLog.error(TAG, "list parent=$folderId page=${page - 1} 与上一页重复，停止翻页")
                break
            }
            prevSig = sig
        }
        return out
    }

    override suspend fun ping(): Boolean = runCatching { list(rootId); true }.getOrDefault(false)

    // ---------------- 写 ----------------

    override suspend fun rename(fileId: String, newName: String): Result<String> = runCatching {
        api("/v1/file/rename", buildJsonObject { put("fileId", fileId); put("newName", newName) })
        fileId
    }

    override suspend fun mkdir(parentId: String, name: String): Result<String> = runCatching {
        list(parentId).firstOrNull { it.isDir && it.fileName.equals(name, true) }?.let { return@runCatching it.fileId }
        val o = try {
            api("/v1/file/create_dir", buildJsonObject {
                put("parentId", parentId); put("dirName", name); put("failIfNameExist", false)
            })
        } catch (e: GuangyaApiException) {
            // 并发 / 竞态下已存在：再查一次
            list(parentId).firstOrNull { it.isDir && it.fileName.equals(name, true) }?.let { return@runCatching it.fileId }
            throw e
        }
        (o["data"] as? JsonObject)?.str("fileId")?.takeIf { it.isNotBlank() }
            ?: list(parentId).firstOrNull { it.isDir && it.fileName == name }?.fileId
            ?: throw GuangyaApiException(-1, "光鸭新建文件夹「$name」后没有拿到文件夹 ID")
    }

    override suspend fun move(fileId: String, destParentId: String): Result<String> = runCatching {
        val o = api("/v1/file/move_file", buildJsonObject {
            put("fileIds", buildJsonArray { add(JsonPrimitive(fileId)) })
            put("parentId", destParentId)
        })
        val taskId = (o["data"] as? JsonObject)?.str("taskId")
        if (!taskId.isNullOrBlank()) waitTask(taskId, "移动")
        fileId
    }

    internal suspend fun waitTask(taskId: String, what: String) {
        repeat(maxPolls) { i ->
            if (i > 0 || pollIntervalMs > 0) delay(pollIntervalMs)
            val o = api("/v1/get_task_status", buildJsonObject { put("taskId", taskId) }, idempotent = true)
            when ((o["data"] as? JsonObject)?.long("status")) {
                2L -> return
                3L, -1L -> throw GuangyaApiException(-2, "光鸭后台${what}任务失败")
            }
        }
        throw GuangyaApiException(-3, "光鸭后台${what}任务超时（仍在处理中，可稍后在网页端确认）")
    }

    override suspend fun upload(parentId: String, name: String, data: ByteArray): Result<String> = runCatching {
        val md5 = MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }
        val o = api("/v2/get_res_center_token", buildJsonObject {
            put("capacity", 2)
            put("name", name)
            put("parentId", parentId)
            put("res", buildJsonObject { put("fileSize", data.size.toLong()); put("md5", md5) })
        }, allow = setOf(CODE_UPLOAD_DONE))
        val d = o["data"] as? JsonObject ?: throw GuangyaApiException(-1, "光鸭上传凭证为空")
        val taskId = d.str("taskId") ?: throw GuangyaApiException(-1, "光鸭上传凭证缺少 taskId")
        val code = o.long("code")?.toInt() ?: 0
        val msg = o.str("msg").orEmpty()
        if (code == CODE_UPLOAD_DONE || msg.contains("秒传") || msg.contains("上传已完成")) {
            waitUploaded(taskId)
            return@runCatching "秒传"
        }
        // 诊断：只记录字段名（不含任何值），便于判断服务器是否已替我们发起了分片上传
        NetLog.line(TAG, "上传凭证字段：" + d.keys.sorted().joinToString(",") + " · creds=" +
            ((d["creds"] as? JsonObject)?.keys?.sorted()?.joinToString(",") ?: "无"))
        val creds = d["creds"] as? JsonObject ?: throw GuangyaApiException(-1, "光鸭上传凭证缺少 STS")
        val oss = OssTarget(
            bucket = d.str("bucketName").orEmpty(),
            endpoint = d.str("endPoint").orEmpty(),
            objectPath = d.str("objectPath").orEmpty(),
            ak = creds.str("accessKeyID").orEmpty(),
            sk = creds.str("secretAccessKey").orEmpty(),
            sts = creds.str("sessionToken").orEmpty(),
        )
        if (oss.bucket.isBlank() || oss.objectPath.isBlank() || oss.ak.isBlank()) {
            throw GuangyaApiException(-1, "光鸭上传凭证不完整")
        }
        ossMultipartUpload(oss, data)
        waitUploaded(taskId)
        "ok"
    }

    private suspend fun waitUploaded(taskId: String) {
        repeat(maxPolls) { i ->
            if (i > 0 || pollIntervalMs > 0) delay(pollIntervalMs)
            val o = try {
                api("/v1/file/get_info_by_task_id", buildJsonObject { put("taskId", taskId) })
            } catch (e: GuangyaApiException) {
                if (e.code in UPLOAD_PROCESSING) null else throw e
            }
            val fid = (o?.get("data") as? JsonObject)?.str("fileId")
            if (!fid.isNullOrBlank()) return
        }
        throw GuangyaApiException(-3, "光鸭上传后处理超时（文件可能稍后出现）")
    }

    // ---------------- 阿里云 OSS ----------------

    internal data class OssTarget(
        val bucket: String, val endpoint: String, val objectPath: String,
        val ak: String, val sk: String, val sts: String,
    )

    internal fun ossBase(t: OssTarget): String {
        ossUrlOverride?.let { return it.trimEnd('/') }
        var ep = t.endpoint.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (!ep.startsWith(t.bucket + ".")) ep = t.bucket + "." + ep
        return "https://$ep"
    }

    private suspend fun ossMultipartUpload(t: OssTarget, data: ByteArray) {
        val base = ossBase(t)
        val key = t.objectPath.trimStart('/')
        val encKey = key.split('/').joinToString("/") { seg ->
            java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
        // 1. Initiate
        val initXml = ossCall(t, "POST", "$base/$encKey?uploads", "/${t.bucket}/$key?uploads", ByteArray(0), "")
        val uploadId = Regex("<UploadId>([^<]+)</UploadId>").find(initXml)?.groupValues?.get(1)
            ?: throw GuangyaApiException(-4, "光鸭存储初始化上传失败")
        // UploadId 可能含 + / =（非阿里云原生的十六进制 ID），放进 URL 前必须编码，否则 + 会被当成空格 → NoSuchUpload。
        // 签名用的 CanonicalizedResource 仍用原值（OSS V1 规则）。
        val encId = java.net.URLEncoder.encode(uploadId, "UTF-8")
        // 2. 单个分片（海报 / NFO 都很小）
        val partUrl = "$base/$encKey?partNumber=1&uploadId=$encId"
        val etag = ossCall(t, "PUT", partUrl, "/${t.bucket}/$key?partNumber=1&uploadId=$uploadId", data, "", wantEtag = true)
        // 3. Complete
        val xml = "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>$etag</ETag></Part></CompleteMultipartUpload>"
        ossCall(t, "POST", "$base/$encKey?uploadId=$encId", "/${t.bucket}/$key?uploadId=$uploadId",
            xml.toByteArray(), "application/xml")
    }

    private suspend fun ossCall(
        t: OssTarget, method: String, url: String, resource: String, body: ByteArray,
        contentType: String, wantEtag: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val date = httpDate()
        val ossHeaders = sortedMapOf("x-oss-security-token" to t.sts).filterValues { it.isNotBlank() }
        val sig = ossSignV1(t.sk, method, "", contentType, date, ossHeaders, resource)
        val rb = Request.Builder().url(url.toHttpUrl())
            .header("Date", date)
            .header("Authorization", "OSS ${t.ak}:$sig")
        ossHeaders.forEach { (k, v) -> rb.header(k, v) }
        val mt = if (contentType.isBlank()) null else contentType.toMediaType()
        rb.method(method, body.toRequestBody(mt))
        val r = http.fetch(rb.build())
        val text = r.text
        if (r.code !in 200..299) {
            val c = Regex("<Code>([^<]+)</Code>").find(text)?.groupValues?.get(1) ?: "HTTP ${r.code}"
            throw GuangyaApiException(-4, "光鸭存储上传失败：$c")
        }
        if (wantEtag) r.header("ETag") ?: throw GuangyaApiException(-4, "光鸭存储没有返回 ETag") else text
    }

    companion object {
        const val TAG = "光鸭"
        const val API_BASE = "https://api.guangyapan.com"
        const val ACCOUNT_BASE = "https://account.guangyapan.com"
        const val CLIENT_ID = "aMe-8VSlkrbQXpUR"
        const val WEB_URL = "https://www.guangyapan.com/"
        /** 登录验证时列的目录（根目录）。 */
        const val ROOT_ID_FOR_VALIDATE = ""
        const val CRED_KEY_PREFIX = "credentials_$CLIENT_ID"
        const val DEVICE_KEY = "swangpan_web_device_id"
        const val PAGE_SIZE = 100
        const val CODE_TOKEN_INVALID = 117
        const val CODE_UPLOAD_DONE = 156
        val UPLOAD_PROCESSING = setOf(145, 146, 147, 155, 163)
        private val DEFAULT_DEVICE = "0".repeat(32)

        private val jsonStatic = Json { ignoreUnknownKeys = true; isLenient = true }

        fun from(acct: DriveAccount): GuangyaDrive = GuangyaDrive(acct.id, AccountTokenStore(acct.id))

        fun accountHeaders(deviceId: String): Map<String, String> = linkedMapOf(
            "X-Client-Id" to CLIENT_ID,
            "X-Client-Version" to "0.0.1",
            "X-Device-Id" to deviceId,
            "X-Device-Model" to "chrome%2F147.0.0.0",
            "X-Device-Name" to "PC-Chrome",
            "X-Device-Sign" to "wdi10.$deviceId",
            "X-Net-Work-Type" to "NONE",
            "X-OS-Version" to "MacIntel",
            "X-Platform-Version" to "1",
            "X-Protocol-Version" to "301",
            "X-Provider-Name" to "NONE",
            "X-SDK-Version" to "9.0.2",
        )

        /** 业务码 → 中文提示（码表来自光鸭开放平台文档 + OpenList）。 */
        fun errorText(code: Int, msg: String?): String {
            val base = when (code) {
                CODE_TOKEN_INVALID -> "光鸭登录已失效，请重新登录"
                111, 123 -> "光鸭拒绝访问（无权限）"
                112 -> "光鸭请求参数不合法"
                145 -> "光鸭任务不存在"
                146 -> "光鸭文件不存在或已被删除"
                149 -> "文件已在回收站"
                152 -> "光鸭上传任务不存在"
                163 -> "光鸭上传任务已过期"
                430 -> "该操作需要光鸭会员"
                else -> "光鸭云盘返回错误"
            }
            val m = msg?.trim().orEmpty()
            return if (m.isEmpty() || m.equals("success", true)) "$base（code $code）" else "$base：$m（code $code）"
        }

        /** 阿里云 OSS V1 签名。 */
        fun ossSignV1(
            secret: String, verb: String, contentMd5: String, contentType: String, date: String,
            ossHeaders: Map<String, String>, resource: String,
        ): String {
            val canonHeaders = ossHeaders.entries
                .map { it.key.lowercase() to it.value.trim() }
                .sortedBy { it.first }
                .joinToString("") { "${it.first}:${it.second}\n" }
            val sts = "$verb\n$contentMd5\n$contentType\n$date\n$canonHeaders$resource"
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA1")) }
            return Base64.getEncoder().encodeToString(mac.doFinal(sts.toByteArray()))
        }

        fun httpDate(): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }.format(java.util.Date())

        fun randomDeviceId(): String {
            val b = ByteArray(16); SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        private fun traceparent(): String {
            val r = SecureRandom()
            fun hex(n: Int) = ByteArray(n).also { r.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            return "00-${hex(16)}-${hex(8)}-01"
        }

        /**
         * 解析网页 localStorage 里的凭据 JSON（@xbase/sdk 格式：access_token / refresh_token /
         * expires_in / expires_at(ISO 字符串) / sub）。返回 null 表示还没登录。
         */
        fun parseWebCredentials(raw: String?, deviceId: String?): GyCreds? {
            if (raw.isNullOrBlank()) return null
            val o = runCatching { jsonStatic.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
            val at = o.str("access_token")?.takeIf { it.isNotBlank() } ?: return null
            val expAt = o.str("expires_at")?.let { parseIsoMillis(it) } ?: 0L
            return GyCreds(
                accessToken = at,
                refreshToken = o.str("refresh_token").orEmpty(),
                expiresAt = expAt,
                deviceId = deviceId?.trim()?.takeIf { it.isNotBlank() && it.length <= 64 } ?: randomDeviceId(),
                sub = o.str("sub").orEmpty(),
            )
        }

        private fun parseIsoMillis(s: String): Long? = runCatching {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            f.parse(s)?.time
        }.getOrNull() ?: s.toLongOrNull()

        fun encodeCreds(c: GyCreds): String = jsonStatic.encodeToString(GyCreds.serializer(), c)
        fun decodeCreds(s: String?): GyCreds? =
            s?.let { runCatching { jsonStatic.decodeFromString(GyCreds.serializer(), it) }.getOrNull() }
    }
}

/** 默认凭据存储：AccountStore 的加密账号密钥。 */
class AccountTokenStore(private val accountId: String) : GyTokenStore {
    override fun load(): GyCreds? = GuangyaDrive.decodeCreds(AccountStore.secretOf(accountId))
    override fun save(c: GyCreds) = AccountStore.saveSecret(accountId, GuangyaDrive.encodeCreds(c))
}

private fun JsonObject.str(k: String): String? = when (val v = this[k]) {
    null, is JsonNull -> null
    is JsonPrimitive -> v.contentOrNull
    else -> v.toString()
}

private fun JsonObject.long(k: String): Long? = when (val v = this[k]) {
    is JsonPrimitive -> v.longOrNull ?: v.contentOrNull?.toDoubleOrNull()?.toLong()
    else -> null
}

@Suppress("unused")
private fun JsonElement.asObj(): JsonObject? = this as? JsonObject
