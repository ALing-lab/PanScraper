package com.tamu.pan123.data.webdav

/** WebDAV 地址填写说明与实时解析（表单下方的提示）。 */
object WebDavAddress {
    data class Info(val text: String, val error: Boolean)

    const val HELP = "服务器地址是一个完整的网址：协议://主机:端口/路径。\n" +
        "• 协议：http:// 或 https://，必须写。\n" +
        "• 主机：NAS 的局域网 IP（如 192.168.1.5）或域名。\n" +
        "• 端口：写在主机后面、用冒号隔开，如 :5005。只有用默认端口（http 是 80，https 是 443）时才能省略；没有单独的端口栏。\n" +
        "• 路径：WebDAV 服务的路径，如 /dav；服务开在根目录时可以不写。\n" +
        "• 用户名 / 密码：WebDAV 服务自己的账号（如 NAS 用户、Alist 用户），不是网盘账号。"

    val EXAMPLES = listOf(
        "群晖 NAS" to "http://192.168.1.5:5005",
        "群晖 HTTPS" to "https://192.168.1.5:5006",
        "威联通 NAS" to "http://192.168.1.6:8080/share",
        "Alist / OpenList" to "http://192.168.1.7:5244/dav",
        "域名 + 默认端口" to "https://dav.example.com/dav",
    )

    fun describe(raw: String): Info {
        val u = raw.trim()
        if (u.isEmpty() || u == "https://" || u == "http://") return Info("格式：http://IP:端口/路径，例如 http://192.168.1.5:5005/dav", false)
        if (!u.startsWith("http://") && !u.startsWith("https://")) return Info("要以 http:// 或 https:// 开头", true)
        val uri = runCatching { java.net.URI(u) }.getOrNull() ?: return Info("地址格式不对（不要有空格或中文标点）", true)
        val host = uri.host ?: return Info("缺少主机（IP 或域名）", true)
        val https = u.startsWith("https://")
        val port = if (uri.port > 0) "端口 ${uri.port}" else "端口 ${if (https) 443 else 80}（默认）"
        val path = uri.rawPath?.takeIf { it.isNotBlank() && it != "/" } ?: "/（根目录）"
        return Info("主机 $host · $port · 路径 $path", false)
    }
}
