package com.tamu.pan123

import com.tamu.pan123.data.net.NetLog
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃记录：把堆栈（脱敏后）写到 logs/crash-时间.txt，「导出日志」时一并带上。
 * 只记录异常类型、消息和堆栈，不含请求、token、Cookie。
 */
object CrashReporter {
    private const val KEEP = 5

    fun install(dir: File) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write(dir, t, e) }
            prev?.uncaughtException(t, e)
        }
    }

    fun write(dir: File, t: Thread, e: Throwable): File {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val text = buildString {
            append(AppInfo.NAME + " ").append(BuildConfigInfo.VERSION).append(" 崩溃报告\n")
            append("时间：").append(stamp).append("\n")
            runCatching {
                append("设备：").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                append(" · Android ").append(android.os.Build.VERSION.RELEASE).append('\n')
            }
            append("线程：").append(t.name).append("\n\n")
            append(sw.toString())
        }
        val f = File(dir, "crash-$stamp.txt")
        f.writeText(NetLog.sanitize(text))
        dir.listFiles { x -> x.name.startsWith("crash-") }?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
        return f
    }

    fun recent(dir: File?): List<File> =
        dir?.listFiles { x -> x.name.startsWith("crash-") }?.sortedByDescending { it.name }?.take(3).orEmpty()
}
