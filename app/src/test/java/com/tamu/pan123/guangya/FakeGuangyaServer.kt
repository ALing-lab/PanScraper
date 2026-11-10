package com.tamu.pan123.guangya

import com.tamu.pan123.data.guangya.GuangyaDrive
import com.tamu.pan123.data.guangya.GyCreds
import com.tamu.pan123.data.guangya.GyTokenStore
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * 有状态的假光鸭服务器（业务接口 + 账号刷新 + 阿里云 OSS 分片上传）。
 * 响应结构按 OpenList guangyapan 驱动的 types.go 与网页 JS 构造，不是真实抓包。
 */
class FakeGuangyaServer {
    data class Node(var id: String, var name: String, var parent: String, val dir: Boolean, var size: Long = 0)

    val server = MockWebServer()
    val nodes = LinkedHashMap<String, Node>()
    val calls: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    val bodies: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private var nextId = 1000

    /** 当前有效 access_token；其它 token → 401。 */
    var validToken = "AT-1"
    var refreshTokenExpected = "RT-1"
    var refreshCount = 0
    /** OSS 返回的 UploadId（可设成含 + / = 的值，复现「NoSuchUpload」）。 */
    var ossUploadId = "UP1"
    /** 让业务接口返回 code 117（而非 HTTP 401）来表示 token 失效。 */
    var tokenInvalidAsCode = false
    /** move 任务：先返回几次 status=1 再完成；或直接失败。 */
    var movePendingPolls = 1
    var moveFails = false
    /** 上传：秒传(156)。 */
    var instantUpload = false
    /** 上传后 get_info_by_task_id 先返回几次“处理中”(146)。 */
    var uploadProcessingPolls = 1
    /** 只认 /nd.bizuserres.s 前缀（模拟 /userres 404）。 */
    var onlyLegacyPrefix = false
    /** 某 path 直接返回指定业务错误。 */
    val forcedErrors = HashMap<String, Pair<Int, String>>()

    private val tasks = HashMap<String, Int>() // taskId → 剩余 pending 次数
    private val pendingMoves = HashMap<String, Pair<List<String>, String>>()
    private val uploads = HashMap<String, Triple<String, String, Int>>() // taskId → (parent, name, size)
    val ossParts = HashMap<String, ByteArray>()
    val ossAuth: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val ossStsHeaders: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    fun dir(id: String, name: String, parent: String = "") { nodes[id] = Node(id, name, parent, true) }
    fun file(id: String, name: String, parent: String, size: Long = 1) { nodes[id] = Node(id, name, parent, false, size) }

    fun start(): FakeGuangyaServer { server.dispatcher = dispatcher; server.start(); return this }
    fun shutdown() = server.shutdown()
    val base: String get() = server.url("/").toString().trimEnd('/')

    fun drive(store: GyTokenStore, poll: Long = 0): GuangyaDrive = GuangyaDrive(
        accountId = "guangya-test", store = store, apiBase = base, accountBase = "$base/acct",
        pathIntervalMs = 0, pollIntervalMs = poll, maxPolls = 10, ossUrlOverride = "$base/oss",
    )

    fun childrenOf(parent: String) = nodes.values.filter { it.parent == parent }

