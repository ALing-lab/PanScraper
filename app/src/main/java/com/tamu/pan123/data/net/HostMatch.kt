package com.tamu.pan123.data.net

/** 主机名匹配：只认「完全相同」或「以 .domain 结尾的子域名」，避免 eviltmdb.org 之类被误认成 tmdb.org。 */
object HostMatch {
    fun isOrSubdomainOf(host: String?, domain: String): Boolean {
        val h = host?.trim()?.trimEnd('.')?.lowercase() ?: return false
        val d = domain.trim().trimEnd('.').lowercase()
        if (h.isEmpty() || d.isEmpty()) return false
        return h == d || h.endsWith(".$d")
    }

    fun matchesAny(host: String?, domains: Collection<String>): Boolean = domains.any { isOrSubdomainOf(host, it) }
}
