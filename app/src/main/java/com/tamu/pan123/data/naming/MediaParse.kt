package com.tamu.pan123.data.naming

/**
 * 画质 / HDR / 音轨 / 制作组解析 —— 对齐 rename_movies.py 的 parse_media / movie_hdr / movie_audio / release_group。
 */
object MediaParse {
    private val B = """(?<![A-Za-z0-9])"""
    private val E = """(?![A-Za-z0-9])"""

    private val RES_P = Regex("""(?i)${B}(2160|1080|720|480)[pi]${E}""")
    private val RES_ALT = listOf(
        Regex("""(?i)${B}(?:4K|UHD|3840x2160)${E}""") to "2160p",
        Regex("""(?i)${B}1920x1080${E}""") to "1080p",
        Regex("""(?i)${B}1280x720${E}""") to "720p",
    )
    private val FPS = Regex("""(?i)(?<![0-9])(?<![0-9]\.)(\d{2,3}(?:\.\d{1,3})?)\s?(?:fps|帧)""")
    private val VIVID = Regex("""(?i)(?<![A-Za-z0-9])HDR[ ._-]?Vivid(?![A-Za-z0-9])|菲影HDR""")
    private val DOVI = Regex("""(?i)${B}(?:DV|DoVi)${E}|Dolby[ ._-]?Vision|杜比视界|杜比世界""")
    private val HDR10P = Regex("""(?i)${B}HDR10(?:\+|Plus|P${E})""")
    private val HDR10 = Regex("""(?i)${B}HDR10(?![0-9+])""")
    private val HDR = Regex("""(?i)${B}HDR(?![A-Za-z0-9])""")
    private val BLURAY = Regex("""(?i)${B}(?:Blu-?ray|BDRip|BDRemux|R(?:E)?MUX|BDMV|BD(?:25|50|66|100)?)${E}|蓝光|原盘""")
    private val UHD = Regex("""(?i)${B}UHD${E}""")
    private val REMUX = Regex("""(?i)${B}(?:BD)?R(?:E)?MUX${E}""")

    /** 声道后缀（DDP5.1 / AAC2.0 / TrueHD7.1 / DTS-HD.MA.5.1），可带一个空格或点。 */
    private const val CH = """(?:[ .]?(?<ch>[1-9]\.[0-2])(?![0-9]))?"""

    /**
     * 音轨标记（v0.3 扩展）：按在原文件名里出现的顺序保留，大小写统一。
     * 顺序很重要：长的写法放前面（DTS-HD.MA 先于 DTS-HD 先于 DTS；DDP 先于 DD；E-AC-3 / EAC3 先于 AC3）。
     */
    private val MOVIE_AUDIO = Regex(
        """(?i)${B}(?:""" +
            """(?<truehd>TrueHD)""" +
            """|(?<atmos>(?:Dolby[ ._-]?)?Atmos|全景声)""" +
            """|(?<dtshdma>DTS[-. ]?HD[-. ]?MA)""" +
            """|(?<dtsx>DTS[-:. ]?X)(?![A-Za-z0-9])""" +
            """|(?<dtshd>DTS[-. ]?HD)""" +
            """|(?<dts>DTS)""" +
            """|(?<eac3d>E-AC-3)""" +
            """|(?<eac3>E-?AC-?3)""" +
            """|(?<ddp>DDP|DD\+)""" +
            """|(?<dd>DD)(?=[ .]?[1-9]\.[0-2]|[^A-Za-z0-9+]|$)""" +
            """|(?<ac3>AC-?3)""" +
            """|(?<aac>AAC)""" +
            """|(?<flac>FLAC)""" +
            """|(?<lpcm>L?PCM)""" +
            """|(?<opus>OPUS)""" +
            """|(?<mp3>MP3)""" +
            """|(?<hifi>Hi-?Fi)""" +
            """)""" + CH + """(?![A-Za-z])"""
    )

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

