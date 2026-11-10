package com.tamu.pan123.data.scrape

import com.tamu.pan123.data.AppDirs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 预刮削计划落盘；内存里只保留当前计划的轻量引用。 */
object PlanStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true; isLenient = true }
    private val _currentPath = MutableStateFlow<String?>(null)
    private val _summary = MutableStateFlow<PlanSummary?>(null)
    val currentPath: StateFlow<String?> = _currentPath.asStateFlow()
    val summary: StateFlow<PlanSummary?> = _summary.asStateFlow()

    data class PlanSummary(
        val path: String,
        val createdAt: String,
        val groups: Int,
        val actions: Int,
        val unmatched: Int,
        val stats: PlanStats,
    )

    fun latestFile(): File? =
        AppDirs.plans.listFiles()?.filter { it.name.startsWith("scrape_") && it.name.endsWith(".json") }
            ?.maxByOrNull { it.lastModified() }

    /** 同一时间只允许一个读改写（执行引擎标记完成 + 预览勾选可能同时发生，避免互相覆盖）。 */
    private val lock = Any()

    fun save(plan: ScrapePlan, name: String = "scrape_${System.currentTimeMillis()}.json"): File = synchronized(lock) {
        AppDirs.plans.mkdirs()
        val file = File(AppDirs.plans, name)
        com.tamu.pan123.data.io.AtomicFiles.writeText(file, json.encodeToString(plan))
        // 另存一份 current 指针（原子写）
        com.tamu.pan123.data.io.AtomicFiles.writeText(File(AppDirs.plans, "current.txt"), file.name)
        _currentPath.value = file.absolutePath
        _summary.value = PlanSummary(file.absolutePath, plan.createdAt, plan.groups.size, plan.actions.size,
            plan.stats.unmatched, plan.stats)
        file
    }

    fun load(file: File = latestFile() ?: error("没有计划文件")): ScrapePlan = synchronized(lock) {
        val plan = json.decodeFromString<ScrapePlan>(file.readText())
        _currentPath.value = file.absolutePath
        _summary.value = PlanSummary(file.absolutePath, plan.createdAt, plan.groups.size, plan.actions.size,
            plan.stats.unmatched, plan.stats)
        plan
    }

    fun loadOrNull(): ScrapePlan? = latestFile()?.let { runCatching { load(it) }.getOrNull() }

    fun reset() {
        _currentPath.value = null
        _summary.value = null
    }

    fun update(transform: (ScrapePlan) -> ScrapePlan): ScrapePlan = synchronized(lock) {
        val file = latestFile() ?: error("没有计划文件")
        val updated = transform(load(file))
        com.tamu.pan123.data.io.AtomicFiles.writeText(file, json.encodeToString(updated))
        _summary.value = PlanSummary(file.absolutePath, updated.createdAt, updated.groups.size, updated.actions.size,
            updated.stats.unmatched, updated.stats)
        updated
    }

    fun nowLabel(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        sdf.timeZone = TimeZone.getTimeZone("America/Los_Angeles")
        return sdf.format(Date()) + " PT"
    }
}
