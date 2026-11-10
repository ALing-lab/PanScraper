package com.tamu.pan123.data.tmdb

import com.tamu.pan123.data.net.NetLog
import com.tamu.pan123.data.net.standardTimeouts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * TMDB 网络设置：API / 图片地址、DNS-over-HTTPS、仅用于 TMDB 的代理。
 * 中国大陆网络下 api.themoviedb.org 常被 DNS 污染或阻断：可改用 api.tmdb.org、开启 DoH，或填代理 / 反代地址。
 */
@Serializable
data class TmdbNetConfig(
    val apiBase: String = ALT_API,
    val imageBase: String = DEFAULT_IMAGE,
    /** 图片备用地址（镜像 / 反代）：image.tmdb.org 连不上时自动改用。 */
    val imageFallback: String = "",
    /** 官方图片地址都连不上时，自动试公共图片镜像（img.amodb.dev、wsrv.nl）。 */
    val publicMirrors: Boolean = true,
    /** 用阿里 / 腾讯 DoH 解析 TMDB 域名（绕过 DNS 污染）；DoH 失败时回落系统 DNS。 */
    val doh: Boolean = true,
    /** none / http / socks */
    val proxyType: String = "none",
    val proxyHost: String = "",
    val proxyPort: Int = 0,
) {
    companion object {
        /** 官方主域名（部分大陆网络被 DNS 污染）。 */
        const val DEFAULT_API = "https://api.themoviedb.org/3"
        /** 官方备用域名，v0.2.2 起为默认。 */
        const val ALT_API = "https://api.tmdb.org/3"
        const val DEFAULT_IMAGE = "https://image.tmdb.org"
        /** TMDB 网站自己用的图片 CDN（需带 Referer: https://www.themoviedb.org/，否则 301 回 image.tmdb.org）。 */
        const val MEDIA_IMAGE = "https://media.themoviedb.org"
        /** 公共图片镜像（2026-11 实测可用）：前缀式与 url= 转发式。 */
        val PUBLIC_MIRRORS = listOf("https://img.amodb.dev", "https://wsrv.nl/?url=")
    }

    /** 规范化：去掉末尾斜杠；API 地址没写版本号时补 /3。 */
    fun normalized(): TmdbNetConfig {
        var a = apiBase.trim().trimEnd('/').ifBlank { DEFAULT_API }
        if (!a.startsWith("http://") && !a.startsWith("https://")) a = "https://$a"
        if (!Regex("""/3$""").containsMatchIn(a)) a = "$a/3"
        var i = imageBase.trim().trimEnd('/').ifBlank { DEFAULT_IMAGE }
        if (!i.startsWith("http://") && !i.startsWith("https://")) i = "https://$i"
        i = i.removeSuffix("/t/p")
        var f = imageFallback.trim().trimEnd('/').removeSuffix("/t/p")
        if (f.isNotEmpty() && !f.startsWith("http://") && !f.startsWith("https://")) f = "https://$f"
        return copy(apiBase = a, imageBase = i, imageFallback = f, proxyHost = proxyHost.trim())
    }
}

object TmdbNet {
    private val _config = MutableStateFlow(TmdbNetConfig())
    val config: StateFlow<TmdbNetConfig> = _config

    @Volatile private var cachedFor: TmdbNetConfig? = null
    @Volatile private var cached: OkHttpClient? = null

    fun apply(c: TmdbNetConfig) {
        _config.value = c.normalized()
    }

    /** 把默认的 image.tmdb.org 换成设置里的图片地址。 */
    fun imageUrl(url: String): String {
        val base = _config.value.imageBase
        return if (base != TmdbNetConfig.DEFAULT_IMAGE && url.startsWith(TmdbNetConfig.DEFAULT_IMAGE)) base + url.removePrefix(TmdbNetConfig.DEFAULT_IMAGE) else url
    }