    private val NOT_GROUP = setOf(
        "dl", "rip", "hd", "ma", "x", "ray", "ac3", "264", "265", "x264", "x265", "h264", "h265",
        "hevc", "avc", "vivid", "av1", "remux", "rmux", "uhd", "hdr", "hdr10", "dovi", "dv", "atmos", "truehd",
        "dts", "aac", "flac", "lpcm", "ddp", "dd", "opus", "mp3", "hifi", "eac3", "ac3", "bluray", "web", "webdl", "webrip", "1080p", "2160p",
        "720p", "4k", "10bit", "8bit", "hdtv", "dvdrip", "bdrip", "imax", "hybrid",
    )
    private val GROUP = Regex("""(?<=[A-Za-z0-9+\]\)])-([A-Za-z0-9]+)$""")

    data class Media(
        val res: String = "",
        val fps: String = "",
        val hdr: String = "",
        val bluray: String = "",
        val audio: String = "",
    )

    fun movieHdr(stem: String): String {
        val hstem = VIVID.replace(stem, " ")
        val vivid = hstem != stem
        val parts = mutableListOf<String>()
        if (DOVI.containsMatchIn(hstem)) parts += "DoVi"
        when {
            HDR10P.containsMatchIn(hstem) -> parts += "HDR10+"
            HDR10.containsMatchIn(hstem) -> parts += "HDR10"
        }
        if (vivid) parts += "HDRVivid"
        if ((parts.isEmpty() || parts == listOf("DoVi")) && HDR.containsMatchIn(hstem)) parts += "HDR"
        return parts.joinToString(".")
    }

    fun movieAudio(stem: String): List<String> {
        val out = mutableListOf<String>()
        for (m in MOVIE_AUDIO.findAll(stem)) {
            val g = m.groups
            val base = when {
                g["truehd"] != null -> "TrueHD"
                g["atmos"] != null -> "Atmos"
                g["dtshdma"] != null -> "DTS-HD.MA"
                g["dtsx"] != null -> "DTS-X"
                g["dtshd"] != null -> "DTS-HD"
                g["dts"] != null -> "DTS"
                g["eac3d"] != null -> "E-AC-3"
                g["eac3"] != null -> "EAC3"
                g["ddp"] != null -> "DDP"
                g["dd"] != null -> "DD"
                g["ac3"] != null -> "AC3"
                g["aac"] != null -> "AAC"
                g["flac"] != null -> "FLAC"
                g["lpcm"] != null -> "LPCM"
                g["opus"] != null -> "OPUS"
                g["mp3"] != null -> "MP3"
                g["hifi"] != null -> "HIFI"
                else -> continue
            }
            val tag = base + (g["ch"]?.value ?: "")
            if (out.none { it.equals(tag, true) || it.startsWith(tag) }) {
                out.removeAll { tag.startsWith(it) && tag != it } // 「DDP」后又出现「DDP5.1」时保留更完整的
                out += tag
            }
        }
        return out
    }

    fun releaseGroup(stem: String): String {
        val m = GROUP.find(stem) ?: return ""
        val grp = m.groupValues[1]
        if (grp.lowercase() in NOT_GROUP || grp.all { it.isDigit() }) return ""
        val before = stem.substring(0, m.range.first)
        val spaced = before.replace('.', ' ')
        if (!(TAG.containsMatchIn(spaced) || MOVIE_AUDIO.containsMatchIn(before) || BLURAY.containsMatchIn(before))) return ""
        return grp
    }

    fun parseMedia(stem0: String): Media {
        val stem = Naming.stripTmdbMark(stem0)
        var res = RES_P.find(stem)?.let { "${it.groupValues[1]}p" } ?: ""
        if (res.isEmpty()) {
            for ((pat, v) in RES_ALT) if (pat.containsMatchIn(stem)) { res = v; break }
        }
        var fps = ""
        FPS.find(stem)?.let { m ->
            var num = m.groupValues[1]
            if (num.endsWith(".0")) num = num.dropLast(2)
            fps = "${num}fps"
        }
        val hdr = movieHdr(stem)
        val bluray = if (BLURAY.containsMatchIn(stem)) "BluRay" else ""
        // v0.3：剧集也保留全部音轨标记（按原文件名顺序）
        val audio = movieAudio(stem).joinToString(".")
        return Media(res, fps, hdr, bluray, audio)
    }

    fun hasUhd(stem: String) = UHD.containsMatchIn(stem)
    fun hasRemux(stem: String) = REMUX.containsMatchIn(stem)
}
