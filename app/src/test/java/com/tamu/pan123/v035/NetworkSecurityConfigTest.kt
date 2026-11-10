package com.tamu.pan123.v035

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V35-2：network_security_config 的 base-config 默认放行明文（只为局域网 WebDAV），
 * 所以代码里每个固定域名都必须在 domain-config（禁明文）里。这里扫描源码里的 http(s):// 固定地址来守住这条规则。
 */
class NetworkSecurityConfigTest {
    private val root = File("src/main")

    /** 只是文档 / 许可链接（在浏览器里打开）、XML 命名空间或示例地址，App 自己不会连。 */
    private val linkOnly = setOf(
        "developer.android.com", "fonts.google.com", "square.github.io", "coil-kt.github.io",
        "www.bouncycastle.org", "apache.org",
    )

    private fun domains(): List<String> {
        val xml = File(root, "res/xml/network_security_config.xml").readText()
        val block = xml.substringAfter("<domain-config cleartextTrafficPermitted=\"false\">").substringBefore("</domain-config>")
        return Regex("<domain[^>]*>([^<]+)</domain>").findAll(block).map { it.groupValues[1].trim().lowercase() }.toList()
    }

    private fun fixedHosts(): Set<String> =
        root.resolve("java").walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            Regex("https?://([A-Za-z0-9.-]+)").findAll(f.readText()).map { it.groupValues[1].lowercase().trimEnd('.') }
        }.filter { h ->
            h.contains('.') && !h.endsWith("example.com") && !Regex("^[0-9.]+$").matches(h) && h !in linkOnly
        }.toSet()

    @Test fun everyFixedHostForbidsCleartext() {
        val ds = domains()
        assertTrue("domain-config 为空", ds.isNotEmpty())
        val missing = fixedHosts().filter { h -> ds.none { d -> h == d || h.endsWith(".$d") } }
        assertTrue("这些固定域名没有加进 network_security_config 的 domain-config：$missing", missing.isEmpty())
    }

    @Test fun baseConfigOnlyThenDomainConfigForbidsCleartext() {
        val xml = File(root, "res/xml/network_security_config.xml").readText()
        assertTrue(xml.contains("<domain-config cleartextTrafficPermitted=\"false\">"))
        assertTrue("应写明新增固定域名的规则", xml.contains("必须同时加进下面的 domain-config"))
    }
}
