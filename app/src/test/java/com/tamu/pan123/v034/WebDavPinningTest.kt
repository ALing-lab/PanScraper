package com.tamu.pan123.v034

import com.tamu.pan123.data.webdav.CertPinning
import com.tamu.pan123.data.webdav.UntrustedCertException
import com.tamu.pan123.data.webdav.WebDavDrive
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * 审查 M1：「信任自签名证书」不再信任一切，而是固定证书指纹。
 * 测试证书是 keytool 生成的一次性自签名证书（CN=nas.local，不含 localhost / IP），只用于本测试。
 */
class WebDavPinningTest {
    private val srv = MockWebServer()
    private lateinit var cert: X509Certificate
    private lateinit var otherCert: X509Certificate

    private fun load(name: String): Pair<KeyStore, X509Certificate> {
        val ks = KeyStore.getInstance("PKCS12")
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { ks.load(it, "testpass".toCharArray()) }
        val alias = ks.aliases().nextElement()
        return ks to (ks.getCertificate(alias) as X509Certificate)
    }

    @Before fun up() {
        val (ks, c) = load("selfsigned-test-cert.pkcs12")
        cert = c
        otherCert = load("selfsigned-test-cert-2.pkcs12").second
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "testpass".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        srv.useHttps(ctx.socketFactory, false)
        srv.start()
    }
    @After fun down() = srv.shutdown()

    private val ok207 = MockResponse().setResponseCode(207).setBody(
        """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response></d:multistatus>""")

    private fun drive(insecure: Boolean, pin: String?) = WebDavDrive("t", srv.url("/").toString(), "u", "p", insecure, pin)

    @Test fun firstConnectReportsFingerprintToConfirm() = runBlocking {
        val r = drive(insecure = true, pin = null).check()
        val e = r.exceptionOrNull()
        assertTrue("应要求确认证书：$e", e is UntrustedCertException)
        assertEquals(CertPinning.sha256(cert), (e as UntrustedCertException).fingerprint)
        assertEquals(0, srv.requestCount) // 握手阶段就拒绝，没有发出任何请求（密码不会发出去）
    }

    @Test fun pinnedCertIsAcceptedEvenWithoutHostnameMatch() = runBlocking {
        srv.enqueue(ok207)
        val r = drive(insecure = true, pin = CertPinning.sha256(cert)).check()
        assertTrue("固定的证书应能连接：${r.exceptionOrNull()}", r.isSuccess)
    }

    @Test fun mismatchedPinIsRejected() = runBlocking {
        val r = drive(insecure = true, pin = CertPinning.sha256(otherCert)).check()
        val e = r.exceptionOrNull() as? UntrustedCertException
        assertNotNull(e)
        assertEquals(CertPinning.sha256(cert), e!!.fingerprint)
        assertEquals(0, srv.requestCount)
    }

    @Test fun switchOffMeansSystemTrustOnly() = runBlocking {
        val r = drive(insecure = false, pin = CertPinning.sha256(cert)).check()
        assertTrue(r.isFailure)
        assertNull(r.exceptionOrNull() as? UntrustedCertException)
        assertEquals(0, srv.requestCount)
    }

    @Test fun trustManagerUnit() {
        val tm = CertPinning.PinningTrustManager(CertPinning.sha256(cert))
        tm.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA") // 不抛 = 通过
        try { tm.checkServerTrusted(arrayOf(otherCert), "ECDHE_ECDSA"); throw AssertionError("应拒绝") } catch (_: UntrustedCertException) {}
        assertEquals(CertPinning.sha256(cert), CertPinning.normalize(CertPinning.sha256(cert).replace(":", "").lowercase()))
        assertNull(CertPinning.normalize("abc"))
    }
}
