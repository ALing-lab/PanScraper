package com.tamu.pan123.data.exec

import com.tamu.pan123.data.AppDirs
import com.tamu.pan123.data.scrape.PlanAction
import com.tamu.pan123.data.tmdb.TmdbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 下载海报 / 生成 NFO 字节；缓存到 AppDirs。对齐 gen_artwork.py 的主要字段。 */
object ArtworkFetcher {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .addInterceptor(com.tamu.pan123.data.net.NetLog.interceptor("TMDB图片"))
        .build()

    suspend fun contentFor(action: PlanAction): ByteArray = withContext(Dispatchers.IO) {
        when (action.kind) {
            "image" -> {
                val url = action.artworkUrl
                    ?: resolveImageUrl(action)
                    ?: error("没有海报 URL：${action.newName}")
                download(url)
            }
            "tvshow_nfo" -> {
                val tid = action.tmdbId ?: error("无 tmdb id")
                buildTvshowNfo(tid).toByteArray(Charsets.UTF_8)
            }
            "movie_nfo" -> {
                val mid = action.tmdbId ?: error("无 tmdb id")
                buildMovieNfo(mid).toByteArray(Charsets.UTF_8)
            }
            "season_nfo" -> {
                buildSeasonNfo(action.tmdbId, action.season ?: 1).toByteArray(Charsets.UTF_8)
            }
            else -> error("不支持的 artwork kind=${action.kind}")
        }
    }

    private suspend fun resolveImageUrl(action: PlanAction): String? {
        val tid = action.tmdbId ?: return null
        val name = action.newName.lowercase()
        if (action.mediaKind == "movie") {
            return when {
                name.startsWith("fanart") || name.startsWith("backdrop") || name.startsWith("background") ->
                    movieImage(tid, "backdrops", listOf(null, "zh", "en"), "w1280") { it.backdropPath }
                else -> movieImage(tid, "posters", listOf("zh", null, "en"), "w780") { it.posterPath }
            }
        }
        return when {
            name.startsWith("season") && name.contains("poster") ->
                seasonPosterUrl(tid, action.season ?: 1) ?: showPosterUrl(tid)
            name.startsWith("poster") -> showPosterUrl(tid)
            name.startsWith("fanart") || name.startsWith("backdrop") || name.startsWith("background") ->
                backdropUrl(tid)
            name.startsWith("logo") || name.startsWith("clearlogo") ->
                logoUrl(tid)
            else -> showPosterUrl(tid)
        }
    }

    private suspend fun movieImage(
        mid: Int, key: String, langs: List<String?>, size: String,
        fallback: (com.tamu.pan123.data.tmdb.TmdbMovieDetail) -> String?,
    ): String? {
        val text = TmdbClient.get(
            "/movie/$mid/images",
            mapOf("include_image_language" to "zh,en,null", "language" to "zh"),
        )
        pickPath(text, key, langs)?.let { return "https://image.tmdb.org/t/p/$size$it" }
        val d = TmdbClient.movieDetails(mid) ?: return null
        return fallback(d)?.let { "https://image.tmdb.org/t/p/$size$it" }
    }

    private suspend fun buildMovieNfo(mid: Int): String {
        val raw = TmdbClient.get("/movie/$mid", mapOf("language" to "zh-CN")) ?: error("TMDB 电影详情失败")
        val credits = TmdbClient.get("/movie/$mid/credits", mapOf("language" to "zh-CN"))
        val d = TmdbClient.movieDetails(mid)
        val title = d?.let { TmdbClient.movieInfo(it).title } ?: jsonStr(raw, "title").orEmpty()
        return MovieNfo.build(mid, raw, credits, title)
    }

    private suspend fun showPosterUrl(tid: Int): String? {
        pickFromImages(tid, "posters", listOf("zh", null, "en"))?.let {
            return "https://image.tmdb.org/t/p/w780$it"
        }
        val d = TmdbClient.tvDetails(tid) ?: return null
        return d.posterPath?.let { "https://image.tmdb.org/t/p/w780$it" }
    }

