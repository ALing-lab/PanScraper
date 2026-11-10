package com.tamu.pan123.data.webdav

import com.tamu.pan123.data.drive.AccountStore
import com.tamu.pan123.data.drive.CloudDrive
import com.tamu.pan123.data.drive.CloudEntry
import com.tamu.pan123.data.drive.DriveAccount
import com.tamu.pan123.data.drive.DrivePaths
import com.tamu.pan123.data.drive.ProviderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import java.io.IOException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDAV 实现（RFC 4918）。
 * - ID = 相对于服务器地址的路径，以 "/" 开头，未编码（如 "/电影/盗梦空间 (2010)"）。
 * - 列目录 PROPFIND Depth:1；改名 / 移动 MOVE（Overwrite: F，绝不覆盖）；建目录 MKCOL；上传 PUT（If-None-Match: *）。
 * - URL 逐段百分号编码；目录请求统一带尾斜杠；href 解码兼容绝对 URL / 绝对路径 / 双重斜杠。
 * - 认证：Basic。可选「信任自签名证书」（仅对该账号生效）：证书指纹固定，只信任用户确认过的那一张，见 [CertPinning]。
 */
class WebDavDrive(
    override val accountId: String,
    baseUrl: String,
    private val user: String,
    private val password: String,
    private val insecure: Boolean = false,
    /** 用户确认信任的服务器证书 SHA-256 指纹（只在 insecure 时使用）。 */
    private val pinnedSha256: String? = null,
) : CloudDrive {
    override val kind = ProviderKind.WEBDAV
    override val writeIntervalMs = 100L

    private val base: HttpUrl = normalizeBase(baseUrl)
    /** 基础路径的解码段，用来把 href 还原成相对 ID。 */
    private val baseSegs: List<String> = base.pathSegments.filter { it.isNotEmpty() }

    private val http: OkHttpClient = buildClient(insecure, pinnedSha256)

    // ---------------- URL 工具 ----------------

    internal fun urlFor(path: String, dir: Boolean): HttpUrl {
        val b = base.newBuilder()
        // 去掉 base 的尾部空段，避免出现 "//"
        while (b.build().pathSegments.lastOrNull() == "" && b.build().pathSegments.size > 1) {
            b.removePathSegment(b.build().pathSegments.size - 1)
        }
        val segs = path.split('/').filter { it.isNotEmpty() }
        if (b.build().pathSegments == listOf("")) {
            // base 是根 "/"：第一段直接覆盖空段
            if (segs.isNotEmpty()) b.setPathSegment(0, segs.first())
            segs.drop(1).forEach { b.addPathSegment(it) }
        } else {
            segs.forEach { b.addPathSegment(it) }
        }
        if (dir) b.addPathSegment("")
        return b.build()
    }

    /** 把服务器返回的 href 转为 ID（相对路径，已解码）。不在 base 下的返回 null。 */
    internal fun idFromHref(href: String): String? {
        val raw = href.trim()
        val pathPart = when {
            raw.startsWith("http://", true) || raw.startsWith("https://", true) ->
                raw.toHttpUrl().encodedPath
            else -> raw.substringBefore('?')
        }
        val segs = pathPart.split('/').filter { it.isNotEmpty() }.map { decodeSeg(it) }
        if (segs.size < baseSegs.size || segs.subList(0, baseSegs.size) != baseSegs) return null
        val rel = segs.drop(baseSegs.size)
        return "/" + rel.joinToString("/")
    }

    private fun decodeSeg(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    // ---------------- HTTP ----------------

    private fun req(url: HttpUrl): Request.Builder {
        val b = Request.Builder().url(url).header("User-Agent", "123Scraper/0.2 WebDAV")
        if (user.isNotEmpty() || password.isNotEmpty()) b.header("Authorization", Credentials.basic(user, password, Charsets.UTF_8))
        return b
    }

    private fun call(r: Request): Response = http.newCall(r).execute()

    private fun fail(op: String, resp: Response): Nothing {
        val hint = when (resp.code) {
            401 -> "用户名或密码不对（或服务器要求 Digest 认证）"
            403 -> "没有权限"
            404 -> "路径不存在"
            405 -> "服务器不允许该操作"
            409 -> "上级目录不存在"
            412 -> "目标已存在（不会覆盖）"
            423 -> "文件被锁定"
            507 -> "服务器空间不足"
            else -> "HTTP ${resp.code}"
        }
        throw IOException("WebDAV $op 失败：$hint")
    }

    private val propBody = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getetag/><d:displayname/></d:prop></d:propfind>"""
        .toRequestBody("application/xml; charset=utf-8".toMediaType())

    private data class Prop(val id: String, val isDir: Boolean, val size: Long, val etag: String?)

    private fun propfind(path: String, depth: Int, asDir: Boolean): Pair<Int, List<Prop>> {
        val r = req(urlFor(path, asDir)).method("PROPFIND", propBody).header("Depth", depth.toString()).build()
        call(r).use { resp ->
            // 部分服务器对不带尾斜杠的目录返回 301/302：改用目录形式重试
            if (resp.code in listOf(301, 302, 307, 308) && !asDir) return propfind(path, depth, asDir = true)
            if (resp.code == 404) return 404 to emptyList()
            if (resp.code != 207 && resp.code != 200) fail("列目录", resp)
            return resp.code to parseMultistatus(resp.body?.string().orEmpty())
        }
    }

    private fun parseMultistatus(xml: String): List<Prop> {
        if (xml.isBlank()) return emptyList()
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { isExpandEntityReferences = false }
        }
        val doc = f.newDocumentBuilder().parse(xml.byteInputStream())
        val out = mutableListOf<Prop>()
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        for (i in 0 until responses.length) {
            val r = responses.item(i) as Element
            val href = r.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: continue
            val id = idFromHref(href) ?: continue
            // 只取 2xx 的 propstat
            var isDir = false
            var size = 0L
            var etag: String? = null
            val stats = r.getElementsByTagNameNS("DAV:", "propstat")
            for (j in 0 until stats.length) {
                val st = stats.item(j) as Element
                val status = st.getElementsByTagNameNS("DAV:", "status").item(0)?.textContent.orEmpty()
                if (status.isNotEmpty() && !status.contains(" 2")) continue
                if (st.getElementsByTagNameNS("DAV:", "collection").length > 0) isDir = true
                st.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.trim()?.toLongOrNull()?.let { size = it }
                st.getElementsByTagNameNS("DAV:", "getetag").item(0)?.textContent?.trim()?.let { etag = it.trim('"') }
            }
            if (href.trim().endsWith("/")) isDir = true
            out += Prop(id, isDir, size, etag)
        }
        return out
    }

    // ---------------- CloudDrive ----------------

    override suspend fun list(folderId: String): List<CloudEntry> = withContext(Dispatchers.IO) {
        val folder = norm(folderId)
        val (code, props) = propfind(folder, 1, asDir = true)
        if (code == 404) throw IOException("WebDAV 列目录失败：路径不存在 $folder")
        props.filter { norm(it.id) != folder }
            .map {
                val id = norm(it.id)
                CloudEntry(id, DrivePaths.nameOf(id), it.isDir, it.size, folder, it.etag)
            }
            .distinctBy { it.fileId }
            .sortedWith(compareBy({ !it.isDir }, { it.fileName.lowercase() }))
    }

    private fun norm(p: String): String {
        val s = "/" + p.split('/').filter { it.isNotEmpty() }.joinToString("/")
        return s
    }

    /** 查单个路径：null = 不存在；true/false = 是否目录。 */
    private fun stat(path: String): Boolean? {
        val (code, props) = propfind(path, 0, asDir = false)
        if (code == 404) return null
        return props.firstOrNull()?.isDir ?: false
    }

    private fun moveTo(from: String, to: String): String {
        val isDir = stat(from) ?: throw IOException("WebDAV 移动失败：源不存在 $from")
        if (stat(to) != null) throw IOException("WebDAV 移动失败：目标已存在（不会覆盖）${DrivePaths.nameOf(to)}")
        val r = req(urlFor(from, isDir))
            .method("MOVE", null)
            .header("Destination", urlFor(to, isDir).toString())
            .header("Overwrite", "F")
            .build()
        call(r).use { resp -> if (resp.code !in listOf(201, 204)) fail("移动/改名", resp) }
        return to
    }

    override suspend fun rename(fileId: String, newName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(newName.isNotBlank() && '/' !in newName) { "名称不合法：$newName" }
            val from = norm(fileId)
            val to = DrivePaths.join(DrivePaths.parentOf(from), newName)
            if (to == from) from else moveTo(from, to)
        }
    }

    override suspend fun move(fileId: String, destParentId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val from = norm(fileId)
            val dest = norm(destParentId)
            if (stat(dest) != true) throw IOException("WebDAV 移动失败：目标文件夹不存在 $dest")
            val to = DrivePaths.join(dest, DrivePaths.nameOf(from))
            if (to == from) from else moveTo(from, to)
        }
    }

    override val trashSupport = com.tamu.pan123.data.drive.TrashSupport.PERMANENT

    /** WebDAV DELETE（没有回收站）。执行引擎会先确认文件夹里没有视频才调用。 */
    override suspend fun trash(fileId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val path = norm(fileId)
            require(path != "/" && path.isNotBlank()) { "不能删除根目录" }
            val isDir = stat(path) ?: return@runCatching "已不存在"
            call(req(urlFor(path, dir = isDir)).delete().build()).use { resp ->
                when (resp.code) {
                    200, 202, 204, 404 -> {}
                    else -> fail("删除", resp)
                }
            }
            "已删除"
        }
    }

    override suspend fun mkdir(parentId: String, name: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(name.isNotBlank() && '/' !in name) { "名称不合法：$name" }
            val path = DrivePaths.join(norm(parentId), name)
            when (stat(path)) {
                true -> return@runCatching path // 已存在的文件夹直接复用
                false -> throw IOException("WebDAV 建文件夹失败：同名文件已存在 $name")
                null -> {}
            }
            call(req(urlFor(path, dir = true)).method("MKCOL", null).build()).use { resp ->
                when (resp.code) {
                    201, 200, 204 -> {}
                    405 -> if (stat(path) != true) fail("建文件夹", resp)
                    else -> fail("建文件夹", resp)
                }
            }
            path
        }
    }

    override suspend fun upload(parentId: String, name: String, data: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(name.isNotBlank() && '/' !in name) { "名称不合法：$name" }
            val path = DrivePaths.join(norm(parentId), name)
            if (stat(path) != null) return@runCatching "已存在"
            val type = when (name.substringAfterLast('.').lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "nfo", "xml" -> "text/xml; charset=utf-8"
                else -> "application/octet-stream"
            }
            val r = req(urlFor(path, dir = false))
                .put(data.toRequestBody(type.toMediaType()))
                .header("If-None-Match", "*") // 服务器支持时，保证不覆盖
                .build()
            call(r).use { resp ->
                when (resp.code) {
                    200, 201, 204 -> {}
                    412 -> return@runCatching "已存在"
                    else -> fail("上传", resp)
                }
            }
            "ok"
        }
    }

    override suspend fun exists(parentId: String, name: String): Boolean = withContext(Dispatchers.IO) {
        // 先精确查（快），再按目录列表不区分大小写查
        if (stat(DrivePaths.join(norm(parentId), name)) != null) return@withContext true
        list(parentId).any { it.fileName.equals(name, ignoreCase = true) }
    }

    override suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        runCatching { propfind("/", 0, asDir = true).first in listOf(200, 207) }.getOrDefault(false)
    }

    /** 带错误信息的连通性测试（给“测试连接”按钮用）。 */
    suspend fun check(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val (code, props) = propfind("/", 1, asDir = true)
            if (code == 404) throw IOException("服务器地址的路径不存在")
            (props.size - 1).coerceAtLeast(0)
        }.recoverCatching { e -> throw (CertPinning.findUntrusted(e) ?: e) }
    }

    companion object {
        fun normalizeBase(url: String): HttpUrl {
            var u = url.trim()
            if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "https://$u"
            if (!u.endsWith("/")) u += "/"
            return u.toHttpUrl()
        }

        fun from(acct: DriveAccount): WebDavDrive {
            val secret = AccountStore.secretOf(acct.id)
            val pass = runCatching { org.json.JSONObject(secret ?: "{}").optString("password") }.getOrDefault("")
            return WebDavDrive(
                accountId = acct.id,
                baseUrl = acct.extra["url"].orEmpty(),
                user = acct.extra["user"].orEmpty(),
                password = pass,
                insecure = acct.extra["insecure"] == "1",
                pinnedSha256 = acct.extra["pin"],
            )
        }

        private fun buildClient(insecure: Boolean, pin: String?): OkHttpClient {
            val b = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .addInterceptor(com.tamu.pan123.data.net.NetLog.interceptor("WebDAV"))
                .followRedirects(false) // 不让 MOVE/PUT 被重定向成 GET
            if (insecure) {
                val tm = CertPinning.PinningTrustManager(pin)
                val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }
                b.sslSocketFactory(ctx.socketFactory, tm)
                    .hostnameVerifier(CertPinning.hostnameVerifier(pin, okhttp3.internal.tls.OkHostnameVerifier))
            }
            return b.build()
        }
    }
}
