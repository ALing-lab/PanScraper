package com.tamu.pan123.v034

import com.tamu.pan123.data.update.ReleaseInfo
import com.tamu.pan123.data.update.UpdateChecker
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** 审查 H3 / M3：更新包只从本仓库的 GitHub 发布页下载，校验大小、SHA-256、签名和包名。 */
class UpdateVerifyTest {
    private val srv = MockWebServer()
    private lateinit var dir: File
    private val G = "https://github.com/ALing-lab/PanScraper/releases/download"

    @Before fun up() {
        srv.start()
        dir = Files.createTempDirectory("upd").toFile()
        // 只放行 MockWebServer 自己的主机名 + 端口（模拟 GitHub）
        UpdateChecker.testAllowUrl = { it.host == srv.hostName && it.port == srv.port }
    }
    @After fun down() { srv.shutdown(); UpdateChecker.testAllowUrl = null; dir.deleteRecursively() }

    @Test fun onlyRepoHttpsAssetsAreTrusted() {
        UpdateChecker.testAllowUrl = null
        assertTrue(UpdateChecker.isTrustedAssetUrl("$G/v0.3.4/PanScraper-v0.3.4.apk"))
        assertTrue(UpdateChecker.isTrustedAssetUrl("https://objects.githubusercontent.com/github-production-release-asset/x"))
        assertTrue(UpdateChecker.isTrustedAssetUrl("https://release-assets.githubusercontent.com/x"))
        assertFalse(UpdateChecker.isTrustedAssetUrl("http://github.com/ALing-lab/PanScraper/releases/download/v1/a.apk"))
        assertFalse(UpdateChecker.isTrustedAssetUrl("https://github.com/evil/PanScraper/releases/download/v1/a.apk"))
        assertFalse(UpdateChecker.isTrustedAssetUrl("https://github.com.evil.com/ALing-lab/PanScraper/releases/download/v1/a.apk"))
        assertFalse(UpdateChecker.isTrustedAssetUrl("https://evil.example/ALing-lab/PanScraper/releases/download/v1/a.apk"))
        assertFalse(UpdateChecker.isTrustedAssetUrl(null))
    }

    @Test fun parseSkipsUntrustedApkAndReadsSha() {
        UpdateChecker.testAllowUrl = null
        val sha = "ab".repeat(32)
        val body = """{"tag_name":"v0.3.9","body":"- 修复\nSHA-256 (PanScraper-v0.3.9.apk): $sha",
          "assets":[{"name":"PanScraper-v0.3.9.apk","browser_download_url":"https://evil.example/PanScraper-v0.3.9.apk","size":10},
                    {"name":"PanScraper-v0.3.9-real.apk","browser_download_url":"$G/v0.3.9/PanScraper-v0.3.9-real.apk","size":20}]}"""
        val r = UpdateChecker.parse(body)
        assertEquals("PanScraper-v0.3.9-real.apk", r.apkName)
        assertEquals(20L, r.apkSize)
        assertEquals(sha, UpdateChecker.sha256FromNotes("SHA-256 (PanScraper-v0.3.9.apk): $sha", "PanScraper-v0.3.9.apk"))
        assertNull(UpdateChecker.sha256FromNotes("没有校验值", "x.apk"))
        // 只有不可信的资源 → 没有 APK（界面会提示「手动更新」）
        val bad = UpdateChecker.parse("""{"tag_name":"v1","assets":[{"name":"a.apk","browser_download_url":"https://evil.example/a.apk","size":1}]}""")
        assertNull(bad.apkUrl)
    }

    private fun rel(path: String, size: Long, sha: String? = null, shaUrl: String? = null) =
        ReleaseInfo("9.9.9", "t", "", "p", srv.url(path).toString(), "PanScraper-v9.9.9.apk", size, sha, shaUrl)

    private fun bytes(n: Int) = ByteArray(n) { (it % 251).toByte() }

    @Test fun downloadOkWhenSizeAndShaMatch() = runBlocking {
        val data = bytes(5000)
        srv.enqueue(MockResponse().setBody(Buffer().write(data)))
        val f = File.createTempFile("sha", ".bin").apply { writeBytes(data) }
        val sha = UpdateChecker.sha256Of(f)
        val out = UpdateChecker.download(rel("/a.apk", 5000, sha), dir) {}
        assertEquals(5000L, out.length())
    }

    @Test fun downloadRejectsWrongSize() = runBlocking {
        srv.enqueue(MockResponse().setBody(Buffer().write(bytes(4000))))
        try { UpdateChecker.download(rel("/a.apk", 5000), dir) {}; fail() } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("大小不对"))
        }
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".apk") })
    }

    @Test fun downloadRejectsShaMismatchFromAsset() = runBlocking {
        srv.enqueue(MockResponse().setBody(Buffer().write(bytes(100))))
        srv.enqueue(MockResponse().setBody("${"0".repeat(64)}  PanScraper-v9.9.9.apk\n"))
        try { UpdateChecker.download(rel("/a.apk", 100, shaUrl = srv.url("/a.apk.sha256").toString()), dir) {}; fail() } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("SHA-256"))
        }
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".apk") })
    }

    @Test fun downloadRefusesRedirectToOtherHost() = runBlocking {
        srv.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/a.apk"))
        try { UpdateChecker.download(rel("/a.apk", 100), dir) {}; fail() } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("非 GitHub"))
        }
        assertEquals(1, srv.requestCount) // 没有去连 evil.example
    }

    @Test fun downloadRefusesUntrustedUrlAndUnknownSize() = runBlocking {
        UpdateChecker.testAllowUrl = null
        try { UpdateChecker.download(ReleaseInfo("1", "", "", "", "https://evil.example/a.apk", "a.apk", 10), dir) {}; fail() } catch (e: IOException) {
            assertTrue(e.message!!.contains("拒绝下载"))
        }
        try { UpdateChecker.download(ReleaseInfo("1", "", "", "", "$G/v1/a.apk", "a.apk", 0), dir) {}; fail() } catch (e: IOException) {
            assertTrue(e.message!!.contains("大小"))
        }
        assertEquals(0, srv.requestCount)
    }

    @Test fun signerAndPackageMustMatchInstalledApp() {
        val mine = setOf("aa"); val other = setOf("bb")
        assertNull(UpdateChecker.checkSameApp("com.tamu.pan123scraper", mine, "com.tamu.pan123scraper", mine))
        assertTrue(UpdateChecker.checkSameApp("com.tamu.pan123scraper", other, "com.tamu.pan123scraper", mine)!!.contains("签名"))
        assertTrue(UpdateChecker.checkSameApp("com.evil", mine, "com.tamu.pan123scraper", mine)!!.contains("包名"))
        assertTrue(UpdateChecker.checkSameApp(null, mine, "com.tamu.pan123scraper", mine)!!.contains("无法解析"))
        assertTrue(UpdateChecker.checkSameApp("com.tamu.pan123scraper", emptySet(), "com.tamu.pan123scraper", mine)!!.contains("没有有效签名"))
        assertEquals(UpdateChecker.certDigests(listOf(byteArrayOf(1, 2))), UpdateChecker.certDigests(listOf(byteArrayOf(1, 2))))
    }
}
