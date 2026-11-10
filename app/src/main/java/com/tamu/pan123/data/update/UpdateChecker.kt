package com.tamu.pan123.data.update

import com.tamu.pan123.AppInfo
import com.tamu.pan123.BuildConfigInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
    /** 测试替换（MockWebServer）。 */
    @Volatile var apiBase: String = "https://api.github.com"
    @Volatile var currentVersion: String = BuildConfigInfo.VERSION

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }
    private val dl by lazy {
        http.newBuilder().readTimeout(30, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
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
        val apks = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) && it.optString("state", "uploaded") == "uploaded" }
        val pick = apks.firstOrNull { version.isNotBlank() && it.optString("name").contains(version) } ?: apks.firstOrNull()
        val apkUrl = pick?.optString("browser_download_url")?.ifBlank { null }
        val apkName = pick?.optString("name")
        val size = pick?.optLong("size") ?: 0L
        return ReleaseInfo(
            version = version,
            title = o.optString("name").ifBlank { tag },
            notes = o.optString("body").trim(),
            pageUrl = o.optString("html_url").ifBlank { AppInfo.RELEASES_URL },
            apkUrl = apkUrl, apkName = apkName, apkSize = size,
        )
    }

    /** 下载 APK（带进度 0..1；大小未知时传 -1）。 */
    suspend fun download(release: ReleaseInfo, dir: File, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val url = release.apkUrl ?: throw IOException("这个版本没有附带 APK，请点「手动更新」去发布页下载")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, (release.apkName ?: "update-${release.version}.apk").replace(Regex("""[^\w.\-]"""), "_"))
        try {
            dl.newCall(Request.Builder().url(url).header("User-Agent", "${AppInfo.NAME_EN}/${currentVersion}").build()).execute().use { resp ->
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
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != last) { last = pct; onProgress(done.toFloat() / total) }
                            } else onProgress(-1f)
                        }
                    }
                }
                if (!tmp.renameTo(out)) throw IOException("保存安装包失败")
            }
        } catch (e: IOException) {
            throw IOException(if (e is InterruptedIOException) "下载超时（GitHub 在大陆较慢），可以重试或点「手动更新」用浏览器下载" else (e.message ?: "下载失败"), e)
        }
        out
    }
}
