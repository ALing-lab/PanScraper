package com.tamu.pan123.data.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 网络活动记录 + 可选的脱敏调试日志。
 *
 * 只记录：时间、网盘、方法、URL 路径（不含 host 之外的查询参数）、状态码、耗时、条目数、错误摘要。
 * 绝不记录：请求头（Authorization / Cookie）、查询参数（TMDB api_key 等）、请求体、响应体。
 */
object NetLog {
    data class Activity(
        val requests: Int = 0,
        val inflight: String? = null,
        val inflightSince: Long = 0,
        val last: String? = null,
        val lastError: String? = null,
        val retryNote: String? = null,
        val retryAt: Long = 0,
        val lastActivityAt: Long = 0,
    )

    private val _activity = MutableStateFlow(Activity())
    val activity: StateFlow<Activity> = _activity.asStateFlow()

    /** 是否把日志写入文件（设置里的「调试日志」开关）。内存里始终保留最近的记录。 */
    @Volatile var fileEnabled: Boolean = false
    @Volatile var dir: File? = null

    private const val RING = 400
    private val ring = ArrayDeque<String>()
    private val inflightCount = AtomicInteger(0)
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun logFile(): File? = dir?.let { File(it, "net.log") }

    /** 开始新一轮（如一次扫描）：清零计数，保留日志。 */
    fun resetActivity() {
        _activity.value = Activity(lastActivityAt = System.currentTimeMillis())
    }

    /** 截图 / 预览用：直接设置活动状态。 */
    fun previewActivity(a: Activity) { _activity.value = a }

    fun recent(): List<String> = synchronized(ring) { ring.toList() }

    fun clear() {
        synchronized(ring) { ring.clear() }
        runCatching { logFile()?.delete() }
    }

    /** 记录一行（自动脱敏）。 */
    fun line(provider: String, msg: String) {
        val l = "${synchronized(fmt) { fmt.format(Date()) }} [$provider] ${sanitize(msg)}"
        synchronized(ring) {
            ring.addLast(l)
            while (ring.size > RING) ring.removeFirst()
        }
        if (fileEnabled) runCatching {
            val f = logFile() ?: return@runCatching
            f.parentFile?.mkdirs()
            if (f.length() > 2_000_000) {
                val old = File(f.parentFile, "net.1.log")
                old.delete()
                // 改名失败（极少见）时直接清空，避免日志无限增长
                if (!f.renameTo(old)) f.writeText("")
            }
            f.appendText(l + "\n")
        }
    }

    /** 业务层补充信息（条目数 / 页码等）。 */
    fun note(provider: String, msg: String) {
        line(provider, msg)
        _activity.value = _activity.value.copy(last = sanitize("$provider $msg"), lastActivityAt = System.currentTimeMillis())
    }

    fun error(provider: String, msg: String) {
        line(provider, "ERROR $msg")
        _activity.value = _activity.value.copy(lastError = sanitize("$provider：$msg"), lastActivityAt = System.currentTimeMillis())
    }

    fun retrying(provider: String, note: String, waitMs: Long) {
        line(provider, "RETRY $note in ${waitMs}ms")
        _activity.value = _activity.value.copy(retryNote = sanitize(note), retryAt = System.currentTimeMillis() + waitMs)
    }

    fun clearRetry() {
        if (_activity.value.retryNote != null) _activity.value = _activity.value.copy(retryNote = null, retryAt = 0)
    }

    private val SECRET_KV = Regex("""(?i)(api_key|access_token|refresh_token|token|cookie|authorization|password|passwd|sign|signature|ak|sk|security_token)(["']?\s*[=:]\s*["']?)[^\s&"',;}]+""")
    private val BEARER = Regex("""(?i)bearer\s+[A-Za-z0-9._\-+/=]+""")
    private val LONG_TOKEN = Regex("""[A-Za-z0-9_\-]{48,}""")

    fun sanitize(s: String): String = s
        .replace(BEARER, "Bearer ***")
        .replace(SECRET_KV) { it.groupValues[1] + it.groupValues[2] + "***" }
        .replace(LONG_TOKEN, "***")