    /** API 与图片共用的客户端（按当前设置构建，设置变化后重建）。 */
    fun client(): OkHttpClient {
        val c = _config.value
        cached?.let { if (cachedFor == c) return it }
        return synchronized(this) {
            cached?.takeIf { cachedFor == c } ?: build(c).also { cached = it; cachedFor = c }
        }
    }

    private val bootstrap: OkHttpClient by lazy { OkHttpClient.Builder().standardTimeouts(callSec = 10).build() }

    private val dohChain: List<DnsOverHttps> by lazy {
        listOf(
            "https://dns.alidns.com/dns-query" to listOf("223.5.5.5", "223.6.6.6"),
            "https://doh.pub/dns-query" to listOf("1.12.12.12", "120.53.53.53"),
        ).map { (url, ips) ->
            DnsOverHttps.Builder().client(bootstrap).url(url.toHttpUrl())
                .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
                .includeIPv6(false)
                .build()
        }
    }

    /** 只对 TMDB 相关域名走 DoH；全部失败时回落系统 DNS。 */
    private val tmdbDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val tmdbHost = hostname.endsWith("themoviedb.org") || hostname.endsWith("tmdb.org")
            if (tmdbHost) for (d in dohChain) {
                val r = runCatching { d.lookup(hostname) }.getOrNull()
                if (!r.isNullOrEmpty()) {
                    NetLog.line("TMDB", "DoH $hostname -> ${r.joinToString { it.hostAddress ?: "" }}")
                    return r
                }
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    private fun build(c: TmdbNetConfig): OkHttpClient {
        val b = OkHttpClient.Builder()
            .standardTimeouts(callSec = 90) // 图片可能要依次试 5～6 个地址
            .addInterceptor(imageFallbackInterceptor(c))
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(NetLog.interceptor("TMDB"))
        val proxy = when (c.proxyType) {
            "http" -> if (c.proxyHost.isNotBlank() && c.proxyPort > 0) Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(c.proxyHost, c.proxyPort)) else null
            "socks" -> if (c.proxyHost.isNotBlank() && c.proxyPort > 0) Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(c.proxyHost, c.proxyPort)) else null
            else -> null
        }
        if (proxy != null) b.proxy(proxy)
        else if (c.doh) b.dns(tmdbDns) // 走代理时由代理解析域名
        return b.build()
    }

    /** 上次成功的图片地址（主地址 / 官方 / 备用），下次优先用它，免得每张图都先等超时。 */
    @Volatile var preferredImageBase: String? = null
        internal set

    /** 测试用：关掉中间的官方地址候选，避免单元测试连外网。 */
    @Volatile internal var tryOfficialImage = true

    /**
     * 图片地址候选：设置里的主地址 → 官方 image.tmdb.org → 备用地址（镜像 / 反代）。
     * 备用地址两种写法：
     *  - 主机前缀，如 https://img.example.com → https://img.example.com/t/p/w185/xxx.jpg
     *  - 以 url= 结尾的转发前缀，如 https://wsrv.nl/?url= → 后面拼上完整的 image.tmdb.org 地址
     */
    fun imageCandidates(url: okhttp3.HttpUrl, c: TmdbNetConfig = _config.value): List<okhttp3.HttpUrl> {
        val p = url.encodedPath.let { x -> val i = x.indexOf("/t/p/"); if (i >= 0) x.substring(i) else return listOf(url) }
        val official = TmdbNetConfig.DEFAULT_IMAGE + p
        val bases = imageBases(c).filter { it.isNotBlank() }.distinct()
            .let { b -> preferredImageBase?.takeIf { it in b }?.let { listOf(it) + (b - it) } ?: b }
        return bases.mapNotNull { b -> buildImageUrl(b, p, official)?.toHttpUrlOrNull() }.distinct()
    }

    private fun buildImageUrl(base: String, path: String, official: String): String? = when {
        base.contains("{url}") -> base.replace("{url}", official)
        base.endsWith("=") -> base + official
        else -> base.trimEnd('/') + path
    }

    /** 图片地址顺序：设置的主地址（默认 image.tmdb.org，走 DoH）→ 官方 image.tmdb.org → 官网图片 CDN media.themoviedb.org → 自定义备用。 */
    private fun imageBases(c: TmdbNetConfig): List<String> =
        listOf(c.imageBase,
            if (tryOfficialImage) TmdbNetConfig.DEFAULT_IMAGE else "",
            if (tryOfficialImage) TmdbNetConfig.MEDIA_IMAGE else "",
            c.imageFallback) + (if (c.publicMirrors && tryOfficialImage) TmdbNetConfig.PUBLIC_MIRRORS else emptyList())

    private fun baseOf(c: TmdbNetConfig, u: okhttp3.HttpUrl): String? =
        (imageBases(c) + TmdbNetConfig.DEFAULT_IMAGE + TmdbNetConfig.MEDIA_IMAGE + TmdbNetConfig.PUBLIC_MIRRORS).filter { it.isNotBlank() }.distinct()
            .firstOrNull { b -> b.toHttpUrlOrNull()?.let { it.host == u.host && it.port == u.port } == true }

    /** 是否是设置里的 TMDB 图片地址（含自定义镜像）。 */
    fun isImageHost(u: okhttp3.HttpUrl): Boolean = baseOf(_config.value, u) != null

    private fun isTmdbImage(c: TmdbNetConfig, u: okhttp3.HttpUrl): Boolean =
        u.encodedPath.contains("/t/p/") && baseOf(c, u) != null

    /** 图片：主地址连不上 / 被劫持（非图片）/ 5xx 时，依次换官方地址和备用地址。 */
    private fun imageFallbackInterceptor(c: TmdbNetConfig) = okhttp3.Interceptor { chain ->
        val req = chain.request()
        if (!isTmdbImage(c, req.url)) return@Interceptor chain.proceed(req)
        val cands = imageCandidates(req.url, c)
        var last: java.io.IOException? = null
        var lastResp: okhttp3.Response? = null
        for ((i, u) in cands.withIndex()) {
            if (i > 0) NetLog.note("TMDB", "图片地址 ${cands[i - 1].host} 不可用，改用 ${u.host}")
            try {
                lastResp?.close(); lastResp = null
                val rb = req.newBuilder().url(u)
                if (u.host == "media.themoviedb.org") rb.header("Referer", "https://www.themoviedb.org/")
                val r = chain.proceed(rb.build())
                val type = r.header("Content-Type").orEmpty()
                val good = r.isSuccessful && !type.startsWith("text/")
                if (good) {
                    preferredImageBase = baseOf(c, u) ?: preferredImageBase
                    return@Interceptor r
                }
                if (r.code == 404 && i == cands.lastIndex) return@Interceptor r
                NetLog.error("TMDB", "图片 ${u.host} 返回 HTTP ${r.code} ${type.substringBefore(';')}")
                lastResp = r
            } catch (e: java.io.IOException) {
                NetLog.error("TMDB", "图片 ${u.host} 连接失败：${e.javaClass.simpleName} ${e.message.orEmpty()}")
                last = e
            }
        }
        lastResp ?: throw java.io.IOException(
            "所有图片地址都连不上（试过 ${cands.joinToString("、") { it.host }}）。" +
                "请在「设置 › TMDB 网络」里配置代理，或填一个能访问的图片镜像" +
                (last?.let { "；最后的错误：${it.javaClass.simpleName} ${it.message.orEmpty()}" } ?: ""),
            last,
        )
    }

    fun describe(c: TmdbNetConfig = _config.value): String = buildString {
        append(c.apiBase.removePrefix("https://").removeSuffix("/3"))
        if (c.proxyType != "none" && c.proxyHost.isNotBlank()) append(" · ${c.proxyType.uppercase()} 代理")
        else if (c.doh) append(" · DoH")
    }
}
