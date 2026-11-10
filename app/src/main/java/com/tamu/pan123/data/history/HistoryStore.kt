package com.tamu.pan123.data.history

import com.tamu.pan123.data.AppDirs
import com.tamu.pan123.data.scrape.PlanAction
import com.tamu.pan123.data.scrape.ScrapePlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** 一条执行过的操作（只存名字和 ID，不存海报 / NFO 内容）。 */
@Serializable
data class HistoryItem(
    val op: String,
    val folder: String = "",
    val from: String = "",
    val to: String = "",
    val tmdbId: Int? = null,
    val at: Long = 0,
)

/** 整理过的片子文件夹：下次扫描直接用这里的 TMDB 条目，不再匹配；视频都在 [files] 里就整个跳过。 */
@Serializable
data class HistoryGroup(
    val folderId: String,
    val folderName: String,
    val kind: String,
    val tmdbId: Int,
    val title: String,
    val year: String = "",
    val files: List<String> = emptyList(),
)

@Serializable
data class HistoryRecord(
    val id: String,
    val at: Long,
    val accountId: String,
    val provider: String,
    /** 扫描的目录（根目录名）。 */
    val dir: String = "",
    val title: String = "",
    val items: List<HistoryItem> = emptyList(),
    val groups: List<HistoryGroup> = emptyList(),
)

@Serializable
private data class HistoryFile(val enabled: Boolean = true, val records: List<HistoryRecord> = emptyList())

/**
 * 历史记录 / 记忆（设置里可开关，默认开）。执行成功的项目记下来，之后扫描时跳过，不重复匹配 TMDB。
 * 只存紧凑信息：网盘、目录、文件夹、文件名、TMDB ID、操作类型、时间。
 */
object HistoryStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(HistoryFile())
    private var loaded = false
    private const val MAX_RECORDS = 300

    private val _records = MutableStateFlow<List<HistoryRecord>>(emptyList())
    val records: StateFlow<List<HistoryRecord>> = _records.asStateFlow()
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** 测试可替换目录。 */
    @Volatile var dirOverride: File? = null
    private fun file(): File? = runCatching { File(dirOverride ?: File(AppDirs.rootDir(), "history"), "history.json") }.getOrNull()

    @Synchronized
    private fun ensure() {
        if (loaded) return
        loaded = true
        val f = file() ?: return
        if (f.isFile) runCatching { json.decodeFromString(HistoryFile.serializer(), f.readText()) }.getOrNull()?.let { publish(it, save = false) }
    }

    @Synchronized
    fun reload() { loaded = false; _state.value = HistoryFile(); ensure(); publish(_state.value, save = false) }

    private fun publish(h: HistoryFile, save: Boolean = true) {
        val sorted = h.copy(records = h.records.sortedByDescending { it.at }.take(MAX_RECORDS))
        _state.value = sorted
        _records.value = sorted.records
        _enabled.value = sorted.enabled
        if (save) file()?.let { f ->
            runCatching {
                f.parentFile?.mkdirs()
                com.tamu.pan123.data.io.AtomicFiles.writeText(f, json.encodeToString(HistoryFile.serializer(), sorted))
            }
        }
    }

    fun load(): List<HistoryRecord> { ensure(); return _records.value }
    fun isEnabled(): Boolean { ensure(); return _state.value.enabled }

    @Synchronized fun setEnabled(on: Boolean) { ensure(); publish(_state.value.copy(enabled = on)) }
    @Synchronized fun delete(id: String) { ensure(); publish(_state.value.copy(records = _state.value.records.filter { it.id != id })) }
    @Synchronized fun clear() { ensure(); publish(_state.value.copy(records = emptyList())) }
    @Synchronized fun add(r: HistoryRecord) { ensure(); publish(_state.value.copy(records = listOf(r) + _state.value.records)) }

    /** 扫描时查询：这个文件夹是不是整理过（开关关闭时一律返回 null）。 */
    fun remembered(accountId: String, pathIds: Boolean, folderId: String, folderName: String): HistoryGroup? {
        ensure()
        if (!_state.value.enabled) return null
        for (r in _state.value.records) {
            if (r.accountId != accountId) continue
            val g = r.groups.firstOrNull { g ->
                g.folderName == folderName && (g.folderId == folderId || pathIds)
            } ?: continue
            // 同一文件夹多次执行：合并所有记录里的文件名
            val files = _state.value.records.filter { it.accountId == accountId }
                .flatMap { rr -> rr.groups.filter { it.folderName == folderName && it.tmdbId == g.tmdbId }.flatMap { it.files } }
                .distinct()
            return g.copy(files = files)
        }
        return null
    }

    /** 由执行引擎在一次执行结束时调用：只记录这次成功的项目。 */
    fun recordRun(plan: ScrapePlan, actions: Map<String, PlanAction>, okIds: List<String>, now: Long = System.currentTimeMillis()): HistoryRecord? {
        if (!isEnabled() || okIds.isEmpty()) return null
        val ok = okIds.mapNotNull { actions[it] }
        val groupsById = plan.groups.associateBy { it.id }
        val items = ok.map { a ->
            val g = groupsById[a.groupId]
            HistoryItem(a.op, folder = g?.folderNewName ?: g?.folderName ?: "", from = a.oldName,
                to = if (a.op == "move") (a.destLabel ?: a.newName) else a.newName, tmdbId = a.tmdbId ?: g?.tmdbId, at = now)
        }
        val touched = ok.map { it.groupId }.toSet()
        val hg = touched.mapNotNull { gid ->
            val g = groupsById[gid] ?: return@mapNotNull null
            val fid = g.folderId ?: return@mapNotNull null
            val tid = g.tmdbId ?: return@mapNotNull null
            if (g.kind !in setOf("tv", "movie")) return@mapNotNull null
            val acts = actions.values.filter { it.groupId == gid }
            val renamed = acts.firstOrNull { it.op == "rename" && it.kind == "folder" && it.fileId == fid }
            val finalName = if (renamed == null) g.folderName else if (renamed.done || renamed.id in okIds) renamed.newName else g.folderName
            val videos = acts.filter { (it.op == "rename" || it.op == "skip") && it.kind == "file" && it.tmdbId == tid && (it.op == "skip" || it.done || it.id in okIds) }
                .map { it.newName.ifBlank { it.oldName } }
            HistoryGroup(fid, finalName, g.kind, tid, g.title, g.year, videos)
        }
        val title = plan.groups.filter { it.id in touched && it.title.isNotBlank() }.map { it.title }.distinct().let {
            when (it.size) { 0 -> "执行了 ${ok.size} 项"; 1 -> it[0]; else -> "${it[0]} 等 ${it.size} 部" }
        }
        val r = HistoryRecord(UUID.randomUUID().toString().take(8), now, plan.accountId, plan.provider,
            dir = plan.roots.joinToString("、") { it.name }, title = title, items = items, groups = hg)
        add(r)
        return r
    }
}
