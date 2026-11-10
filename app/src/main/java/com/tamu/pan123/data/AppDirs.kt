package com.tamu.pan123.data

import android.content.Context
import java.io.File

/** 应用数据全部落在专属外部目录，不堆内存。清理不碰 SecureStore。 */
object AppDirs {
    private lateinit var root: File
    lateinit var posters: File; private set
    lateinit var plans: File; private set
    lateinit var tmdbCache: File; private set
    lateinit var imgCache: File; private set
    lateinit var logs: File; private set
    lateinit var preview: File; private set

    data class Usage(
        val posters: Long,
        val plans: Long,
        val tmdbCache: Long,
        val imgCache: Long,
        val logs: Long,
        val preview: Long,
    ) {
        val total get() = posters + plans + tmdbCache + imgCache + logs + preview
    }

    fun init(context: Context) {
        root = context.getExternalFilesDir(null) ?: context.filesDir
        posters = File(root, "posters").also { it.mkdirs() }
        plans = File(root, "plans").also { it.mkdirs() }
        tmdbCache = File(root, "cache/tmdb").also { it.mkdirs() }
        imgCache = File(root, "cache/img").also { it.mkdirs() }
        logs = File(root, "logs").also { it.mkdirs() }
        preview = File(root, "preview").also { it.mkdirs() }
    }

    fun rootDir(): File = root

    fun sizeBytes(): Long = walkSize(root)

    fun usage(): Usage = Usage(
        posters = walkSize(posters),
        plans = walkSize(plans),
        tmdbCache = walkSize(tmdbCache),
        imgCache = walkSize(imgCache),
        logs = walkSize(logs),
        preview = walkSize(preview),
    )

    fun formatSize(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / (1024.0 * 1024))
        else -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
    }

    private fun walkSize(f: File): Long {
        if (!f.exists()) return 0
        if (f.isFile) return f.length()
        return f.listFiles()?.sumOf { walkSize(it) } ?: 0
    }

    /** 清理海报/计划/缓存/日志/预览；保留登录与 TMDB 密钥。返回清理前总字节数。 */
    fun clearAll(): Long {
        val before = sizeBytes()
        listOf(posters, plans, tmdbCache, imgCache, logs, preview).forEach { dir ->
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
        }
        return before
    }
}
