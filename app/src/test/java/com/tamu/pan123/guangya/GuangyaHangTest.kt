package com.tamu.pan123.guangya

import com.tamu.pan123.data.guangya.GuangyaApiException
import com.tamu.pan123.data.guangya.GuangyaDrive
import com.tamu.pan123.data.guangya.GyCreds
import com.tamu.pan123.data.net.NetLog
import com.tamu.pan123.data.scrape.Scanner
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 复现 v0.2.0 真机「扫描中…」卡死：
 * - 请求不返回（无超时上限）/ 分页参数不生效导致的死循环
 * - 扫描出错或取消后 UI 状态停留在 scan、取消按钮无效
 */
class GuangyaHangTest {
    private val server = MockWebServer()
    private val listCalls = AtomicInteger(0)
    /** list 的行为：hang / samePage / endless / ok */
    @Volatile private var mode = "ok"
    @Volatile private var refreshStatus = 200

    private fun item(id: String, name: String, dir: Boolean) = JSONObject()
        .put("fileId", id).put("fileName", name).put("resType", if (dir) 2 else 1).put("fileSize", 10)

    private fun ok(data: JSONObject) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("code", 0).put("msg", "success").put("data", data).toString())

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.endsWith("/v1/auth/token")) {
                    return if (refreshStatus == 200) MockResponse().setBody(JSONObject()
                        .put("access_token", "AT-2").put("refresh_token", "RT-2").put("expires_in", 3600).toString())
                    else MockResponse().setResponseCode(refreshStatus).setBody("{\"error\":\"invalid_grant\"}")
                }
                if (!path.endsWith("/file/get_file_list")) return MockResponse().setResponseCode(404)
                val n = listCalls.incrementAndGet()
                val page = JSONObject(request.body.readUtf8()).optInt("page")
                return when (mode) {
                    "hang" -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    // 服务器忽略 page：每次都返回同样的满页，total 缺失
                    "samePage" -> ok(JSONObject().put("list", JSONArray().apply {
                        repeat(GuangyaDrive.PAGE_SIZE) { put(item("F$it", "f$it.mkv", false)) }
                    }))
                    // 每页都是新条目，永不结束
                    "endless" -> ok(JSONObject().put("list", JSONArray().apply {
                        repeat(GuangyaDrive.PAGE_SIZE) { put(item("P${page}_$it", "p$page-$it.mkv", false)) }
                    }))
                    else -> ok(JSONObject().put("total", 1).put("list", JSONArray().put(item("V1", "Inception.2010.1080p.mkv", false))))
                }.also { if (n > 10_000) error("runaway") }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        Scanner.cancel()
        runCatching { server.shutdown() }
    }

    private val base get() = server.url("/").toString().trimEnd('/')

    private fun drive(
        creds: GyCreds = GyCreds("AT-1", "RT-1"),
        callTimeoutMs: Long = 30_000,
        maxPages: Int = 300,
        apiBase: String = base,
    ) = GuangyaDrive(
        accountId = "gy-hang", store = MemTokenStore(creds), apiBase = apiBase, accountBase = "$base/acct",
        pathIntervalMs = 0, pollIntervalMs = 0, callTimeoutMs = callTimeoutMs, maxPages = maxPages,
        retryDelaysMs = listOf(50),
    )

    @Test
    fun hungRequestTimesOutWithChineseError() = runBlocking {
        mode = "hang"
        val t0 = System.currentTimeMillis()
        try {
            drive(callTimeoutMs = 800).list("1967025791566299206")
            fail("should time out")
        } catch (e: java.io.IOException) {
            assertTrue(e.message, e.message!!.contains("超时"))
        }
        // 1 次 + 1 次重试，每次 ≤0.8s
        assertTrue(System.currentTimeMillis() - t0 < 5_000)
        assertEquals(2, listCalls.get())
    }

    @Test
    fun pageIgnoredByServerDoesNotLoopForever() = runBlocking {
        mode = "samePage"
        val out = drive().list("1967025791566299206")
        assertEquals(GuangyaDrive.PAGE_SIZE, out.size) // 去重，不会重复累加
        assertEquals(2, listCalls.get()) // 第 2 页与第 1 页相同 → 停止
        assertTrue(NetLog.recent().any { it.contains("重复") })
    }

    @Test
    fun maxPagesGuardThrows() = runBlocking {
        mode = "endless"
        try {
            drive(maxPages = 5).list("x")
            fail("should stop")
        } catch (e: GuangyaApiException) {
            assertTrue(e.message, e.message!!.contains("超过 5 页"))
        }
        assertEquals(5, listCalls.get())
    }

    @Test
    fun connectFailureSurfacesImmediately() = runBlocking {
        val t0 = System.currentTimeMillis()
        try {
            drive(apiBase = "http://127.0.0.1:1").list("x")
            fail("should fail")
        } catch (e: java.io.IOException) {
            assertTrue(e.message, e.message!!.contains("网络无法连接光鸭服务器"))
        }
        assertTrue(System.currentTimeMillis() - t0 < 3_000) // 不重试
    }

    @Test
    fun failedRefreshSaysLoginExpired() = runBlocking {
        refreshStatus = 400
        val expired = GyCreds("AT-OLD", "RT-OLD", expiresAt = System.currentTimeMillis() - 1000)
        try {
            drive(creds = expired).list("x")
            fail("should fail")
        } catch (e: GuangyaApiException) {
            assertTrue(e.message, e.message!!.contains("登录已过期，请重新登录"))
        }
        assertEquals(0, listCalls.get())
    }

    @Test
    fun cancelWhileRequestHangsResetsUiAndStopsJob() {
        mode = "hang"
        assertTrue(Scanner.start(listOf("1967025791566299206" to "刮削测试/盗梦空间2010"), false, drive(callTimeoutMs = 60_000), preflightTmdb = false))
        assertEquals("scan", Scanner.progress.value.phase)
        val deadline = System.currentTimeMillis() + 5_000
        while (listCalls.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(1, listCalls.get())
        Thread.sleep(200)
        assertTrue(Scanner.isRunning)
        assertNotNull(NetLog.activity.value.inflight)

        Scanner.cancel()
        // UI 立即复位
        assertEquals("cancelled", Scanner.progress.value.phase)
        // 正在等待的请求被 call.cancel() 中断，作业在 1 秒内结束（远小于 60 秒超时）
        val t0 = System.currentTimeMillis()
        while (Scanner.isRunning && System.currentTimeMillis() - t0 < 3_000) Thread.sleep(20)
        assertFalse("scan job still running after cancel", Scanner.isRunning)
        assertTrue(System.currentTimeMillis() - t0 < 1_500)
        assertEquals("cancelled", Scanner.progress.value.phase)
        // 可以马上开始新的扫描
        mode = "hang"
        assertTrue(Scanner.start(listOf("x" to "x"), false, drive(callTimeoutMs = 60_000), preflightTmdb = false))
        Scanner.cancel()
    }

    @Test
    fun scanErrorLeavesErrorStateNotStuckScanning() {
        mode = "hang"
        assertTrue(Scanner.start(listOf("1967025791566299206" to "刮削测试/盗梦空间2010"), false, drive(callTimeoutMs = 500), preflightTmdb = false))
        val t0 = System.currentTimeMillis()
        while (Scanner.isRunning && System.currentTimeMillis() - t0 < 5_000) Thread.sleep(20)
        assertFalse(Scanner.isRunning)
        val p = Scanner.progress.value
        assertEquals("error", p.phase)
        assertTrue(p.error, p.error!!.contains("超时"))
    }

    @Test
    fun netLogNeverContainsSecrets() {
        val s = NetLog.sanitize("GET /3/search?api_key=abcdef123456&query=x Authorization: Bearer eyJhbGciOi.abc.def cookie=UID=1; CID=2 access_token\":\"tok123\"")
        assertFalse(s, s.contains("abcdef123456"))
        assertFalse(s, s.contains("eyJhbGciOi"))
        assertFalse(s, s.contains("UID=1"))
        assertFalse(s, s.contains("tok123"))
    }
}
