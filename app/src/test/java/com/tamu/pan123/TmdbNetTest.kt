package com.tamu.pan123

import com.tamu.pan123.data.AppDirs
import com.tamu.pan123.data.SecureStore
import com.tamu.pan123.data.tmdb.TmdbClient
import com.tamu.pan123.data.tmdb.TmdbNet
import com.tamu.pan123.data.tmdb.TmdbNetConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 复现真机：大陆网络连不上 api.themoviedb.org → 扫描应立即报中文错误并熔断，而不是每个文件夹都等一次超时。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TmdbNetTest {
    @Before fun setup() {
        val ctx = RuntimeEnvironment.getApplication()
        AppDirs.init(ctx); SecureStore.init(ctx)
        SecureStore.saveTmdbKey("fake-key-for-test")
        AppDirs.clearAll()
        TmdbClient.resetFailures()
    }

    @After fun tearDown() {
        TmdbNet.apply(TmdbNetConfig()); TmdbClient.resetFailures()
        TmdbNet.tryOfficialImage = true; TmdbNet.preferredImageBase = null
    }

    /** 真机缩略图全空白：主图片地址被劫持（返回网页）/ 连不上时，必须换备用地址，并记住能用的那个。 */
    @Test fun imageFallsBackOnHijackAndDeadHost() = runBlocking {
        val hijack = MockWebServer(); hijack.start()
        val mirror = MockWebServer(); mirror.start()
        repeat(3) { hijack.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>blocked</html>")) }
        repeat(3) { mirror.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody("JPEGDATA")) }
        TmdbNet.tryOfficialImage = false
        val main = hijack.url("/").toString().trimEnd('/')
        val fb = mirror.url("/").toString().trimEnd('/')
        TmdbNet.apply(TmdbNetConfig(imageBase = main, imageFallback = fb, doh = false))
        val url = TmdbNet.imageUrl("https://image.tmdb.org/t/p/w185/x.jpg")
        assertTrue(url, url.startsWith(main))
        val body = TmdbNet.client().newCall(okhttp3.Request.Builder().url(url).build()).execute().use { it.body!!.string() }
        assertEquals("JPEGDATA", body)
        assertEquals("/t/p/w185/x.jpg", mirror.takeRequest().path)
        // 第二张图直接走备用地址，不再先撞主地址
        val before = hijack.requestCount
        TmdbNet.client().newCall(okhttp3.Request.Builder().url(url).build()).execute().close()
        assertEquals(before, hijack.requestCount)
        // 测试按钮也报告实际使用的地址
        TmdbNet.preferredImageBase = null
        val msg = TmdbClient.testImage().getOrThrow()
        assertTrue(msg, msg.contains("图片正常") && msg.contains(mirror.hostName))
        hijack.shutdown(); mirror.shutdown()
        // 镜像连不上：主地址关掉也要给出中文原因
        TmdbNet.apply(TmdbNetConfig(imageBase = "http://127.0.0.1:1", doh = false))
        val m = TmdbClient.testImage().exceptionOrNull()!!.message!!
        assertTrue(m, m.contains("所有图片地址都连不上") && m.contains("设置 › TMDB 网络"))
    }

    @Test fun forwardPrefixMirror() {
        TmdbNet.tryOfficialImage = true
        val c = TmdbNetConfig(imageFallback = "https://wsrv.nl/?url=").normalized()
        val cands = TmdbNet.imageCandidates("https://image.tmdb.org/t/p/w185/x.jpg".let { okhttp3.HttpUrl.Companion.run { it.toHttpUrl() } }, c)
        assertEquals("https://image.tmdb.org/t/p/w185/x.jpg", cands[0].toString())
        assertEquals("https://media.themoviedb.org/t/p/w185/x.jpg", cands[1].toString())
        assertEquals("wsrv.nl", cands[2].host)
        assertEquals("https://image.tmdb.org/t/p/w185/x.jpg", cands[2].queryParameter("url"))
    }

    @Test fun normalizesBases() {
        val n = TmdbNetConfig(apiBase = "tmdb.example.com/", imageBase = "https://img.example.com/t/p/").normalized()
        assertEquals("https://tmdb.example.com/3", n.apiBase)
        assertEquals("https://img.example.com", n.imageBase)
        TmdbNet.apply(n)
        assertEquals("https://img.example.com/t/p/w780/a.jpg", TmdbNet.imageUrl("https://image.tmdb.org/t/p/w780/a.jpg"))
    }

    @Test fun connectFailureFailsFastWithChineseHelp() = runBlocking {
        TmdbNet.apply(TmdbNetConfig(apiBase = "http://127.0.0.1:1/3", doh = false))
        try { TmdbClient.searchMovieRaw("盗梦空间", "2010"); fail() } catch (e: java.io.IOException) {
            assertTrue(e.message, e.message!!.contains("无法连接 TMDB"))
            assertTrue(e.message, e.message!!.contains("TMDB 网络"))
            assertTrue(e.message, e.message!!.contains("127.0.0.1"))
        }
        val t0 = System.currentTimeMillis()
        try { TmdbClient.searchMovieRaw("Inception", "2010"); fail() } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("无法连接 TMDB"))
        }
        assertTrue("second call must short-circuit", System.currentTimeMillis() - t0 < 200)
        assertTrue(TmdbClient.test().isFailure)
    }

    @Test fun customBaseUsedAndTestReportsOk() = runBlocking {
        val srv = MockWebServer(); srv.start()
        try {
            srv.enqueue(MockResponse().setBody("{\"images\":{}}"))
            TmdbNet.apply(TmdbNetConfig(apiBase = srv.url("/proxy/3").toString(), doh = false))
            val r = TmdbClient.test()
            assertTrue(r.exceptionOrNull()?.message, r.isSuccess)
            val req = srv.takeRequest()
            assertTrue(req.path!!.startsWith("/proxy/3/configuration"))
        } finally { srv.shutdown() }
    }
}
