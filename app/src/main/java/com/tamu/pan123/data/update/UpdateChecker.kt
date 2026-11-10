package com.tamu.pan123.data.update

import com.tamu.pan123.AppInfo
import com.tamu.pan123.BuildConfigInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

data class ReleaseInfo(
    val version: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val apkName: String?,
    val apkSize: Long,
    /** 发布里附带的 SHA-256（来自更新说明里的 sha256 行）；没有则为 null。 */
    val sha256: String? = null,
    /** 「APK 文件名.sha256」资源的下载地址（下载时再取）。 */
    val sha256Url: String? = null,
)

sealed class UpdateResult {
    data class Available(val release: ReleaseInfo) : UpdateResult()
    data class UpToDate(val latest: String) : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}

/**
 * 检查更新：GitHub Releases latest 接口。大陆访问 GitHub 常常很慢，超时设得短，错误给出友好提示，
 * 并提供「手动更新」（浏览器打开发布页）兜底。
 */
object UpdateChecker {
    /** 只给单元测试替换（MockWebServer）。 */
    @Volatile internal var apiBase: String = "https://api.github.com"
    @Volatile internal var currentVersion: String = BuildConfigInfo.VERSION
    /** 只给单元测试用：放行 MockWebServer 的 http 地址。正式代码里永远是 null。 */
    @Volatile internal var testAllowUrl: ((okhttp3.HttpUrl) -> Boolean)? = null

    /** GitHub 发布资源允许的下载主机（含重定向后的 CDN）。 */
    private val ASSET_HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
    private val REPO_DOWNLOAD_PREFIX = "/${AppInfo.GITHUB_REPO}/releases/download/"

    /** browser_download_url 必须是 https://github.com/ALing-lab/PanScraper/releases/download/...（或 GitHub 的资源 CDN）。 */
    internal fun isTrustedAssetUrl(raw: String?): Boolean {
        val u = raw?.toHttpUrlOrNull() ?: return false
        testAllowUrl?.let { if (it(u)) return true }
        if (!u.isHttps) return false
        val host = u.host.lowercase()
        if (host !in ASSET_HOSTS) return false
        return if (host == "github.com") u.encodedPath.startsWith(REPO_DOWNLOAD_PREFIX) else true
    }

    private fun hopAllowed(u: okhttp3.HttpUrl) = testAllowUrl?.invoke(u) == true || (u.isHttps && u.host.lowercase() in ASSET_HOSTS)

    /** 手动跟随重定向（最多 5 次）：每一跳在连接之前都检查必须是 https + GitHub 的主机。 */
    private fun openTrusted(url: String): okhttp3.Response {
        var u = url.toHttpUrlOrNull() ?: throw IOException("下载地址无效")
        repeat(6) {
            if (!hopAllowed(u)) throw IOException("更新下载被重定向到了非 GitHub 地址（${u.host}），已停止下载")
            val resp = dl.newCall(Request.Builder().url(u).header("User-Agent", "${AppInfo.NAME_EN}/${currentVersion}").build()).execute()
            if (resp.code in 300..399) {
                val loc = resp.header("Location"); resp.close()
                u = loc?.let { u.resolve(it) } ?: throw IOException("下载失败：重定向缺少地址")
            } else return resp
        }
        throw IOException("下载失败：重定向次数太多")
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }
    private val dl by lazy {
        http.newBuilder().readTimeout(30, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    }

    /** 1.2.10 > 1.2.9；忽略前缀 v 和 -beta 之类的后缀。 */
    fun compare(a: String, b: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    fun friendly(e: Throwable): String = when (e) {
        is InterruptedIOException -> "连接 GitHub 超时（大陆网络访问 GitHub 往往较慢）。可以稍后再试，或点「手动更新」在浏览器里打开发布页。"
        is UnknownHostException -> "无法解析 GitHub 地址，请检查网络 / 代理后重试，或点「手动更新」。"
        is SSLException -> "与 GitHub 的安全连接失败（网络可能被干扰），请换个网络或开代理后重试。"
        else -> "检查更新失败：${e.message ?: e.javaClass.simpleName}"
    }

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("${apiBase.trimEnd('/')}/repos/${AppInfo.GITHUB_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "${AppInfo.NAME_EN}/${currentVersion}")
                .build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.code == 404 -> return@withContext UpdateResult.Error("还没有发布任何版本（或仓库尚未公开）。")
                    resp.code == 403 || resp.code == 429 -> return@withContext UpdateResult.Error("GitHub 接口访问次数暂时超限，请过一会儿再试，或点「手动更新」。")
                    !resp.isSuccessful -> return@withContext UpdateResult.Error("GitHub 返回 HTTP ${resp.code}，请稍后再试。")
                }
                val r = parse(body)
                if (compare(r.version, currentVersion) > 0) UpdateResult.Available(r) else UpdateResult.UpToDate(r.version)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            UpdateResult.Error(friendly(e))
        }
    }

