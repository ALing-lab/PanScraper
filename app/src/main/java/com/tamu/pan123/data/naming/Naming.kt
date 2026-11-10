package com.tamu.pan123.data.naming

/**
 * 命名 / 季集 / 搜词 —— 对齐 rename_movies.py。
 */
object Naming {
    val VIDEO_EXT = setOf(".mkv", ".mp4", ".avi", ".ts", ".m2ts", ".mov", ".wmv", ".iso", ".rmvb")
    val SUB_EXT = setOf(".srt", ".ass", ".ssa", ".sup")

    private val CN_NUM = "零一二三四五六七八九"
    private val NUM = """(\d{1,3}|[零一二三四五六七八九十百两]+)"""

    private val FOLDER_DONE = Regex("""^(?<name>.+?)(?:\((?<year>\d{4})\))?\{tmdb-(?<id>\d+)\}$""", RegexOption.IGNORE_CASE)
    private val TMDB_ID = Regex("""\{tmdb-(\d+)\}|\[tmdbid[=-]?(\d+)\]""", RegexOption.IGNORE_CASE)
    private val COLLECTION_MARK = Regex("""\{tmdb-collection-\d+\}""", RegexOption.IGNORE_CASE)
    private val TMDB_MARK = Regex("""\{tmdb-\d+\}|\[tmdbid[=-]?\d+\]""", RegexOption.IGNORE_CASE)

    private val SEASON_DIR = Regex("""(?i)^\s*(?:Season[ ._-]?\d{1,2}|S\d{1,2}|第${NUM}季|Specials?|SP)\s*$""")
    private val SEASON = Regex("""(?i)(?<![a-z0-9])(?:Season[ ._-]?(\d{1,2})|S(\d{1,2}))(?![0-9])|第${NUM}季""")

    private val EP_PATTERNS = listOf(
        Regex("""第${NUM}季\s*第${NUM}[集话話]"""),
        Regex("""(?i)(?<![a-z0-9])S(\d{1,2})[ ._-]?E(\d{1,3})(?![0-9])"""),
        Regex("""(?i)(?<![a-z0-9])(\d{1,2})x(\d{2,3})(?![0-9])"""),
        Regex("""第${NUM}[集话話]"""),
        Regex("""第(\d{1,3}|[零一二三四五六七八九十百两]+)期"""),
        Regex("""(?i)(?<![a-z0-9])EP?(\d{1,3})(?![0-9])"""),
    )

    private val CUT = Regex(
        """(?i)(?<![a-z0-9])(?:S\d{1,2}(?:[ ._-]?E\d{1,3})?|Season[ ._-]?\d{1,2}|EP?\d{1,3}|\d{1,2}x\d{2,3})(?![0-9])""" +
            """|第${NUM}[季集话話期]|\d{2,3}(?:\.\d+)?\s?(?:fps|帧)|高帧率|全\d+集""" +
            """|(?<![0-9])\d{3,4}x\d{3,4}(?![0-9])|(?<![A-Za-z0-9])(?:HDR10Plus|E-?AC-?3|AC3|DTS[-:]?X|L?PCM|OPUS|DoVi)(?![A-Za-z0-9])""" +
            """|(?i:HDR[ ._-]?Vivid)|菲影HDR"""
    )

    private val AD = Regex(
        """【[^】]*】|\[[^\]]*\]""" +
            """|(?i:(?:https?://)?(?:www\.)?[A-Za-z0-9-]+\.(?:com|net|org|cn|cc|tv|me|top|xyz|vip|info|club)(?![A-Za-z0-9]))"""
    )
    private val YEAR_TOKEN = Regex("""(?<![0-9A-Za-z\u4e00-\u9fff])((?:19|20)\d{2})(?![0-9A-Za-z])""")
    private val TAG = Regex(
        """(?ix)(?<!\w)(?:
        REMUX|Blu-?ray|WEB-?DL|WEBRip|HDRip|BRRip|DVDRip|HDTV|
        UHD|HDR10\+?|HDR10|HDR|DoVi|Dolby[\s.]?Vision|杜比视界|
        蓝光原盘|国英双音|国语|粤语|中字|内封字幕|外挂字幕|
        TrueHD|DTS(?:-?HD)?(?:\.?MA)?|Atmos|FLAC|
        x264|x265|H\.?264|H\.?265|HEVC|AVC|AV1|
        10bit|8bit|Hi10P|
        \d{3,4}p|4K|2160p|1080p|720p|
        \b(?:NF|AMZN|DSNP|ATVP|iT)\b|
        \bDV\b|
        Proper|REPACK|EXTENDED|UNCUT|Directors?\.?Cut|
        MA\s?\d+|DD\+?5\.1|DDP5\.1|AAC
    )(?!\w)"""
    )

