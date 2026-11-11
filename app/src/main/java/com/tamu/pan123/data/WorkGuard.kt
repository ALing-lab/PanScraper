package com.tamu.pan123.data

/** 清理与网盘工作互斥；重新规划也不能与执行交错。 */
object WorkGuard {
    private val lock = Any()
    private var active = 0
    suspend fun <T> work(block: suspend () -> T): T {
        synchronized(lock) { check(active == 0) { "有扫描或执行任务尚未结束，请先结束任务" }; active++ }
        return try { block() } finally { synchronized(lock) { active-- } }
    }
    fun <T> idle(block: () -> T): T = synchronized(lock) {
        check(active == 0) { "有扫描或执行任务尚未结束，暂时不能清理数据" }
        block()
    }
}