    internal fun parse(body: String): ReleaseInfo {
        val o = JSONObject(body)
        val tag = o.optString("tag_name").ifBlank { o.optString("name") }
        val assets = o.optJSONArray("assets")
        val version = tag.trim().removePrefix("v").removePrefix("V")
        // 选 APK 资源：只看已上传完的 .apk；名字里带版本号的优先（如 PanScraper-v0.3.3.apk），否则取第一个
        val all = if (assets == null) emptyList() else (0 until assets.length()).map { assets.getJSONObject(it) }
        // 只接受本仓库发布页里的 https 资源
        val apks = all.filter {
            it.optString("name").endsWith(".apk", ignoreCase = true) && it.optString("state", "uploaded") == "uploaded" &&
                isTrustedAssetUrl(it.optString("browser_download_url"))
        }
        val pick = apks.firstOrNull { version.isNotBlank() && it.optString("name").contains(version) } ?: apks.firstOrNull()
        val apkUrl = pick?.optString("browser_download_url")?.ifBlank { null }
        val apkName = pick?.optString("name")
        val size = pick?.optLong("size") ?: 0L
        val sumAsset = apkName?.let { n ->
            all.firstOrNull { it.optString("name").equals("$n.sha256", true) && isTrustedAssetUrl(it.optString("browser_download_url")) }
        }
        val body = o.optString("body")
        return ReleaseInfo(
            version = version,
            title = o.optString("name").ifBlank { tag },
            notes = o.optString("body").trim(),
            pageUrl = o.optString("html_url").ifBlank { AppInfo.RELEASES_URL },
            apkUrl = apkUrl, apkName = apkName, apkSize = size,
            sha256 = apkName?.let { sha256FromNotes(body, it) },
            sha256Url = sumAsset?.optString("browser_download_url"),
        )
    }

    private val HEX64 = Regex("(?i)(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])")

    /** 更新说明里写的 SHA-256：优先找同时出现 APK 文件名的那一行，否则找带 sha256 字样的行。 */
    internal fun sha256FromNotes(body: String, apkName: String): String? {
        val lines = body.lines()
        lines.firstOrNull { it.contains(apkName, true) && HEX64.containsMatchIn(it) }?.let { return HEX64.find(it)!!.value.lowercase() }
        lines.firstOrNull { (it.contains("sha256", true) || it.contains("sha-256", true)) && HEX64.containsMatchIn(it) }
            ?.let { return HEX64.find(it)!!.value.lowercase() }
        return null
    }