    private val LANG_TOKENS = setOf(
        "chs", "cht", "sc", "tc", "zh", "zho", "chi", "zh-cn", "zh-tw", "zh-hk", "zh-hans", "zh-hant",
        "en", "eng", "ja", "jp", "jpn", "ko", "kor", "简体", "繁体", "简中", "繁中", "中文", "中字",
        "简英", "繁英", "中英", "双语", "chs&eng", "cht&eng", "简日", "繁日", "forced", "sdh", "default", "cc",
    )

    val CJK = Regex("""[\u4e00-\u9fff]""")

    /** 中文 / 阿拉伯数字 → Int。空串、无法识别的字返回 0（不会抛异常）。 */
    fun cn2int(text: String): Int {
        if (text.isEmpty()) return 0
        if (text.all { it.isDigit() }) return text.toIntOrNull() ?: 0
        val t = text.replace("两", "二")
        if ("百" in t) {
            val (h, rest) = t.split("百", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            return (if (h.isEmpty()) 1 else cn2int(h)) * 100 + (if (rest.isEmpty()) 0 else cn2int(rest))
        }
        if ("十" in t) {
            val (a, o) = t.split("十", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            return (if (a.isEmpty()) 1 else CN_NUM.indexOf(a).coerceAtLeast(0)) * 10 + (if (o.isEmpty()) 0 else CN_NUM.indexOf(o).coerceAtLeast(0))
        }
        val i = CN_NUM.indexOf(t)
        return if (i >= 0) i else 0
    }

    fun splitNameExt(filename: String): Pair<String, String> {
        val i = filename.lastIndexOf('.')
        return if (i <= 0) filename to "" else filename.substring(0, i) to filename.substring(i)
    }

    fun safeName(text: String): String =
        text.replace(Regex("""[\\/:*?"<>|]"""), " ").replace(Regex("""\s+"""), " ").trim()

    /**
     * 执行前的最后一道检查（计划文件可能被旧版本 / 手动改过）：去掉控制字符；
     * 含路径分隔符、是 "." / ".."、为空或超长（UTF-8 超过 255 字节）的名字直接拒绝，绝不让改名 / 上传跑到别的目录。
     */
    fun execName(name: String?): String {
        val n = (name ?: "").filter { it >= ' ' && it != '\u007f' }.trim()
        require(n.isNotEmpty()) { "文件名为空，已跳过" }
        require(n != "." && n != "..") { "文件名不合法（$n），已跳过" }
        require('/' !in n && '\\' !in n) { "文件名里不能有 / 或 \\（$n），已跳过" }
        require(n.toByteArray(Charsets.UTF_8).size <= 255) { "文件名太长（超过 255 字节），已跳过" }
        return n
    }

    fun dotTitle(title: String): String =
        safeName(title).replace(Regex("""\s+"""), ".").replace(Regex("""\.{2,}"""), ".").trim('.')

    fun folderTitle(title: String): String = safeName(title).replace(Regex("""\s+"""), "")

    fun buildFolderName(title: String, year: String, tmdbId: Int): String =
        folderTitle(title) + (if (year.isNotBlank()) "($year)" else "") + "{tmdb-$tmdbId}"

    fun buildCollectionName(name: String, collectionId: Int): String =
        "${folderTitle(name)}{tmdb-collection-$collectionId}"

    /** 「简洁命名」（设置里开关，默认关）：剧集 名称.年份.SxxEyy，电影 名称.年份.分辨率；文件夹命名不变。 */
    @Volatile var simpleNaming: Boolean = false

    /** 「播放器兼容命名」（默认关，「简洁命名」的子开关）：视频文件名末尾（扩展名前）加 {tmdb-ID}。只在简洁命名打开时生效。 */
    @Volatile var tmdbInFileName: Boolean = false

    private fun withTmdb(base: String, tmdbId: Int?) =
        if (simpleNaming && tmdbInFileName && tmdbId != null && tmdbId > 0) "$base{tmdb-$tmdbId}" else base

    /** 去掉文件名里已有的 {tmdb-ID} / [tmdbid=ID] 标记（避免重复，也不让它干扰画质 / 片名解析）。 */
    fun stripTmdbMark(stem: String): String = TMDB_MARK.replace(stem, "").trim()

    fun buildMovieBase(title: String, year: String, media: MediaParse.Media, stem: String = "", tmdbId: Int? = null): String =
        withTmdb(buildMovieBaseRaw(title, year, media, stripTmdbMark(stem)), tmdbId)

    private fun buildMovieBaseRaw(title: String, year: String, media: MediaParse.Media, stem: String): String {
        if (simpleNaming) return listOf(dotTitle(title), year, media.res).filter { it.isNotBlank() }.joinToString(".")
        val parts = mutableListOf(
            dotTitle(title), year, media.res, media.fps,
            if (stem.isNotEmpty()) MediaParse.movieHdr(stem) else media.hdr,
            if (stem.isNotEmpty() && MediaParse.hasUhd(stem)) "UHD" else "",
            media.bluray,
            if (stem.isNotEmpty() && MediaParse.hasRemux(stem)) "REMUX" else "",
        )
        parts += MediaParse.movieAudio(stem)
        val base = parts.filter { it.isNotBlank() }.joinToString(".")
        val grp = if (stem.isNotEmpty()) MediaParse.releaseGroup(stem) else ""
        return if (grp.isNotEmpty()) "$base-$grp" else base
    }

    fun buildEpisodeBase(title: String, year: String, season: Int, episode: Int, media: MediaParse.Media, tmdbId: Int? = null): String {
        val se = "S%02dE%02d".format(season, episode)
        val base = if (simpleNaming) listOf(dotTitle(title), year, se).filter { it.isNotBlank() }.joinToString(".")
        else listOf(dotTitle(title), year, se, media.res, media.fps, media.hdr, media.audio).filter { it.isNotBlank() }.joinToString(".")
        return withTmdb(base, tmdbId)
    }

    fun alreadyScraped(name: String): Boolean =
        FOLDER_DONE.containsMatchIn(name) || COLLECTION_MARK.containsMatchIn(name) || TMDB_MARK.containsMatchIn(name)

    fun scrapedTmdbId(name: String): Int? {
        val m = TMDB_ID.find(name) ?: return null
        return (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
    }

    fun folderDoneId(name: String): Int? =
        FOLDER_DONE.matchEntire(name)?.groups?.get("id")?.value?.toIntOrNull()

    fun isSeasonDir(name: String): Boolean = SEASON_DIR.matches(name)

    fun seasonFromName(name: String): Int? {
        if (Regex("""(?i)\s*(?:Specials?|SP)\s*""").matches(name)) return 0
        val m = SEASON.find(name) ?: return null
        val g = m.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return null
        return cn2int(g)
    }

    /** (season or null, episode) */
    fun parseEpisode(stem: String): Pair<Int?, Int>? {
        EP_PATTERNS.forEachIndexed { i, pat ->
            val m = pat.find(stem) ?: return@forEachIndexed
            val g = m.groupValues.drop(1)
            return when (i) {
                0, 1, 2 -> cn2int(g[0]) to cn2int(g[1])
                else -> null to cn2int(g[0])
            }
        }
        return null
    }

    fun cleanForSearch(name: String): String =
        AD.replace(TMDB_MARK.replace(name, " "), " ").replace(Regex("""\s+"""), " ").trim(' ', '.', '_', '-')

    fun parseTitleYear(head: String): Pair<String, String?> {
        var text = head.replace('_', ' ').replace('.', ' ')
        // 「盗梦空间2010」「雀骨2026」：中文标题后直接跟年份时补一个空格，便于拆出年份
        text = text.replace(Regex("""(\p{IsHan})((?:19|20)\d{2})(?!\d)"""), "$1 $2")
        TAG.find(text)?.let { m -> if (m.range.first > 0) text = text.substring(0, m.range.first) }
        val years = YEAR_TOKEN.findAll(text).toList()
        var year: String? = null
        if (years.isNotEmpty()) {
            val last = years.last()
            val titlePart = (text.substring(0, last.range.first) + " " + text.substring(last.range.last + 1)).trim()
            if (titlePart.replace(Regex("""[\s\-()（）]+"""), "").isNotEmpty()) {
                year = last.groupValues[1]
                text = titlePart
            }
        }
        text = text.replace(Regex("""[\(\)\[\]\{\}【】（）]+"""), " ")
            .replace(Regex("""\s+"""), " ").trim(' ', '-', '_', '.')
        text = text.replace(Regex("""(\d{4}) \1$"""), "$1")
        return text to year
    }

    fun queryFromName(stem: String): Pair<String, String?> {
        val cleaned = cleanForSearch(stem)
        val m = CUT.find(cleaned)
        val head = if (m != null) cleaned.substring(0, m.range.first) else cleaned
        var (title, year) = if (head.trim(' ', '.', '_', '-').isNotEmpty()) parseTitleYear(head) else ("" to null)
        if (year == null) {
            val (_, y2) = parseTitleYear(cleaned)
            year = y2
            if (year != null && title.endsWith(year)) year = null
        }
        return title to year
    }

    fun langSuffix(stem: String): String {
        val segs = stem.split('.').toMutableList()
        val out = mutableListOf<String>()
        while (segs.size > 1 && segs.last().trim().lowercase() in LANG_TOKENS) {
            out.add(0, segs.removeAt(segs.lastIndex).trim())
        }
        return out.joinToString(".")
    }

    /** 同目录重名时加 .v2；返回最终名。 */
    fun claim(target: String, old: String, taken: MutableSet<String>): String? {
        if (target == old) return target
        if (target.lowercase() !in taken || target.lowercase() == old.lowercase()) {
            taken += target.lowercase(); return target
        }
        val (base, ext) = splitNameExt(target)
        for (i in 2 until 100) {
            val cand = "$base.v$i$ext"
            if (cand.lowercase() !in taken) {
                taken += cand.lowercase(); return cand
            }
        }
        return null
    }

    fun seasonDirName(n: Int): String = if (n == 0) "Specials" else "Season $n"

    fun norm(t: String): String = (t.lowercase()).replace(Regex("""[^0-9a-z\u4e00-\u9fff]+"""), "")

    fun splitQuery(query: String): List<String> {
        val toks = query.split(Regex("""\s+"""))
        val cjk = toks.filter { CJK.containsMatchIn(it) }.joinToString(" ")
        val eng = toks.filter { !CJK.containsMatchIn(it) }.joinToString(" ")
        return listOf(query, eng, cjk).filter { it.isNotBlank() && norm(it).isNotEmpty() }.distinct()
    }

    // ---------------- v0.3：按名字识别目录角色 / 特别篇 ----------------

    /** 分类 / 地区 / 年份 / 画质这类“归档用”的文件夹名，永远不当作片名。 */
    val CATEGORY_NAMES = setOf(
        "资源", "影视", "影视资源", "视频", "videos", "video", "vidoes", "vids", "media", "媒体", "媒体库", "影音", "影片",
        "剧集", "电视剧", "连续剧", "剧", "电影", "影院", "movies", "movie", "films", "film", "tv", "tv shows", "tvshows",
        "shows", "series", "电视", "番剧", "动漫", "动画", "anime", "cartoon", "综艺", "综艺节目", "variety", "纪录片", "纪录",
        "documentary", "documentaries", "儿童", "少儿", "kids", "演唱会", "音乐", "music", "mv",
        "国产", "国产剧", "国剧", "国漫", "华语", "内地", "大陆", "港台", "港剧", "台剧", "香港", "台湾", "欧美", "欧美剧", "美剧", "英剧",
        "日剧", "韩剧", "泰剧", "日韩", "日漫", "外语", "国外", "海外", "亚洲", "其他地区",
        "下载", "downloads", "download", "已下载", "离线下载", "转存", "我的资源", "来自分享", "共享", "分享", "收藏",
        "4k", "8k", "1080p", "2160p", "720p", "uhd", "hdr", "蓝光", "高清", "超清", "原盘", "remux", "杜比视界",
        "新番", "完结", "连载", "已完结", "更新中", "热门", "经典", "最新", "合集", "全集", "系列合集",
        "动作", "喜剧", "爱情", "科幻", "悬疑", "恐怖", "惊悚", "剧情", "犯罪", "战争", "动画电影", "家庭", "奇幻", "古装",
    )
    private val YEARISH = Regex("""^(?:19|20)\d{2}(?:\s*(?:年|s|年代))?(?:\s*[-~～至]\s*(?:19|20)?\d{2,4})?$""", RegexOption.IGNORE_CASE)
    private val DECADE = Regex("""^(?:\d0年代|\d0s|(?:19|20)\d0s)$""", RegexOption.IGNORE_CASE)
    private val LETTER = Regex("""^[A-Za-z0-9](?:\s*[-~]\s*[A-Za-z0-9])?$""")

    fun isCategoryDir(name: String): Boolean {
        val n = name.trim().lowercase().replace(Regex("""^[\d.、_\- ]+(?=\D)"""), "") // 「01.电影」「2-美剧」
        if (n.isEmpty()) return true
        if (n in CATEGORY_NAMES) return true
        if (YEARISH.matches(n) || DECADE.matches(n) || LETTER.matches(n)) return true
        // 「国产电视剧」「欧美电影」「2026新剧」这类由分类词拼起来的名字
        val stripped = CATEGORY_NAMES.sortedByDescending { it.length }.fold(n) { acc, w -> if (w.length >= 2) acc.replace(w, "") else acc }
            .replace(Regex("""(?:19|20)\d{2}|新剧|新片|年|[\s._\-·&+/、]"""), "")
        return stripped.isEmpty()
    }

    /** 没有信息量的名字（新建文件夹、未命名、temp…），不当作片名。 */
    private val GENERIC = Regex(
        """^(?:新建文件夹|新建文件夹\s*\(\d+\)|新文件夹|未命名文件夹|未命名|untitled(?: folder)?|new folder(?: \(\d+\))?|folder|temp|tmp|misc|其他|其它|杂项|备份|backup|copy|副本|待整理|未整理|待刮削|bdmv|stream|video_ts)\d*$""",
        RegexOption.IGNORE_CASE,
    )
    fun isGenericDir(name: String): Boolean = GENERIC.matches(name.trim())

    enum class Extra { SPECIAL, MOVIE }

    private val SPECIAL_WORDS = Regex(
        """番外|花絮|加更|彩蛋|先导|纯享|特辑|特別篇|特别篇|特别版|幕后|未播|删减片段|精彩片段|会员版|直拍|plus版|超前营业|""" +
            """(?<![A-Za-z])(?:OVA|OAD|SP|SPs|Special|Specials|Extra|NCOP|NCED)(?:\s*\d{1,3})?(?![A-Za-z])""",
        RegexOption.IGNORE_CASE,
    )
    private val MOVIE_WORDS = Regex("""剧场版|劇場版|电影版|(?<![A-Za-z])(?:The\s+)?Movie(?![A-Za-z])""", RegexOption.IGNORE_CASE)

    /** 剧集文件夹里的视频是不是特别篇 / 剧场版（只看文件名里的关键词；SxxEyy 标准集数优先当正片）。 */
    fun extraKind(stem: String): Extra? {
        val ep = parseEpisode(stem)
        if (ep?.first == 0) return Extra.SPECIAL
        val clean = cleanForSearch(stem)
        if (MOVIE_WORDS.containsMatchIn(clean)) return Extra.MOVIE
        if (SPECIAL_WORDS.containsMatchIn(clean)) {
            // 「S01E03」这种标准编号且只带 Extra/NCOP 之类英文词时也算特别篇；中文关键词一律算
            return Extra.SPECIAL
        }
        return null
    }

    /** 特别篇 / 剧场版名里的序号（「剧场版2」「OVA 03」「第3期加更」），没有则 null。 */
    fun extraNumber(stem: String): Int? {
        val clean = cleanForSearch(stem)
        val spaced = stripTmdbMark(stem).replace(Regex("""[._\-\[\]]"""), " ")
        Regex("""(?i)(?<![A-Za-z])(?:OVA|OAD|SP|Special|剧场版|劇場版|Movie)\s*0*(\d{1,3})(?!\d)""").find(spaced)?.let { return it.groupValues[1].toInt() }
        parseEpisode(clean)?.let { return it.second }
        return null
    }

    /** 去掉剧场版关键词，用来按电影搜 TMDB。 */
    fun stripExtraWords(text: String): String =
        MOVIE_WORDS.replace(SPECIAL_WORDS.replace(text, " "), " ").replace(Regex("""\s+"""), " ").trim(' ', '.', '-', '_')

    /** 文件夹名像不像合集（系列 / 合集 / 三部曲 / Collection）。 */
    fun looksLikeCollectionName(name: String): Boolean =
        Regex("""系列|合集|三部曲|四部曲|全系列|(?<![A-Za-z])(?:Collection|Trilogy|Saga|Duology|Quadrilogy)(?![A-Za-z])|\{tmdb-collection-\d+\}""",
            RegexOption.IGNORE_CASE).containsMatchIn(name)
}
