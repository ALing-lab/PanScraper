package com.tamu.pan123.data.tmdb

import com.tamu.pan123.data.AppDirs
import com.tamu.pan123.data.SecureStore
import com.tamu.pan123.data.naming.Naming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import com.tamu.pan123.data.net.NetLog
import com.tamu.pan123.data.net.fetch
import com.tamu.pan123.data.net.standardTimeouts
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * TMDB API + 磁盘缓存（cache/tmdb，7 天）。密钥从不写日志。
 */
object TmdbClient {
    private const val LANG = "zh-CN"
    private const val SLEEP_MS = 350L
    private const val CACHE_DAYS = 7.0

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http: OkHttpClient get() = TmdbNet.client()

    /** 连接失败熔断：30 秒内再次请求直接报同样的错误，避免每个文件夹都等一次连接超时。 */
    @Volatile private var failUntil = 0L
    @Volatile private var failMsg: String? = null

    /** 本次运行中自动切换到的备用 API 地址（官方地址连不上、备用可用时）。 */
    @Volatile private var autoBase: String? = null

    fun resetFailures() { failUntil = 0; failMsg = null; autoBase = null }

    private fun bases(): List<String> {
        val cfg = TmdbNet.config.value.apiBase
        val auto = autoBase
        val official = setOf(TmdbNetConfig.DEFAULT_API, TmdbNetConfig.ALT_API)
        return when {
            auto != null && cfg in official -> listOf(auto)
            cfg == TmdbNetConfig.ALT_API -> listOf(cfg, TmdbNetConfig.DEFAULT_API)
            cfg == TmdbNetConfig.DEFAULT_API -> listOf(cfg, TmdbNetConfig.ALT_API)
            else -> listOf(cfg)
        }
    }

    /** 请求 API；官方地址连接失败时自动改用 api.tmdb.org，仍失败则熔断 30 秒并给出中文提示。 */
    private suspend fun fetchApi(path: String, params: Map<String, String>): com.tamu.pan123.data.net.HttpResult {
        var last: java.io.IOException? = null
        var lastHost = ""
        for (base in bases()) {
            val key = apiKey().trim().removePrefix("Bearer ")
            val bearer = key.count { it == '.' } == 2
            val url = (base + path).toHttpUrl().newBuilder()
                .also { if (!bearer) it.addQueryParameter("api_key", key) }
                .also { b -> params.forEach { (k, v) -> b.addQueryParameter(k, v) } }
                .build()
            val req = Request.Builder().url(url).header("User-Agent", "123pan-tmdb-android/0.2")
                .also { if (bearer) it.header("Authorization", "Bearer $key") }.get().build()
            try {
                val r = http.fetch(req)
                if (base != TmdbNet.config.value.apiBase && autoBase == null) {
                    autoBase = base
                    NetLog.note("TMDB", "官方地址连不上，已自动改用 ${url.host}")
                }
                return r
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                last = e; lastHost = url.host
                NetLog.error("TMDB", "${url.host} ${reason(e)}")
                if (!(isConnectFailure(e) || e is java.io.InterruptedIOException)) break
            }
        }
        val e = last ?: java.io.IOException("无法连接 TMDB")
        val msg = connectHelp(if (bases().size > 1) "api.tmdb.org / api.themoviedb.org" else lastHost, reason(e))
        if (isConnectFailure(e) || e is java.io.InterruptedIOException) { failUntil = System.currentTimeMillis() + 30_000; failMsg = msg }
        throw java.io.IOException(msg, e)
    }

    private fun isConnectFailure(e: java.io.IOException) =
        e is java.net.ConnectException || e is java.net.UnknownHostException || e is java.net.NoRouteToHostException ||
            e is javax.net.ssl.SSLException || e.message.orEmpty().startsWith("failed to connect", true) ||
            e.message.orEmpty().contains("Connection reset", true)

    fun connectHelp(host: String, why: String) =
        "无法连接 TMDB（$host）：$why。请开启代理，或在「设置 › TMDB 网络」开启 DoH、填写 TMDB 代理或反代地址"

