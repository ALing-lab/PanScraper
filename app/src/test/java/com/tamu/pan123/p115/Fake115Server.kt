package com.tamu.pan123.p115

import com.tamu.pan123.data.p115.Drive115
import com.tamu.pan123.data.p115.P115Creds
import com.tamu.pan123.data.p115.P115TokenStore
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.UUID

/** 有状态假 115 服务器。响应结构按 115driver types，非真实抓包。 */
class Fake115Server {
    data class Node(var id: String, var name: String, var parent: String, val dir: Boolean, var size: Long = 0, var sha: String = "")

    val server = MockWebServer()
    val nodes = LinkedHashMap<String, Node>()
    val calls: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())
    val bodies: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private var nextId = 1000L

    var validUid = "UID1"; var validCid = "CID1"; var validSeid = "SEID1"; var validKid = "KID1"
    var userId = 12345L; var userKey = "USERKEY_TEST_32CHARS____________"
    var qrStatus = 0 // 0 waiting, 1 scanned, 2 allowed, -1 expired
    var qrUid = "qr-uid-001"
    var forceErrno: Int? = null
    var forceCid: String? = null
    var instantUploadSha: String? = null // 若匹配 quickId → status 2
    var confirmUploads = true
    var needSignCheck = false
    var signCheckDone = false
    val ossPuts: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
    val ossAuth: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var pendingUpload: Triple<String, String, String>? = null // parent, name, sha

    fun dir(id: String, name: String, parent: String = "0") { nodes[id] = Node(id, name, parent, true) }
    fun file(id: String, name: String, parent: String, size: Long = 1, sha: String = "") {
        nodes[id] = Node(id, name, parent, false, size, sha)
    }
    fun childrenOf(p: String) = nodes.values.filter { it.parent == p }

    fun start(): Fake115Server { server.dispatcher = dispatcher; server.start(); return this }
    fun shutdown() = server.shutdown()
    val base get() = server.url("/").toString().trimEnd('/')

    fun drive(store: P115TokenStore): Drive115 = Drive115(
        accountId = "p115-test", store = store,
        apiBase = "$base/web", qrBase = "$base/qr", passportBase = "$base/passport",
        uploadBase = "$base/up", proApiBase = "$base/pro",
        pathIntervalMs = 0, ossUrlOverride = "$base/oss", statusUrl = "$base/my/", skipEcdh = true,
    )

    private fun ok(data: Any? = null, extra: JSONObject.() -> Unit = {}): MockResponse {
        val o = JSONObject().put("state", true).put("errno", 0)
        if (data != null) o.put("data", data)
        o.extra()
        return json(o)
    }
    private fun err(code: Int, msg: String) = json(JSONObject().put("state", false).put("errno", code).put("error", msg))
    private fun json(o: JSONObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(o.toString())

    private fun cookieOk(req: RecordedRequest): Boolean {
        val c = req.getHeader("Cookie").orEmpty()
        return c.contains("UID=$validUid") && c.contains("CID=$validCid") && c.contains("SEID=$validSeid")
    }

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@Fake115Server) {
            calls += request
            val path = request.path.orEmpty().substringBefore('?')
            val body = request.body.readUtf8()
            bodies += path to body
            val q = request.requestUrl!!

            if (path.startsWith("/oss/")) {
                ossAuth += request.getHeader("Authorization").orEmpty()
                ossPuts += body.toByteArray()
                if (confirmUploads) pendingUpload?.let { (parent, name, sha) -> file((nextId++).toString(), name, parent, body.toByteArray().size.toLong(), sha) }
                return MockResponse().setResponseCode(200).setBody("{\"Status\":\"OK\"}")
            }

            when {
                path == "/qr/api/1.0/web/1.0/token" -> {
                    qrStatus = 0; qrUid = "qr-" + UUID.randomUUID().toString().take(8)
                    json(JSONObject().put("state", 1).put("code", 0).put("data", JSONObject()
                        .put("uid", qrUid).put("time", System.currentTimeMillis() / 1000)
                        .put("sign", "sign-$qrUid").put("qrcode", "https://115.com/scan?uid=$qrUid")))
                }
                path == "/qr/get/status/" -> {
                    json(JSONObject().put("state", 1).put("data", JSONObject()
                        .put("status", qrStatus).put("msg", when (qrStatus) {
                            0 -> "等待"; 1 -> "已扫码"; 2 -> "已确认"; -1 -> "过期"; else -> ""
                        }).put("version", "1")))
                }
                path.matches(Regex("/passport/app/1.0/.+/1.0/login/qrcode")) -> {
                    if (qrStatus != 2) return json(JSONObject().put("state", 0).put("code", 40199002).put("message", "qr expired"))
                    val app = path.split("/")[4]
                    json(JSONObject().put("state", 1).put("data", JSONObject()
                        .put("user_id", userId).put("user_name", "测试用户")
                        .put("cookie", JSONObject().put("UID", validUid).put("CID", validCid)
                            .put("SEID", validSeid).put("KID", validKid))
                        .put("from", app)))
                }
                path == "/web/files" -> {
                    if (!cookieOk(request)) return err(99, "not login")
                    forceErrno?.let { return err(it, "forced") }
                    val cid = q.queryParameter("cid") ?: "0"
                    val offset = q.queryParameter("offset")?.toIntOrNull() ?: 0
                    val limit = q.queryParameter("limit")?.toIntOrNull() ?: 56
                    val all = childrenOf(cid).sortedBy { it.name }
                    val slice = all.drop(offset).take(limit)
                    val arr = JSONArray()
                    slice.forEach {
                        val o = JSONObject().put("n", it.name).put("s", it.size).put("sha", it.sha).put("pc", "pc${it.id}")
                            .put("tp", "1700000000").put("t", if (it.dir) "1700000000" else "2024-01-01 12:00")
                        if (it.dir) o.put("cid", it.id.toLongOrNull() ?: it.id).put("pid", it.parent)
                        else o.put("fid", it.id).put("cid", it.parent.toLongOrNull() ?: it.parent)
                        arr.put(o)
                    }
                    ok().also { /* rebuild */ }
                    val rc = forceCid ?: cid
                    json(JSONObject().put("state", true).put("errno", 0).put("cid", rc.toLongOrNull() ?: rc)
                        .put("count", all.size).put("offset", offset).put("limit", limit).put("data", arr))
                }
                path == "/web/files/batch_rename" -> {
                    if (!cookieOk(request)) return err(99, "not login")
                    forceErrno?.let { return err(it, "forced") }
                    val form = parseForm(body)
                    val fid = form["fid"] ?: return err(1001, "no fid")
                    val name = form["file_name"] ?: form.entries.firstOrNull { it.key.startsWith("files_new_name") }?.value
                        ?: return err(1001, "no name")
                    val n = nodes[fid] ?: return err(70005, "not found")
                    if (childrenOf(n.parent).any { it.name == name && it.id != fid }) return err(20004, "exist")
                    n.name = name; ok()
                }
                path == "/web/files/add" -> {
                    if (!cookieOk(request)) return err(99, "not login")
                    val form = parseForm(body)
                    val pid = form["pid"] ?: "0"
                    val name = form["cname"] ?: return err(1001, "no name")
                    if (childrenOf(pid).any { it.name == name && it.dir }) return err(20004, "exist")
                    val id = "${nextId++}"
                    dir(id, name, pid)
                    json(JSONObject().put("state", true).put("errno", 0).put("cid", id.toLong()).put("cname", name))
                }
                path == "/web/files/move" -> {
                    if (!cookieOk(request)) return err(99, "not login")
                    forceErrno?.let { return err(it, "forced") }
                    val form = parseForm(body)
                    val dest = form["pid"] ?: "0"
                    form.filterKeys { it.startsWith("fid") }.values.forEach { id ->
                        nodes[id]?.parent = dest
                    }
                    ok()
                }
                path == "/pro/app/uploadinfo" -> {
                    if (!cookieOk(request)) return err(99, "not login")
                    json(JSONObject().put("state", true).put("user_id", userId).put("userkey", userKey)
                        .put("size_limit", 5L * 1024 * 1024 * 1024).put("upload_allowed", true))
                }
                path == "/up/3.0/gettoken.php" -> {
                    if (!cookieOk(request)) return err(99, "not login")
                    json(JSONObject().put("StatusCode", "200")
                        .put("AccessKeyID", "STS.AK115").put("AccessKeySecret", "SK115")
                        .put("SecurityToken", "TOK115").put("Expiration", "2099-01-01T00:00:00Z"))
                }
                path == "/up/4.0/initupload.php" -> {
                    if (!cookieOk(request)) return err(99, "not login")
                    val form = parseForm(body)
                    val fileId = form["fileid"].orEmpty()
                    pendingUpload = Triple(form["target"].orEmpty().removePrefix("U_1_"), form["filename"].orEmpty(), fileId)
                    if (needSignCheck && !signCheckDone) {
                        if (form["sign_key"].isNullOrBlank()) {
                            return MockResponse().setBody(JSONObject()
                                .put("statuscode", 0).put("status", 7)
                                .put("sign_key", "sk1").put("sign_check", "0-10").toString())
                        }
                        signCheckDone = true
                    }
                    if (instantUploadSha != null && fileId.equals(instantUploadSha, true)) {
                        return MockResponse().setBody(JSONObject().put("statuscode", 0).put("status", 2)
                            .put("pickcode", "pc-fast").toString())
                    }
                    MockResponse().setBody(JSONObject().put("statuscode", 0).put("status", 1)
                        .put("bucket", "115-bucket").put("object", "u/2026/$fileId")
                        .put("callback", JSONObject().put("callback", "{\"cb\":\"1\"}").put("callback_var", "{\"v\":\"1\"}"))
                        .toString())
                }
                path == "/my/" || path.startsWith("/my") -> json(JSONObject().put("state", true))
                else -> MockResponse().setResponseCode(404).setBody("no $path")
            }
        }
    }

    private fun parseForm(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        return body.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else {
                val k = java.net.URLDecoder.decode(it.substring(0, i), "UTF-8")
                val v = java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
                k to v
            }
        }.toMap()
    }
}

class MemP115Store(var creds: P115Creds?) : P115TokenStore {
    var saves = 0
    override fun load() = creds
    override fun save(c: P115Creds) { creds = c; saves++ }
}
