package com.tamu.pan123.update

import com.tamu.pan123.AppInfo
import com.tamu.pan123.data.update.UpdateChecker
import com.tamu.pan123.data.update.UpdateResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateCheckerTest {
    private val srv = MockWebServer()
    private val oldBase = UpdateChecker.apiBase
    private val oldVer = UpdateChecker.currentVersion
    @Before fun up() { srv.start(); UpdateChecker.apiBase = srv.url("/").toString() }
    @After fun down() { srv.shutdown(); UpdateChecker.apiBase = oldBase; UpdateChecker.currentVersion = oldVer }

    private fun release(tag: String) = """{"tag_name":"$tag","name":"PanScraper $tag","body":"- 新功能","html_url":"https://github.com/ALing-lab/PanScraper/releases/tag/$tag",
        "assets":[{"name":"checksums.txt","browser_download_url":"https://github.com/ALing-lab/PanScraper/releases/download/$tag/checksums.txt","size":10,"state":"uploaded"},
                  {"name":"old-v0.1.apk","browser_download_url":"https://github.com/ALing-lab/PanScraper/releases/download/v0.1/old-v0.1.apk","size":5,"state":"uploaded"},
                  {"name":"PanScraper-${tag}.apk","browser_download_url":"https://github.com/ALing-lab/PanScraper/releases/download/$tag/PanScraper-${tag}.apk","size":23527238,"state":"uploaded"}]}"""

    @Test fun repoConstants() {
        assertEquals("ALing-lab/PanScraper", AppInfo.GITHUB_REPO)
        assertEquals("https://github.com/ALing-lab/PanScraper/releases", AppInfo.RELEASES_URL)
        assertEquals("PanScraper", AppInfo.NAME)
    }

    @Test fun newerTagPicksVersionedApk() = runBlocking {
        UpdateChecker.currentVersion = "0.3.3"
        srv.enqueue(MockResponse().setBody(release("v0.3.10")))
        val r = UpdateChecker.check()
        val req = srv.takeRequest()
        assertEquals("/repos/ALing-lab/PanScraper/releases/latest", req.path)
        assertTrue(r is UpdateResult.Available)
        val rel = (r as UpdateResult.Available).release
        assertEquals("0.3.10", rel.version)
        assertEquals("PanScraper-v0.3.10.apk", rel.apkName)
        assertEquals("https://github.com/ALing-lab/PanScraper/releases/download/v0.3.10/PanScraper-v0.3.10.apk", rel.apkUrl)
        assertEquals(23527238L, rel.apkSize)
    }

    @Test fun sameTagIsUpToDate() = runBlocking {
        UpdateChecker.currentVersion = "0.3.3"
        srv.enqueue(MockResponse().setBody(release("v0.3.3")))
        assertTrue(UpdateChecker.check() is UpdateResult.UpToDate)
    }

    @Test fun noReleaseYet404() = runBlocking {
        srv.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        assertTrue(UpdateChecker.check() is UpdateResult.Error)
    }

    @Test fun compareVersions() {
        assertTrue(UpdateChecker.compare("v0.3.3", "0.3.2") > 0)
        assertTrue(UpdateChecker.compare("0.3.10", "0.3.9") > 0)
        assertEquals(0, UpdateChecker.compare("v0.3.3", "0.3.3"))
    }
}