    /** 只留路径：去掉查询参数和片段。 */
    fun pathOf(r: Request): String = r.url.host + r.url.encodedPath

    /** OkHttp 应用拦截器：记录每个请求的路径、状态码和耗时。 */
    fun interceptor(provider: String) = Interceptor { chain ->
        val req = chain.request()
        val label = "${req.method} ${pathOf(req)}"
        val t0 = System.currentTimeMillis()
        inflightCount.incrementAndGet()
        _activity.value = _activity.value.copy(
            requests = _activity.value.requests + 1,
            inflight = "$provider $label", inflightSince = t0, lastActivityAt = t0,
        )
        try {
            val resp = chain.proceed(req)
            val ms = System.currentTimeMillis() - t0
            line(provider, "$label -> ${resp.code} ${ms}ms")
            _activity.value = _activity.value.copy(
                inflight = if (inflightCount.decrementAndGet() > 0) _activity.value.inflight else null,
                last = "$provider $label → ${resp.code} · ${ms}ms",
                lastActivityAt = System.currentTimeMillis(),
            )
            resp
        } catch (e: IOException) {
            val ms = System.currentTimeMillis() - t0
            val why = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
            line(provider, "$label -> FAIL ${ms}ms $why")
            _activity.value = _activity.value.copy(
                inflight = if (inflightCount.decrementAndGet() > 0) _activity.value.inflight else null,
                lastError = sanitize("$provider $label：$why"),
                lastActivityAt = System.currentTimeMillis(),
            )
            throw e
        }
    }

    /** 导出：设备信息 + 文件日志（若有）+ 内存里最近的记录。 */
    fun exportTo(out: File, header: String): File {
        out.parentFile?.mkdirs()
        val sb = StringBuilder()
        sb.append(header).append("\n\n")
        val f = logFile()
        if (fileEnabled && f != null && f.isFile) {
            File(f.parentFile, "net.1.log").takeIf { it.isFile }?.let { sb.append(it.readText()) }
            sb.append(f.readText())
        } else {
            sb.append("（调试日志文件未开启，以下为内存中最近 ${recent().size} 条）\n")
            recent().forEach { sb.append(it).append('\n') }
        }
        val crashes = com.tamu.pan123.CrashReporter.recent(dir)
        if (crashes.isNotEmpty()) {
            sb.append("\n\n===== 最近的崩溃报告（${crashes.size}）=====\n")
            crashes.forEach { sb.append("\n--- ").append(it.name).append(" ---\n").append(it.readText()) }
        }
        out.writeText(sanitize(sb.toString()))
        return out
    }
}

/** 统一的网络超时：连接 15s、读写 40s、整个请求最多 [callSec] 秒（含 DNS / 重定向 / 读取响应体）。 */
fun OkHttpClient.Builder.standardTimeouts(callSec: Long = 60): OkHttpClient.Builder = this
    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(40, java.util.concurrent.TimeUnit.SECONDS)
    .writeTimeout(40, java.util.concurrent.TimeUnit.SECONDS)
    .callTimeout(callSec, java.util.concurrent.TimeUnit.SECONDS)

data class HttpResult(val code: Int, val body: ByteArray, val headers: okhttp3.Headers) {
    val text: String get() = String(body, Charsets.UTF_8)
    fun header(name: String): String? = headers[name]
}

/**
 * 可取消的请求：协程被取消时立即 call.cancel()，不会阻塞在 socket 上。
 * 响应体在 OkHttp 线程里读完再恢复协程。
 */
suspend fun OkHttpClient.fetch(req: Request): HttpResult = suspendCancellableCoroutine { cont ->
    val call = newCall(req)
    cont.invokeOnCancellation { runCatching { call.cancel() } }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val r = response.use { HttpResult(it.code, it.body?.bytes() ?: ByteArray(0), it.headers) }
                if (cont.isActive) cont.resume(r)
            } catch (e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    })
}