    private suspend fun backdropUrl(tid: Int): String? {
        val d = TmdbClient.tvDetails(tid)
        d?.backdropPath?.let { return "https://image.tmdb.org/t/p/w1280$it" }
        pickFromImages(tid, "backdrops", listOf(null, "zh", "en"))?.let {
            return "https://image.tmdb.org/t/p/w1280$it"
        }
        return null
    }

    private suspend fun logoUrl(tid: Int): String? {
        val path = pickFromImages(tid, "logos", listOf("zh", "en", null)) ?: return null
        return "https://image.tmdb.org/t/p/original$path"
    }

    private suspend fun seasonPosterUrl(tid: Int, season: Int): String? {
        // season images
        val imgs = TmdbClient.get(
            "/tv/$tid/season/$season/images",
            mapOf("include_image_language" to "zh,null,en", "language" to "zh"),
        )
        pickPath(imgs, "posters", listOf("zh", null, "en"))?.let {
            return "https://image.tmdb.org/t/p/w780$it"
        }
        val text = TmdbClient.get("/tv/$tid/season/$season", mapOf("language" to "zh-CN")) ?: return null
        val path = jsonStr(text, "poster_path") ?: return null
        return "https://image.tmdb.org/t/p/w780$path"
    }

    private suspend fun pickFromImages(tid: Int, key: String, langs: List<String?>): String? {
        val text = TmdbClient.get(
            "/tv/$tid/images",
            mapOf("include_image_language" to "zh,en,null", "language" to "zh"),
        ) ?: return null
        return pickPath(text, key, langs)
    }

