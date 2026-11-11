package com.tamu.pan123.audit

import com.tamu.pan123.data.webdav.WebDavDrive
import com.tamu.pan123.data.tmdb.TmdbNetConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*

/** Local-only security regression tests. No real server or credentials. */
class AuditSecurityTest {
    private val servers=mutableListOf<MockWebServer>()
    private fun server()=MockWebServer().also{it.start();servers+=it}
    @After fun cleanup(){servers.forEach{it.shutdown()}}
    @Test fun hrefCannotEscapeConfiguredBase() {
        val d=WebDavDrive("test","https://example.invalid/dav/","","")
        val id=d.idFromHref("/dav/%2e%2e/private/")
        val target=id?.let{runCatching{d.urlFor(it,true)}.getOrNull()}
        assertTrue("Traversal must be rejected or stay within /dav; id=$id target=$target",target==null||target.encodedPath.startsWith("/dav/"))
    }
    @Test fun parentAliasMustNotDeleteServerRoot()=runBlocking {
        val s=server()
        s.enqueue(MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:"/>"""))
        s.enqueue(MockResponse().setResponseCode(204))
        val result=WebDavDrive("test",s.url("/dav/").toString(),"","").trash("/..")
        val requests=(0 until s.requestCount).map{s.takeRequest().let{it.method+" "+it.path}}
        assertTrue("Root aliases must be rejected; result=$result requests=$requests",result.isFailure&&requests.isEmpty())
    }
    @Test fun thirdPartyImageMirrorsMustBeOptIn() {
        assertFalse("UI says public mirrors are disabled by default",TmdbNetConfig().publicMirrors)
    }
    @Test fun ordinaryPathStaysInsideConfiguredBaseControl() {
        val d=WebDavDrive("test","https://example.invalid/dav/","","")
        assertEquals("/dav/Movies/a.mkv",d.urlFor("/Movies/a.mkv",false).encodedPath)
    }
}