    internal fun sha256Of(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins -> val b = ByteArray(64 * 1024); while (true) { val n = ins.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 下载「xxx.apk.sha256」资源里的十六进制摘要。拿不到时抛错（发布页声明了校验文件就必须能校验）。 */
    private fun fetchSha256(url: String): String {
        if (!isTrustedAssetUrl(url)) throw IOException("校验文件地址不可信，已停止更新")
        openTrusted(url).use { r ->
            if (!r.isSuccessful) throw IOException("下载校验文件失败：HTTP ${r.code}")
            return HEX64.find(r.body?.string().orEmpty().take(4096))?.value?.lowercase()
                ?: throw IOException("校验文件里没有 SHA-256，已停止更新")
        }
    }

    /** 下载 APK（带进度 0..1；大小未知时传 -1）。 */
    suspend fun download(release: ReleaseInfo, dir: File, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val url = release.apkUrl ?: throw IOException("这个版本没有附带 APK，请点「手动更新」去发布页下载")
        if (!isTrustedAssetUrl(url)) throw IOException("安装包地址不是本项目的 GitHub 发布页，已拒绝下载")
        if (release.apkSize <= 0) throw IOException("GitHub 没有给出安装包大小，无法校验，请点「手动更新」")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, (release.apkName ?: "update-${release.version}.apk").replace(Regex("""[^\w.\-]"""), "_"))
        try {
            openTrusted(url).use { resp ->
                if (!resp.isSuccessful) throw IOException("下载失败：HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("下载失败：没有内容")
                val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
                val tmp = File(out.path + ".part")
                body.byteStream().use { input ->
                    tmp.outputStream().use { os ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            done += n
                            if (done > release.apkSize) throw IOException("安装包比 GitHub 标注的大小还大，可能被篡改，已停止下载")
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != last) { last = pct; onProgress(done.toFloat() / total) }
                            } else onProgress(-1f)
                        }
                    }
                }
                if (tmp.length() != release.apkSize) {
                    tmp.delete()
                    throw IOException("安装包大小不对（下载了 ${tmp.length()} 字节，应为 ${release.apkSize} 字节），可能没下完整或被篡改，请重试")
                }
                val expected = release.sha256 ?: release.sha256Url?.let { fetchSha256(it) }
                if (expected != null && !sha256Of(tmp).equals(expected, true)) {
                    tmp.delete()
                    throw IOException("安装包 SHA-256 校验不通过，可能被篡改，已删除。请点「手动更新」从发布页下载")
                }
                if (!tmp.renameTo(out)) throw IOException("保存安装包失败")
            }
        } catch (e: IOException) {
            throw IOException(if (e is InterruptedIOException) "下载超时（GitHub 在大陆较慢），可以重试或点「手动更新」用浏览器下载" else (e.message ?: "下载失败"), e)
        }
        out
    }

    // ---------------- 安装前校验：包名 + 签名证书必须和当前安装的 App 一致 ----------------

    /** 证书指纹集合（SHA-256，小写十六进制）。 */
    internal fun certDigests(certs: List<ByteArray>): Set<String> = certs.map { c ->
        java.security.MessageDigest.getInstance("SHA-256").digest(c).joinToString("") { "%02x".format(it) }
    }.toSet()

    /**
     * 纯逻辑部分（便于测试）：返回 null 表示通过，否则是给用户看的中文错误。
     * @param archivePkg 下载的 APK 解析出的包名（null = 解析失败）
     */
    internal fun checkSameApp(archivePkg: String?, archiveCerts: Set<String>, installedPkg: String, installedCerts: Set<String>): String? = when {
        archivePkg == null -> "安装包无法解析（文件损坏或不是 APK），已取消安装"
        archivePkg != installedPkg -> "安装包的包名（$archivePkg）和当前 App 不一致，已拒绝安装"
        archiveCerts.isEmpty() -> "安装包没有有效签名，已拒绝安装"
        installedCerts.isEmpty() -> "读不到当前 App 的签名，无法校验，请点「手动更新」"
        archiveCerts != installedCerts -> "安装包的签名和当前 App 不一致（可能被篡改或不是官方版本），已拒绝安装"
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun certsOf(info: android.content.pm.PackageInfo?): List<ByteArray> {
        if (info == null) return emptyList()
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return emptyList()
            val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory?.takeLast(1)?.toTypedArray()
            return sigs?.map { it.toByteArray() } ?: emptyList()
        }
        return info.signatures?.map { it.toByteArray() } ?: emptyList()
    }

    /** 下载完成、拉起安装前调用；不通过时删除安装包并抛 IOException（中文提示）。 */
    @Suppress("DEPRECATION")
    fun verifyBeforeInstall(ctx: android.content.Context, apk: File) {
        val pm = ctx.packageManager
        val flags = if (android.os.Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            else android.content.pm.PackageManager.GET_SIGNATURES
        val archive = runCatching { pm.getPackageArchiveInfo(apk.path, flags) }.getOrNull()
        val installed = runCatching { pm.getPackageInfo(ctx.packageName, flags) }.getOrNull()
        val err = checkSameApp(archive?.packageName, certDigests(certsOf(archive)), ctx.packageName, certDigests(certsOf(installed)))
        if (err != null) { apk.delete(); throw IOException(err) }
    }
}
