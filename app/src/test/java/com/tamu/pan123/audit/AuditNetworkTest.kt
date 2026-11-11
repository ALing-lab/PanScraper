package com.tamu.pan123.audit

import com.tamu.pan123.data.p115.*
import com.tamu.pan123.data.webdav.WebDavDrive
import com.tamu.pan123.data.tmdb.*
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*

class AuditNetworkTest {
    private val servers = mutableListOf<MockWebServer>()
    private fun server() = MockWebServer().also { it.start(); servers += it }
    @After fun cleanup() {
        servers.forEach { it.shutdown() }
        TmdbNet.tryOfficialImage=true; TmdbNet.preferredImageBase=null; TmdbNet.apply(TmdbNetConfig())
    }
    @Test fun webdavForbiddenMultistatusIsNotEmptySuccess() = runBlocking {
        val s=server()
        s.enqueue(MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:"><d:response><d:href>/folder/</d:href><d:status>HTTP/1.1 403 Forbidden</d:status></d:response></d:multistatus>"""))
        val outcome=runCatching { WebDavDrive("test",s.url("/").toString(),"","").list("/folder") }
        assertTrue("Forbidden listing must be an error, actual=$outcome",outcome.isFailure)
    }
    @Test fun webdavEmptyXmlIsNotEmptySuccess() = runBlocking {
        val s=server(); s.enqueue(MockResponse().setResponseCode(207).setBody(""))
        assertTrue(runCatching { WebDavDrive("test",s.url("/").toString(),"","").list("/folder") }.isFailure)
    }
    @Test fun http503CannotBecomeSuccessful115Move() = runBlocking {
        val s=server(); s.enqueue(MockResponse().setResponseCode(503).setBody("""{"code":503,"message":"service unavailable"}"""))
        val store=object:P115TokenStore {
            override fun load()=P115Creds("u","c","s")
            override fun save(c:P115Creds) {}
        }
        val d=Drive115("test",store,apiBase=s.url("/").toString().trimEnd('/'),pathIntervalMs=0)
        assertTrue("HTTP 503 must fail",d.move("file","dest").isFailure)
    }
    @Test fun jsonImageResponseMustTryMirror() {
        val main=server(); val mirror=server()
        main.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody("{\"error\":\"blocked\"}"))
        mirror.enqueue(MockResponse().setHeader("Content-Type","image/jpeg").setBody("VALID-JPEG-FIXTURE"))
        TmdbNet.tryOfficialImage=false; TmdbNet.preferredImageBase=null
        TmdbNet.apply(TmdbNetConfig(imageBase=main.url("/").toString().trimEnd('/'),imageFallback=mirror.url("/").toString().trimEnd('/'),doh=false,publicMirrors=false))
        val url=main.url("/t/p/w185/test.jpg")
        val body=TmdbNet.client().newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() }
        assertEquals("VALID-JPEG-FIXTURE",body)
    }
}