    private fun ok(data: Any?): MockResponse = json(JSONObject().put("code", 0).put("msg", "success").put("data", data ?: JSONObject.NULL))
    private fun err(code: Int, msg: String) = json(JSONObject().put("code", code).put("msg", msg))
    private fun json(o: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(o.toString())

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@FakeGuangyaServer) {
            calls += request
            val path = request.path.orEmpty()
            val body = request.body.readUtf8()
            bodies += path to body
            if (path.startsWith("/oss/")) return oss(request, path, body)
            if (path == "/acct/v1/auth/token") return refresh(body)
            val p = when {
                path.startsWith("/userres/") -> if (onlyLegacyPrefix) return MockResponse().setResponseCode(404) else path.removePrefix("/userres")
                path.startsWith("/nd.bizuserres.s/") -> path.removePrefix("/nd.bizuserres.s")
                else -> return MockResponse().setResponseCode(404)
            }
            val auth = request.getHeader("Authorization")
            if (auth != "Bearer $validToken") {
                return if (tokenInvalidAsCode) err(117, "token invalid") else MockResponse().setResponseCode(401)
            }
            forcedErrors[p]?.let { return err(it.first, it.second) }
            val b = if (body.isBlank()) JSONObject() else JSONObject(body)
            return when (p) {
                "/v1/file/get_file_list" -> {
                    val all = childrenOf(b.optString("parentId")).sortedBy { it.name }
                    val size = b.optInt("pageSize", 100); val page = b.optInt("page", 0)
                    val slice = all.drop(page * size).take(size)
                    val arr = JSONArray()
                    slice.forEach {
                        arr.put(JSONObject().put("fileId", it.id).put("parentId", it.parent).put("fileName", it.name)
                            .put("fileSize", it.size).put("resType", if (it.dir) 2 else 1).put("ctime", 1700000000).put("utime", 1700000000))
                    }
                    ok(JSONObject().put("total", all.size).put("list", arr))
                }
                "/v1/file/rename" -> {
                    val n = nodes[b.optString("fileId")] ?: return err(146, "file not found")
                    if (childrenOf(n.parent).any { it.name == b.optString("newName") && it.id != n.id }) return err(160, "同名文件已存在")
                    n.name = b.optString("newName"); ok(JSONObject())
                }
                "/v1/file/create_dir" -> {
                    val id = "d${nextId++}"
                    dir(id, b.optString("dirName"), b.optString("parentId")); ok(JSONObject().put("fileId", id))
                }
                "/v1/file/move_file" -> {
                    val ids = b.getJSONArray("fileIds").let { a -> (0 until a.length()).map { a.getString(it) } }
                    val tid = "t${nextId++}"
                    tasks[tid] = movePendingPolls
                    pendingMoves[tid] = ids to b.optString("parentId")
                    ok(JSONObject().put("taskId", tid))
                }
                "/v1/get_task_status" -> {
                    val tid = b.optString("taskId")
                    val left = tasks[tid] ?: return err(145, "task not found")
                    if (moveFails) return ok(JSONObject().put("status", 3))
                    if (left > 0) { tasks[tid] = left - 1; return ok(JSONObject().put("status", 1)) }
                    pendingMoves.remove(tid)?.let { (ids, dest) -> ids.forEach { nodes[it]?.parent = dest } }
                    ok(JSONObject().put("status", 2))
                }
                "/v2/get_res_center_token", "/v1/get_res_center_token" -> {
                    val tid = "u${nextId++}"
                    val res = b.getJSONObject("res")
                    uploads[tid] = Triple(b.optString("parentId"), b.optString("name"), res.optInt("fileSize"))
                    tasks[tid] = uploadProcessingPolls
                    if (instantUpload) {
                        json(JSONObject().put("code", 156).put("msg", "秒传成功").put("data", JSONObject().put("taskId", tid)))
                    } else ok(JSONObject().put("taskId", tid).put("objectPath", "upload/2026/$tid/${b.optString("name")}")
                        .put("bucketName", "gy-bucket").put("endPoint", "oss-cn-hangzhou.aliyuncs.com").put("region", "cn-hangzhou")
                        .put("creds", JSONObject().put("accessKeyID", "STS.AKTEST").put("secretAccessKey", "SKTEST").put("sessionToken", "STSTOKEN")))
                }
                "/v1/file/get_info_by_task_id" -> {
                    val tid = b.optString("taskId")
                    val left = tasks[tid] ?: return err(152, "upload task not found")
                    if (left > 0) { tasks[tid] = left - 1; return err(146, "处理中") }
                    val (parent, name, size) = uploads[tid]!!
                    if (!instantUpload && ossParts.keys.none { it.contains(tid) }) return err(146, "处理中")
                    val id = nodes.values.firstOrNull { it.parent == parent && it.name == name }?.id
                        ?: "f${nextId++}".also { file(it, name, parent, size.toLong()) }
                    ok(JSONObject().put("fileId", id))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun refresh(body: String): MockResponse {
        val b = JSONObject(body)
        if (b.optString("grant_type") != "refresh_token" || b.optString("refresh_token") != refreshTokenExpected) {
            return MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
        }
        refreshCount++
        validToken = "AT-${refreshCount + 1}"
        refreshTokenExpected = "RT-${refreshCount + 1}"
        return json(JSONObject().put("access_token", validToken).put("refresh_token", refreshTokenExpected)
            .put("token_type", "Bearer").put("expires_in", 7200).put("sub", "sub-001"))
    }

    private fun oss(req: RecordedRequest, path: String, body: String): MockResponse {
        ossAuth += req.getHeader("Authorization").orEmpty()
        ossStsHeaders += req.getHeader("x-oss-security-token")
        val url = req.requestUrl!!
        return when {
            req.method == "POST" && url.queryParameterNames.contains("uploads") ->
                MockResponse().setBody("<InitiateMultipartUploadResult><UploadId>$ossUploadId</UploadId></InitiateMultipartUploadResult>")
            url.queryParameter("uploadId") != null && url.queryParameter("uploadId") != ossUploadId ->
                MockResponse().setResponseCode(404).setBody("<Error><Code>NoSuchUpload</Code></Error>")
            req.method == "PUT" && url.queryParameter("partNumber") == "1" -> {
                ossParts[url.encodedPath] = body.toByteArray()
                MockResponse().setHeader("ETag", "\"ETAG1\"")
            }
            req.method == "POST" && url.queryParameter("uploadId") == ossUploadId ->
                MockResponse().setBody("<CompleteMultipartUploadResult><ETag>\"X\"</ETag></CompleteMultipartUploadResult>")
            else -> MockResponse().setResponseCode(400).setBody("<Error><Code>InvalidRequest</Code></Error>")
        }
    }
}

class MemTokenStore(var creds: GyCreds?) : GyTokenStore {
    var saves = 0
    override fun load() = creds
    override fun save(c: GyCreds) { creds = c; saves++ }
}