    /** 测试当前 TMDB 网络设置：请求 /configuration。 */
    suspend fun test(): Result<String> = runCatching {
        resetFailures()
        val t0 = System.currentTimeMillis()
        val r = fetchApi("/configuration", emptyMap())
        val ms = System.currentTimeMillis() - t0
        val host = (autoBase ?: TmdbNet.config.value.apiBase).toHttpUrl().host
        val via = if (autoBase != null) "（首选地址不通，已自动改用另一个官方地址）" else ""
        when (r.code) {
            200 -> "连接成功：$host · ${ms}ms$via"
            401 -> error("已连上 $host，但 TMDB API 密钥无效（HTTP 401）")
            else -> error("$host 返回 HTTP ${r.code}（${ms}ms），如使用反代请检查地址")
        }
    }

    /** 图片测试用的真实海报路径（盗梦空间）。 */
    const val IMAGE_TEST_PATH = "/t/p/w92/9gk7adHYeDvHkCSEqAvQNLV5Uge.jpg"

    /** 用 TMDB 客户端下载一张小海报，报告实际用的图片地址。 */
    suspend fun testImage(): Result<String> = runCatching {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val req = okhttp3.Request.Builder().url(TmdbNet.imageUrl(TmdbNetConfig.DEFAULT_IMAGE + IMAGE_TEST_PATH)).build()
            try {
                TmdbNet.client().newCall(req).execute().use { r ->
                    val ms = System.currentTimeMillis() - t0
                    val host = r.request.url.host
                    val n = r.body?.bytes()?.size ?: 0
                    if (r.isSuccessful && n > 0 && r.header("Content-Type").orEmpty().startsWith("image")) "图片正常：$host · ${ms}ms"
                    else error("图片地址 $host 返回 HTTP ${r.code}，可填备用图片地址")
                }
            } catch (e: java.io.IOException) {
                error(e.message?.takeIf { it.startsWith("所有图片地址") } ?: "图片加载失败（${reason(e)}），可开启 DoH / 代理或填备用图片地址")
            }
        }
    }

    private fun reason(e: java.io.IOException) = when (e) {
        is java.net.UnknownHostException -> "域名解析失败"
        is javax.net.ssl.SSLException -> "TLS 握手失败（可能被拦截）"
        else -> if (isConnectFailure(e)) "连接超时 / 被拒绝" else if (e is java.io.InterruptedIOException) "请求超时" else (e.message ?: "网络错误")
    }

    private val featurette = Regex(
        """花絮|幕后|特辑|制作|纪录|featurette|making[ -]?of|behind[ -]the[ -]scenes|visual (?:effects|journey)|assembling|a visual|\bwired\b|\bspecial\b|road to|：.*之路""",
        RegexOption.IGNORE_CASE,
    )

    private fun apiKey(): String {
        val k = SecureStore.tmdbKey.value
        if (k.isBlank()) error("未设置 TMDB API 密钥")
        return k
    }

    private fun cachePath(path: String, params: Map<String, String>): File {
        val key = path + "?" + params.filterKeys { it != "api_key" }.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(AppDirs.tmdbCache, "$hash.json")
    }

    suspend fun get(path: String, params: Map<String, String> = emptyMap()): String? = withContext(Dispatchers.IO) {
        val full = params.toMutableMap().apply { putIfAbsent("language", LANG) }
        val cp = cachePath(path, full)
        if (cp.isFile && (System.currentTimeMillis() - cp.lastModified()) < CACHE_DAYS * 86400_000) {
            return@withContext cp.readText()
        }
        if (System.currentTimeMillis() < failUntil) throw java.io.IOException(failMsg ?: "无法连接 TMDB")
        delay(SLEEP_MS)
        val resp = fetchApi(path, full)
        run {
            if (resp.code !in 200..299) {
                NetLog.error("TMDB", "$path HTTP ${resp.code}")
                if (resp.code == 401) error("TMDB API 密钥无效（HTTP 401），请在资源中心重新填写")
                return@withContext null
            }
            val text = resp.text
            runCatching {
                cp.parentFile?.mkdirs()
                com.tamu.pan123.data.io.AtomicFiles.writeText(cp, text)
            }
            text
        }
    }

    private inline fun <reified T> decode(text: String?): T? =
        text?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    suspend fun searchMovieRaw(query: String, year: String?): List<TmdbSearchItem> {
        val params = mutableMapOf("query" to query, "include_adult" to "false")
        if (!year.isNullOrBlank()) params["primary_release_year"] = year
        return decode<TmdbSearchPage>(get("/search/movie", params))?.results.orEmpty()
    }

    suspend fun searchTvRaw(query: String, year: String?): List<TmdbSearchItem> {
        val params = mutableMapOf("query" to query, "include_adult" to "false")
        if (!year.isNullOrBlank()) params["first_air_date_year"] = year
        return decode<TmdbSearchPage>(get("/search/tv", params))?.results.orEmpty()
    }

    suspend fun movieDetails(id: Int): TmdbMovieDetail? = decode(get("/movie/$id"))
    suspend fun tvDetails(id: Int): TmdbTvDetail? = decode(get("/tv/$id"))

    suspend fun zhTitle(kind: String, id: Int, title: String): String {
        if (title.isBlank() || Naming.CJK.containsMatchIn(title)) return title
        val data = decode<TmdbTranslations>(get("/$kind/$id/translations", emptyMap())) ?: return title
        val found = data.translations.filter { it.iso639 == "zh" }
            .associate { (it.iso3166 ?: "") to ((it.data?.title ?: it.data?.name).orEmpty()) }
        return listOf("CN", "SG", "TW", "HK").firstNotNullOfOrNull { found[it]?.takeIf { t -> t.isNotBlank() } } ?: title
    }

    fun yearCandidates(year: String?): List<String?> {
        if (year.isNullOrBlank()) return listOf(null)
        val y = year.toInt()
        return listOf(year, (y + 1).toString(), (y - 1).toString(), null)
    }

    fun scoreMovie(r: TmdbSearchItem, parts: List<String>, year: String?): Double {
        val names = listOf(Naming.norm(r.title.orEmpty()), Naming.norm(r.originalTitle.orEmpty()))
        var best = 0.0
        for (q in parts.map { Naming.norm(it) }) {
            for (n in names) {
                if (q.isEmpty() || n.isEmpty()) continue
                val sim = when {
                    q == n -> 100.0
                    (q in n || n in q) && min(q.length, n.length).toDouble() / max(q.length, n.length) >= 0.8 -> 70.0
                    else -> 50.0 * similarity(q, n)
                }
                best = max(best, sim)
            }
        }
        var score = best
        val ry = (r.releaseDate ?: "").take(4)
        val releaseYear = ry.takeIf { it.length == 4 }?.toIntOrNull()
        val queryYear = year?.toIntOrNull()
        if (queryYear != null && releaseYear != null) {
            val diff = abs(releaseYear - queryYear)
            score += when (diff) { 0 -> 40.0; 1 -> 15.0; else -> -20.0 }
        } else if (!year.isNullOrBlank()) score -= 10
        val votes = r.voteCount
        score += 12 * log10(1.0 + votes) + 5 * log10(1.0 + r.popularity)
        if (votes < 10) score -= 20
        if (r.video) score -= 60
        if (featurette.containsMatchIn("${r.title.orEmpty()} ${r.originalTitle.orEmpty()}")) score -= 60
        return score
    }

    /** 简易 SequenceMatcher.ratio 近似：2*M / (lenA+lenB)。 */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in a.indices) for (j in b.indices) {
            dp[i + 1][j + 1] = if (a[i] == b[j]) dp[i][j] + 1 else max(dp[i][j + 1], dp[i + 1][j])
        }
        val lcs = dp[a.length][b.length]
        return 2.0 * lcs / (a.length + b.length)
    }

    suspend fun searchMovie(query: String, year: String?): TmdbSearchItem? {
        val parts = Naming.splitQuery(query)
        val cands = linkedMapOf<Int, TmdbSearchItem>()
        suspend fun collect(y: String?) {
            for (q in parts) for (item in searchMovieRaw(q, y)) cands.putIfAbsent(item.id, item)
        }
        fun pick(): Pair<TmdbSearchItem?, Double> {
            if (cands.isEmpty()) return null to -1e9
            val best = cands.values.maxBy { scoreMovie(it, parts, year) }
            return best to scoreMovie(best, parts, year)
        }
        collect(year)
        var (best, sc) = pick()
        val confident = best != null && !year.isNullOrBlank() &&
            (best!!.releaseDate ?: "").startsWith(year!!) && sc >= 160
        if (!confident) {
            for (y in yearCandidates(year).drop(1)) collect(y)
            val p = pick(); best = p.first; sc = p.second
        }
        return best
    }

    suspend fun searchTv(query: String, year: String?): TmdbSearchItem? {
        for (y in yearCandidates(year)) {
            val results = searchTvRaw(query, y)
            if (results.isNotEmpty()) {
                return if (y == null && !year.isNullOrBlank())
                    results.firstOrNull { (it.firstAirDate ?: "").startsWith(year) } ?: results.first()
                else results.first()
            }
        }
        return null
    }

    suspend fun movieInfo(d: TmdbMovieDetail): MatchInfo {
        val rd = d.releaseDate.orEmpty()
        val title = zhTitle("movie", d.id, d.title ?: d.originalTitle.orEmpty())
        return MatchInfo("movie", d.id, title, rd.take(4).takeIf { it.length == 4 }.orEmpty(), d.posterPath, d.overview)
    }

    suspend fun movieInfo(s: TmdbSearchItem): MatchInfo {
        val rd = s.releaseDate.orEmpty()
        val title = zhTitle("movie", s.id, s.title ?: s.originalTitle.orEmpty())
        return MatchInfo("movie", s.id, title, rd.take(4).takeIf { it.length == 4 }.orEmpty(), s.posterPath, s.overview)
    }

    suspend fun tvInfo(d: TmdbTvDetail): MatchInfo {
        val fd = d.firstAirDate.orEmpty()
        val title = zhTitle("tv", d.id, d.name ?: d.originalName.orEmpty())
        return MatchInfo("tv", d.id, title, fd.take(4).takeIf { it.length == 4 }.orEmpty(), d.posterPath, d.overview)
    }

    suspend fun tvInfo(s: TmdbSearchItem): MatchInfo {
        val fd = s.firstAirDate.orEmpty()
        val title = zhTitle("tv", s.id, s.name ?: s.originalName.orEmpty())
        return MatchInfo("tv", s.id, title, fd.take(4).takeIf { it.length == 4 }.orEmpty(), s.posterPath, s.overview)
    }

    suspend fun resolveMovie(stem: String, fallbackNames: List<String>, fallbackId: Int?): MatchInfo? {
        Naming.scrapedTmdbId(stem)?.let { id -> movieDetails(id)?.let { return movieInfo(it) } }
        val (title, year) = Naming.queryFromName(stem)
        if (title.isNotBlank()) searchMovie(title, year)?.let { return movieInfo(it) }
        if (fallbackId != null) movieDetails(fallbackId)?.let { return movieInfo(it) }
        for (name in fallbackNames) {
            val (t, y) = Naming.queryFromName(name)
            if (t.isNotBlank()) searchMovie(t, y)?.let { return movieInfo(it) }
        }
        return null
    }

    /** 同 [resolveShow]，并返回是用第几个名字匹配到的（-1 = 已刮削的 ID）。 */
    suspend fun resolveShowDetailed(names: List<String>, fixedId: Int?): Pair<MatchInfo?, Int> {
        if (fixedId != null) tvDetails(fixedId)?.let { return tvInfo(it) to -1 }
        names.forEachIndexed { i, name ->
            val (t, y) = Naming.queryFromName(name)
            if (t.isBlank()) return@forEachIndexed
            searchTv(t, y)?.let { return tvInfo(it) to i }
        }
        return null to -1
    }

    suspend fun resolveShow(names: List<String>, fixedId: Int?): MatchInfo? {
        if (fixedId != null) tvDetails(fixedId)?.let { return tvInfo(it) }
        for (name in names) {
            val (t, y) = Naming.queryFromName(name)
            if (t.isBlank()) continue
            searchTv(t, y)?.let { return tvInfo(it) }
        }
        return null
    }
}
