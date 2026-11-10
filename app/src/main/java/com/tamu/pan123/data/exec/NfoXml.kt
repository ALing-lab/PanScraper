package com.tamu.pan123.data.exec

/** NFO 里的 XML 转义，剧集 / 季 / 电影共用一套。 */
object NfoXml {
    /** 元素文本和属性值都安全（含引号）；去掉 XML 1.0 不允许的控制字符。 */
    fun esc(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            '\t', '\n', '\r' -> append(ch)
            else -> if (ch < ' ') Unit else append(ch)
        }
    }

    /** CDATA：内容里的 "]]>" 拆成两段 CDATA，读出来和原文完全一样。 */
    fun cdata(name: String, value: String): String {
        val clean = value.filter { it >= ' ' || it == '\t' || it == '\n' || it == '\r' }
        return "  <$name><![CDATA[${clean.replace("]]>", "]]]]><![CDATA[>")}]]></$name>"
    }

    fun tag(name: String, value: Any) = "  <$name>${esc(value.toString())}</$name>"

    fun flat(text: String?) = (text ?: "").replace(Regex("""\s*[\r\n]+\s*"""), "").trim()
}

/** 下载的图片：大小上限 + 文件头校验（确认真是 JPEG / PNG / WebP / GIF，而不是被劫持的网页）。 */
object ImageBytes {
    const val MAX_BYTES = 15L * 1024 * 1024
    private val EXTS = setOf("jpg", "jpeg", "png", "webp", "gif")

    fun looksLikeImage(b: ByteArray): Boolean {
        if (b.size < 12) return false
        fun at(i: Int) = b[i].toInt() and 0xFF
        return (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) ||                              // JPEG
            (at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47) ||                // PNG
            (String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP") ||
            String(b, 0, 4, Charsets.US_ASCII).startsWith("GIF8")
    }

    /** 从 URL 取一个安全的扩展名（只允许常见图片扩展名，其它一律 jpg）。 */
    fun safeExt(url: String): String {
        val e = url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return if (e in EXTS) e else "jpg"
    }
}