    private fun pickPath(jsonText: String?, key: String, langs: List<String?>): String? {
        if (jsonText.isNullOrBlank()) return null
        val arr = JSONObject(jsonText).optJSONArray(key) ?: return null
        for (lang in langs) {
            val cands = mutableListOf<JSONObject>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val l = if (o.isNull("iso_639_1")) null else o.optString("iso_639_1")
                if (l == lang) cands += o
            }
            if (cands.isNotEmpty()) {
                cands.sortByDescending { it.optDouble("vote_average", 0.0) * 1000 + it.optInt("width", 0) }
                return cands.first().optString("file_path").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun download(url: String): ByteArray {
        AppDirs.imgCache.mkdirs()
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val ext = url.substringAfterLast('.', "jpg").substringBefore('?').take(4).ifBlank { "jpg" }
        val cp = File(AppDirs.imgCache, "$hash.$ext")
        if (cp.isFile) return cp.readBytes()
        val req = Request.Builder().url(com.tamu.pan123.data.tmdb.TmdbNet.imageUrl(url))
            .header("User-Agent", "123pan-tmdb-android/0.2")
            .get().build()
        com.tamu.pan123.data.tmdb.TmdbNet.client().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("下载图片 HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: error("空图片")
            cp.writeBytes(bytes)
            return bytes
        }
    }

    /** 接近 probe/tvshow.nfo：tmdbid/uniqueid/plot/outline/director/actor/genre/rating/country/title… */
    private suspend fun buildTvshowNfo(tid: Int): String {
        val d = TmdbClient.tvDetails(tid) ?: error("TMDB 剧集详情失败")
        val info = TmdbClient.tvInfo(d)
        val credits = TmdbClient.get("/tv/$tid/credits", mapOf("language" to "zh-CN"))
        val ext = TmdbClient.get("/tv/$tid/external_ids")
        val L = mutableListOf<String>()
        L += tag("tmdbid", tid)
        L += """  <uniqueid type="tmdb" default="false">$tid</uniqueid>"""
        jsonLong(ext, "tvdb_id")?.let {
            L += tag("tvdbid", it)
            L += """  <uniqueid type="tvdb">$it</uniqueid>"""
        }
        jsonStr(ext, "imdb_id")?.let {
            L += tag("imdbid", it)
            L += """  <uniqueid type="imdb" default="true">$it</uniqueid>"""
        }
        val plot = flat(d.overview)
        L += cdata("plot", plot)
        L += cdata("outline", plot)
        // directors
        val crew = JSONObject(credits ?: "{}").optJSONArray("crew") ?: JSONArray()
        val seen = mutableSetOf<Int>()
        for (i in 0 until crew.length()) {
            val c = crew.getJSONObject(i)
            if (c.optString("job") != "Director") continue
            val id = c.optInt("id")
            if (id in seen) continue
            seen += id
            L += """  <director tmdbid="$id">${esc(c.optString("name"))}</director>"""
        }
        val cast = JSONObject(credits ?: "{}").optJSONArray("cast") ?: JSONArray()
        for (i in 0 until minOf(cast.length(), 40)) {
            val a = cast.getJSONObject(i)
            L += "  <actor>"
            L += "    <name>${esc(a.optString("name"))}</name>"
            L += "    <type>Actor</type>"
            if (a.optString("character").isNotBlank()) L += "    <role>${esc(a.optString("character"))}</role>"
            L += "    <tmdbid>${a.optInt("id")}</tmdbid>"
            if (!a.isNull("profile_path")) {
                L += "    <thumb>https://image.tmdb.org/t/p/original${a.optString("profile_path")}</thumb>"
            }
            L += "    <profile>https://www.themoviedb.org/person/${a.optInt("id")}</profile>"
            L += "  </actor>"
        }
        // genres / rating / country from detail json re-fetch raw for fields not in model
        val raw = TmdbClient.get("/tv/$tid", mapOf("language" to "zh-CN"))
        val genres = JSONObject(raw ?: "{}").optJSONArray("genres") ?: JSONArray()
        for (i in 0 until genres.length()) {
            L += tag("genre", genres.getJSONObject(i).optString("name"))
        }
        val rating = JSONObject(raw ?: "{}").optDouble("vote_average", 0.0)
        if (rating > 0) L += tag("rating", "%.1f".format(rating))
        val countries = JSONObject(raw ?: "{}").optJSONArray("production_countries") ?: JSONArray()
        for (i in 0 until countries.length()) {
            L += tag("country", countries.getJSONObject(i).optString("name"))
        }
        L += tag("title", info.title)
        L += tag("originaltitle", d.originalName.orEmpty())
        val fd = d.firstAirDate.orEmpty()
        if (fd.isNotBlank()) L += tag("premiered", fd)
        if (info.year.isNotBlank()) L += tag("year", info.year)
        L += "  <season>-1</season>"
        L += "  <episode>-1</episode>"
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<tvshow>\n" + L.joinToString("\n") + "\n</tvshow>\n"
    }

    private suspend fun buildSeasonNfo(tid: Int?, snum: Int): String {
        var title = if (snum == 0) "特别篇" else "第 $snum 季"
        var plot = ""
        var premiered = ""
        if (tid != null) {
            val text = TmdbClient.get("/tv/$tid/season/$snum", mapOf("language" to "zh-CN"))
            if (text != null) {
                jsonStr(text, "name")?.let { title = unescapeJson(it) }
                jsonStr(text, "overview")?.let { plot = flat(unescapeJson(it)) }
                jsonStr(text, "air_date")?.let { premiered = it }
            }
        }
        val L = mutableListOf<String>()
        L += cdata("plot", plot)
        L += cdata("outline", plot)
        L += tag("title", title)
        if (premiered.isNotBlank()) {
            L += tag("premiered", premiered)
            L += tag("releasedate", premiered)
            L += tag("year", premiered.take(4))
        }
        L += tag("seasonnumber", snum)
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<season>\n" + L.joinToString("\n") + "\n</season>\n"
    }

    private fun flat(text: String?) = (text ?: "").replace(Regex("""\s*[\r\n]+\s*"""), "").trim()
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun tag(name: String, value: Any) = "  <$name>${esc(value.toString())}</$name>"
    private fun cdata(name: String, value: String) = "  <$name><![CDATA[$value]]></$name>"
    private fun jsonStr(text: String?, key: String): String? {
        if (text.isNullOrBlank()) return null
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (o.isNull(key)) return null
        return o.optString(key).takeIf { it.isNotBlank() && it != "null" }
    }
    private fun jsonLong(text: String?, key: String): Long? {
        if (text.isNullOrBlank()) return null
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (o.isNull(key)) return null
        return o.optLong(key).takeIf { it != 0L }
    }
    private fun unescapeJson(s: String) = s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
}

/** movie.nfo（Kodi / Jellyfin / Emby 通用字段）。纯函数，便于测试。 */
object MovieNfo {
    fun build(mid: Int, detailJson: String, creditsJson: String?, zhTitle: String): String {
        val d = JSONObject(detailJson)
        fun s(k: String) = if (d.isNull(k)) "" else d.optString(k).trim()
        val L = mutableListOf<String>()
        L += tag("title", zhTitle.ifBlank { s("title") })
        L += tag("originaltitle", s("original_title"))
        val plot = flat(s("overview"))
        L += cdata("plot", plot)
        L += cdata("outline", plot)
        if (s("tagline").isNotBlank()) L += tag("tagline", s("tagline"))
        val runtime = d.optInt("runtime", 0)
        if (runtime > 0) L += tag("runtime", runtime)
        val rd = s("release_date")
        if (rd.isNotBlank()) {
            L += tag("premiered", rd)
            L += tag("releasedate", rd)
            L += tag("year", rd.take(4))
        }
        val rating = d.optDouble("vote_average", 0.0)
        if (rating > 0) L += tag("rating", "%.1f".format(java.util.Locale.US, rating))
        L += tag("tmdbid", mid)
        L += """  <uniqueid type="tmdb" default="${s("imdb_id").isBlank()}">$mid</uniqueid>"""
        if (s("imdb_id").isNotBlank()) {
            L += tag("imdbid", s("imdb_id"))
            L += """  <uniqueid type="imdb" default="true">${esc(s("imdb_id"))}</uniqueid>"""
        }
        d.optJSONArray("genres")?.let { g -> for (i in 0 until g.length()) L += tag("genre", g.getJSONObject(i).optString("name")) }
        d.optJSONArray("production_countries")?.let { g -> for (i in 0 until g.length()) L += tag("country", g.getJSONObject(i).optString("name")) }
        d.optJSONArray("production_companies")?.let { g -> for (i in 0 until minOf(g.length(), 5)) L += tag("studio", g.getJSONObject(i).optString("name")) }
        d.optJSONObject("belongs_to_collection")?.let { col ->
            L += "  <set>"
            L += "    <name>${esc(col.optString("name"))}</name>"
            L += "  </set>"
        }
        val cr = runCatching { JSONObject(creditsJson ?: "{}") }.getOrElse { JSONObject() }
        val crew = cr.optJSONArray("crew") ?: JSONArray()
        val seen = mutableSetOf<Int>()
        for (i in 0 until crew.length()) {
            val c = crew.getJSONObject(i)
            if (c.optString("job") != "Director") continue
            val id = c.optInt("id")
            if (!seen.add(id)) continue
            L += """  <director tmdbid="$id">${esc(c.optString("name"))}</director>"""
        }
        val cast = cr.optJSONArray("cast") ?: JSONArray()
        for (i in 0 until minOf(cast.length(), 30)) {
            val a = cast.getJSONObject(i)
            L += "  <actor>"
            L += "    <name>${esc(a.optString("name"))}</name>"
            if (a.optString("character").isNotBlank()) L += "    <role>${esc(a.optString("character"))}</role>"
            L += "    <order>$i</order>"
            if (!a.isNull("profile_path")) L += "    <thumb>https://image.tmdb.org/t/p/original${a.optString("profile_path")}</thumb>"
            L += "  </actor>"
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n<movie>\n" + L.joinToString("\n") + "\n</movie>\n"
    }

    private fun flat(text: String?) = (text ?: "").replace(Regex("""\s*[\r\n]+\s*"""), "").trim()
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun tag(name: String, value: Any) = "  <$name>${esc(value.toString())}</$name>"
    private fun cdata(name: String, value: String) = "  <$name><![CDATA[${value.replace("]]>", "]]&gt;")}]]></$name>"
}
