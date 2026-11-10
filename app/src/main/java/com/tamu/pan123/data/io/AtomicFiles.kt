package com.tamu.pan123.data.io

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** 原子写文件：先写同目录的临时文件并 fsync，再 rename 覆盖；rename 失败时抛错（不会悄悄留下半截文件或旧内容）。 */
object AtomicFiles {
    fun writeBytes(target: File, data: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "." + target.name + ".tmp-" + System.nanoTime())
        try {
            FileOutputStream(tmp).use { os -> os.write(data); os.flush(); runCatching { os.fd.sync() } }
            if (!tmp.renameTo(target)) {
                // 个别文件系统不允许 rename 覆盖已有文件：先删旧的再改名
                if (target.exists() && !target.delete()) throw IOException("无法替换文件 ${target.name}")
                if (!tmp.renameTo(target)) throw IOException("无法保存文件 ${target.name}")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun writeText(target: File, text: String) = writeBytes(target, text.toByteArray(Charsets.UTF_8))

    /** rename 并检查结果；失败抛 IOException。 */
    fun renameOrThrow(from: File, to: File) {
        if (from.renameTo(to)) return
        if (to.exists() && to.delete() && from.renameTo(to)) return
        throw IOException("无法保存文件 ${to.name}")
    }
}
