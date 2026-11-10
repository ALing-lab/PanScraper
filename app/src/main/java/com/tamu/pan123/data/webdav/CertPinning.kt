package com.tamu.pan123.data.webdav

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** 服务器证书不受系统信任、也不是用户确认过的那张。fingerprint = 叶子证书 SHA-256（大写、冒号分隔）。 */
class UntrustedCertException(val fingerprint: String, val subject: String) :
    CertificateException("服务器证书未被信任（SHA-256 $fingerprint）。请在「测试连接」里核对证书指纹后选择信任")

/**
 * 「信任自签名证书」的实现：不再信任一切，而是**证书指纹固定**。
 * - 先按系统证书正常校验，通过就用；
 * - 否则只接受用户在「测试连接」里确认过的那张证书（SHA-256 指纹一致）；
 * - 其它证书一律拒绝，并把指纹带在 [UntrustedCertException] 里，供界面询问用户。
 */
object CertPinning {
    fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

    /** 统一格式：去掉空格 / 冒号，大写，再按两位加冒号。 */
    fun normalize(fp: String?): String? {
        val hex = fp?.filter { it.isLetterOrDigit() }?.uppercase() ?: return null
        if (hex.length != 64 || hex.any { it !in "0123456789ABCDEF" }) return null
        return hex.chunked(2).joinToString(":")
    }

    private fun systemTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    class PinningTrustManager(pin: String?, private val system: X509TrustManager = systemTrustManager()) : X509TrustManager {
        private val pin = normalize(pin)
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = system.checkClientTrusted(chain, authType)
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("服务器没有提供证书")
            try {
                system.checkServerTrusted(chain, authType); return
            } catch (_: CertificateException) { }
            val fp = sha256(leaf)
            if (pin != null && fp == pin) {
                leaf.checkValidity() // 过期的证书仍然拒绝
                return
            }
            throw UntrustedCertException(fp, leaf.subjectX500Principal?.name.orEmpty())
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }

    /** 主机名校验：正常校验通过即可；否则只有对方出示的正是固定的那张证书时才放行（自签名证书常常没写 IP）。 */
    fun hostnameVerifier(pin: String?, default: HostnameVerifier): HostnameVerifier {
        val p = normalize(pin)
        return HostnameVerifier { host, session ->
            if (default.verify(host, session)) return@HostnameVerifier true
            if (p == null) return@HostnameVerifier false
            val leaf = try { session.peerCertificates.firstOrNull() as? X509Certificate } catch (_: SSLPeerUnverifiedException) { null }
            leaf != null && sha256(leaf) == p
        }
    }

    /** 从异常链里找出 [UntrustedCertException]。 */
    fun findUntrusted(e: Throwable?): UntrustedCertException? {
        var t = e
        var n = 0
        while (t != null && n++ < 10) {
            if (t is UntrustedCertException) return t
            t.suppressed.forEach { s -> findUntrusted(s)?.let { return it } }
            t = t.cause
        }
        return null
    }
}
