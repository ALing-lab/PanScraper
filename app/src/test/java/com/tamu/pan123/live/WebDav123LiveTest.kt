package com.tamu.pan123.live

import com.tamu.pan123.data.webdav.WebDavDrive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class WebDav123LiveTest {
    @Test fun actualAppClientUploadRenameMove(): Unit = runBlocking {
        assumeTrue(System.getenv("WEBDAV123_LIVE") == "1")
        val base=System.getenv("WEBDAV123_TEST_BASE") ?: error("No isolated base")
        require(base.startsWith("https://webdav.123pan.cn/webdav/PanScraper_live_test_"))
        val d=WebDavDrive("isolated-live",base,System.getenv("WEBDAV123_USER"),System.getenv("WEBDAV123_PASSWORD"))
        try {
            val own=d.mkdir("/","app_probe_"+UUID.randomUUID().toString().replace("-","")).getOrThrow()
            assertTrue(d.list(own).isEmpty())
            val dest=d.mkdir(own,"destination").getOrThrow()
            d.upload(own,"app_probe.txt","Small app-client live fixture\n".toByteArray()).getOrThrow()
            val f=d.list(own).single{!it.isDir&&it.fileName=="app_probe.txt"}
            val renamed=d.rename(f.fileId,"app_renamed.txt").getOrThrow()
            val moved=d.move(renamed,dest).getOrThrow()
            assertTrue(d.list(own).none{it.fileName=="app_renamed.txt"})
            assertTrue(d.list(dest).any{it.fileId==moved&&it.fileName=="app_renamed.txt"})
        } catch(e:Exception) {
            throw AssertionError("Actual app WebDAV client failed: "+e.javaClass.simpleName)
        }
    }
}
