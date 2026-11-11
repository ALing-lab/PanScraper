package com.tamu.pan123.data.scrape

import com.tamu.pan123.data.naming.MediaParse
import com.tamu.pan123.data.naming.Naming
import com.tamu.pan123.data.drive.CloudDrive
import com.tamu.pan123.data.drive.CloudEntry
import com.tamu.pan123.data.drive.DriveRegistry
import com.tamu.pan123.data.tmdb.MatchInfo
import com.tamu.pan123.data.tmdb.TmdbClient
import com.tamu.pan123.data.net.NetLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 只读扫描：列目录 + TMDB 匹配 + 生成预刮削计划（落盘）。
 * 绝不调用 rename/move/mkdir/upload。
 */
object Scanner {
    data class Progress(
        /** idle / scan / done / error / cancelled */
        val phase: String = "idle",
        val message: String = "",
        val foldersDone: Int = 0,
        val foldersTotal: Int = 0,
        val filesSeen: Int = 0,
        val cancelled: Boolean = false,
        /** 当前步骤（读取目录 / 匹配 TMDB），便于看出卡在哪一步。 */
        val step: String = "",
        val startedAt: Long = 0,
        val runId: Long = 0,
        val error: String? = null,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /** 扫描在独立的后台作用域里运行：离开页面不会中断，取消按钮可以随时终止。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    @Volatile private var cancelFlag = false
    @Volatile private var runCounter = 0L

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * 立即取消：UI 马上回到「已取消」，正在进行的请求通过 call.cancel() 中断（在后台收尾）。
     */
    fun cancel() {
        cancelFlag = true
        job?.cancel(CancellationException("用户取消"))
        val cur = _progress.value
        if (cur.phase == "scan") _progress.value = cur.copy(phase = "cancelled", message = "已取消扫描", cancelled = true, step = "")
        NetLog.clearRetry()
    }

    /** 清除目标时调用：取消正在进行的扫描，并把状态恢复为空闲。 */
    fun reset() {
        cancel()
        _progress.value = Progress(runId = runCounter)
    }

    /** 从 UI 启动扫描（非阻塞）。已在扫描时返回 false。 */
    fun start(
        roots: List<Pair<String, String>>,
        fullDrive: Boolean,
        cloud: CloudDrive,
        preflightTmdb: Boolean = true,
    ): Boolean {
        // 同一时间只允许一个扫描：先取消旧的（旧作业的请求会被 call.cancel() 中断）
        job?.let { old -> if (old.isActive) old.cancel(CancellationException("新的扫描开始")) }
        cancelFlag = false
        // 先把状态切到 scan，避免按钮被连点
        _progress.value = Progress("scan", "开始扫描…", startedAt = System.currentTimeMillis(), runId = runCounter + 1)
        val prev = job
        job = scope.launch {
            prev?.join() // 等旧作业收尾（已取消，很快）
            try {
                scan(roots, fullDrive, cloud, preflightTmdb)
            } catch (_: CancellationException) {
                // cancel() 已经更新了状态
            } catch (e: Throwable) {
                val cur = _progress.value
                _progress.value = cur.copy(phase = "error", message = "扫描失败", error = e.message ?: e.javaClass.simpleName, step = "")
            }
        }
        return true
    }

    private fun prog(phase: String, message: String, foldersDone: Int = _progress.value.foldersDone,
                     foldersTotal: Int = _progress.value.foldersTotal, filesSeen: Int = _progress.value.filesSeen,
                     step: String = "") {
        val cur = _progress.value
        if (cancelFlag && phase == "scan") return // 取消后不要再把状态改回 scan
        _progress.value = cur.copy(phase = phase, message = message, foldersDone = foldersDone,
            foldersTotal = foldersTotal, filesSeen = filesSeen, cancelled = cancelFlag, step = step)
    }

    private fun step(text: String) {
        if (cancelFlag) return
        _progress.value = _progress.value.copy(step = text)
    }

    private suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        if (cancelFlag) throw CancellationException("用户取消")
    }

    private fun aid() = UUID.randomUUID().toString().take(8)

    /** 预览顶部计数（清理旧文件单独计数，不算进改名 / 建文件夹 / 移动）。 */
    fun computeStats(actions: List<PlanAction>, groups: List<PlanGroup>): PlanStats {
        val live = actions.filter { !it.done }
        val main = live.filter { it.kind != "cleanup" && it.op != "delete" }
        return PlanStats(
            rename = main.count { it.op == "rename" },
            mkdir = main.count { it.op == "mkdir" },
            move = main.count { it.op == "move" },
            artwork = main.count { it.op == "artwork" },
            skip = main.count { it.op == "skip" },
            unmatched = main.count { it.op == "unmatched" } + groups.count { it.kind == "unmatched" },
            groups = groups.size,
            cleanup = live.count { it.kind == "cleanup" && it.op == "move" },
            delete = live.count { it.op == "delete" },
        )
    }

    /** 文件夹名是中文标题，但匹配结果和它对不上（多半是按文件名的英文标题搜到的）。 */
    internal fun folderTitleMismatch(folderName: String, matchedTitle: String, matchedOriginal: String? = null): Boolean {
        if (Naming.folderDoneId(folderName) != null) return false
        val (t, _) = Naming.queryFromName(folderName)
        if (t.isBlank() || !Naming.CJK.containsMatchIn(t)) return false
        val ft = Naming.norm(t)
        if (ft.isEmpty()) return false
        return listOfNotNull(matchedTitle, matchedOriginal).none {
            val m = Naming.norm(it)
            m.isNotEmpty() && (m.contains(ft) || ft.contains(m))
        }
    }

    /**
     * 手动重新匹配：丢掉该分组原来的全部操作，按新的类型（电影 / 剧集）和 TMDB 条目从头重新规划，
     * 与正常扫描生成的操作一致（文件夹改名、季文件夹、移动、海报 / NFO、字幕）。
     * 即使文件夹已带 {tmdb-ID}（之前执行过错误匹配），也以手动选择为准。
     * 同时检测本应用以前生成、但与新匹配不符的文件，生成可选的「清理旧刮削文件」操作（默认不勾选，只移动不删除）。
     */
    suspend fun replanGroup(plan: ScrapePlan, groupId: String, info: MatchInfo, cloud: CloudDrive): ScrapePlan =
        scanMutex.withLock { Naming.withSnapshot { com.tamu.pan123.data.WorkGuard.work { replanGroupLocked(plan, groupId, info, cloud) } } }

    private suspend fun replanGroupLocked(plan: ScrapePlan, groupId: String, info: MatchInfo, cloud: CloudDrive): ScrapePlan =
        withContext(Dispatchers.IO) {
            val old = plan.groups.firstOrNull { it.id == groupId } ?: error("找不到分组")
            check(plan.actions.none { it.groupId == groupId && it.done }) { "该分组已有执行完成的操作，请重新扫描后再匹配" }
            drive = cloud
            listCache.clear()
            visited.clear()
            val folderId = old.folderId ?: return@withContext replanLooseLocked(plan, groupId, info, cloud)
            val oldActions = plan.actions.filter { it.groupId == groupId }
            val parentId = oldActions.firstOrNull { it.fileId == folderId && it.kind == "folder" }?.parentId
            val items = cloud.list(folderId)
            val folder = CloudEntry(fileId = folderId, fileName = old.folderName, isDir = true, parentFileId = parentId)
            val toMovie = info.kind == "movie"
            val seasonDirs = if (toMovie) items.filter { it.isDir && Naming.isSeasonDir(it.fileName) } else emptyList()
            val seasonKids = seasonDirs.associateWith { d -> cloud.list(d.fileId) }

            // 旧匹配留下的刮削产物：ID 或类型变了就视为过期
            val prevId = Naming.folderDoneId(folder.fileName) ?: old.tmdbId
            val prevKind = old.kind
            val changed = prevId != info.id || (prevKind in setOf("tv", "movie") && prevKind != info.kind)
            val stale = staleArtifacts(items, info.kind, changed)
            val staleNames = stale.map { it.fileName.lowercase() }.toSet()

            // 规划用的条目：去掉过期产物（这样会重新规划正确的海报 / NFO）；改成电影时把 Season 文件夹里的视频 / 字幕也纳入改名
            val planItems = items.filter { it.fileName.lowercase() !in staleNames && !(toMovie && it in seasonDirs) } +
                seasonKids.values.flatten().filter { !it.isDir && (isVideo(it) || isSub(it)) }

            val actions = mutableListOf<PlanAction>()
            val groups = mutableListOf<PlanGroup>()
            val flags = mutableListOf<String>()
            val gidHint = "${info.kind}-${info.id}-$folderId"
            val trash = items.firstOrNull { it.isDir && it.fileName == TRASH_DIR }
            val pre = mutableListOf<PlanAction>()
            val post = mutableListOf<PlanAction>()
            val needsCleanup = stale.isNotEmpty() || seasonDirs.isNotEmpty()
            var trashMk: String? = null
            if (needsCleanup && trash == null) {
                trashMk = aid()
                pre += PlanAction(trashMk, "mkdir", gidHint, parentId = folderId, newName = TRASH_DIR, kind = "cleanup",
                    selected = false, note = "清理旧刮削文件：回收目录（不会删除任何文件）")
            }
            fun toTrash(e: CloudEntry, note: String) = PlanAction(aid(), "move", gidHint, e.fileId, folderId, e.fileName, e.fileName,
                destParentId = trash?.fileId, destLabel = TRASH_DIR, kind = "cleanup", selected = false, note = note)
            stale.forEach { pre += toTrash(it, "清理旧刮削文件：旧匹配的 ${it.fileName}，移进 $TRASH_DIR") }

            planTitleFolder(folder, planItems, actions, groups, flags, mutableSetOf(), forced = info)
            flags.removeAll { it.contains("没有视频文件") || it.contains("不是季文件夹") }

            val taken = items.map { it.fileName.lowercase() }.toMutableSet()
            for ((d, kids) in seasonKids) {
                for (k in kids.filter { !it.isDir }) {
                    val ext = Naming.splitNameExt(k.fileName).second.lowercase()
                    if (ext !in Naming.VIDEO_EXT && ext !in Naming.SUB_EXT) continue
                    // 改名后的名字（若有）
                    val renamed = actions.firstOrNull { it.fileId == k.fileId && it.op == "rename" }?.newName ?: k.fileName
                    if (renamed.lowercase() in taken) {
                        flags += "清理：「${d.fileName}/${k.fileName}」与上层文件重名，未移出"
                        continue
                    }
                    taken += renamed.lowercase()
                    post += PlanAction(aid(), "move", gidHint, k.fileId, d.fileId, renamed, renamed,
                        destParentId = folderId, destLabel = folder.fileName, kind = "cleanup", selected = false,
                        note = "清理旧刮削文件：把电影文件从 ${d.fileName} 移回上层")
                }
                post += toTrash(d, "清理旧刮削文件：旧的 ${d.fileName} 文件夹（季海报 / season.nfo）移进 $TRASH_DIR")
            }

            var g = groups.firstOrNull()
            val gid = g?.id ?: gidHint
            if (g == null) {
                val ids = mutableListOf<String>()
                val target = Naming.buildFolderName(info.title, info.year, info.id)
                if (target != folder.fileName) {
                    val id = aid()
                    actions += PlanAction(id, "rename", gid, folderId, parentId, folder.fileName, target,
                        kind = "folder", mediaKind = info.kind, tmdbId = info.id)
                    ids += id
                }
                g = PlanGroup(gid, info.kind, info.id, info.title, info.year, info.posterPath, folderId,
                    folder.fileName, target.takeIf { it != folder.fileName }, ids)
            }
            // 顺序：先清理过期产物（腾出 poster.jpg 等名字），再正常操作，最后把 Season 里的电影移回、旧 Season 收进回收目录
            val all = (pre + actions + post).map { it.copy(groupId = gid) }
            val main = actions.map { it.id }.toSet()
            g = g.copy(
                itemIds = all.map { it.id },
                note = "已手动匹配" + if (needsCleanup) " · 发现旧刮削文件，可勾选「清理」" else "",
                confidence = "手动", warn = null,
            )
            if (needsCleanup) flags += "「${folder.fileName}」有旧匹配留下的刮削文件：已列出可选的「清理旧刮削文件」操作（默认不勾选，只移进 $TRASH_DIR，不删除）"
            check(main.isNotEmpty() || needsCleanup || g.folderNewName == null) { "重新规划没有生成任何操作" }
            val folderTag = "「${old.folderName}」"
            val keptFlags = plan.flags.filter { !it.contains(folderTag) }
            val newActions = plan.actions.filter { it.groupId != groupId } + all
            val newGroups = plan.groups.map { if (it.id == groupId) g else it }
            plan.copy(groups = newGroups, actions = newActions, flags = keptFlags + flags,
                stats = computeStats(newActions, newGroups))
        }

    /** 散文件也重新生成整个分组，字幕、目标目录与元数据随新匹配更新。 */
    private suspend fun replanLooseLocked(plan: ScrapePlan, groupId: String, info: MatchInfo, cloud: CloudDrive): ScrapePlan {
        val oldActions = plan.actions.filter { it.groupId == groupId }
        val source = oldActions.firstOrNull { it.kind == "file" && it.fileId != null }
            ?: error("找不到源视频，请重新扫描")
        val parent = source.parentId ?: error("缺少源文件夹，请重新扫描")
        val items = cloud.list(parent)
        check(items.any { it.fileId == source.fileId && isVideo(it) }) { "源视频已变化，请重新扫描" }
        val kept = plan.actions.filter { it.groupId != groupId }.toMutableList()
        // 原分组的 mkdir 可能被别的散电影共用，保留并交给实际依赖的分组。
        for (mkdir in oldActions.filter { it.op == "mkdir" }) {
            val dependent = kept.firstOrNull { PlanSelection.dependencyOf(it, plan.actions)?.id == mkdir.id }
            if (dependent != null) kept += mkdir.copy(groupId = dependent.groupId)
        }
        val actions = mutableListOf<PlanAction>()
        val groups = mutableListOf<PlanGroup>()
        val flags = mutableListOf<String>()
        planLooseFiles(CloudEntry(parent, "", true), items, actions, groups, flags,
            items.map { it.fileName.lowercase() }.toMutableSet(), source.fileId to info, kept.filter { it.op == "mkdir" })
        val combined = kept + actions
        val allGroups = (plan.groups.filter { it.id != groupId } + groups).map { g ->
            g.copy(itemIds = combined.filter { it.groupId == g.id }.map { it.id })
        }
        check(groups.isNotEmpty()) { "重新匹配未生成计划" }
        return plan.copy(actions = combined, groups = allGroups, flags = plan.flags + flags,
            stats = computeStats(combined, allGroups))
    }

    const val TRASH_DIR = "_刮削旧文件"

    /** 本应用生成的、和新匹配不符的文件（只认应用自己会生成的文件名）。 */
    internal fun staleArtifacts(items: List<CloudEntry>, newKind: String, matchChanged: Boolean): List<CloudEntry> {
        val seasonArt = Regex("""^season(\d{2}|-specials)-(poster|fanart|banner|landscape)\.(jpg|png)$""", RegexOption.IGNORE_CASE)
        val shared = setOf("poster.jpg", "fanart.jpg", "clearlogo.png", "banner.jpg", "landscape.jpg", "folder.jpg")
        return items.filter { !it.isDir }.filter {
            val n = it.fileName.lowercase()
            when {
                n == "tvshow.nfo" || seasonArt.matches(n) || n == "season.nfo" -> newKind == "movie" || matchChanged
                n == "movie.nfo" -> newKind == "tv" || matchChanged
                n in shared -> matchChanged
                else -> false
            }
        }
    }

    /** 当前扫描使用的网盘（scan 期间有效）。 */
    @Volatile private var drive: CloudDrive = com.tamu.pan123.data.drive.PendingDrive(com.tamu.pan123.data.drive.ProviderKind.P123, "")
    /** 扫描 / 重新匹配共用 drive、listCache 等状态：同一时间只允许一个在跑，避免互相覆盖。 */
    private val scanMutex = kotlinx.coroutines.sync.Mutex()
    private val visited = mutableSetOf<String>()

    suspend fun scan(
        roots: List<Pair<String, String>>, // id to name
        fullDrive: Boolean,
        cloud: CloudDrive = DriveRegistry.active(),
        preflightTmdb: Boolean = false,
    ): ScrapePlan = scanMutex.withLock { Naming.withSnapshot { com.tamu.pan123.data.WorkGuard.work { scanLocked(roots, fullDrive, cloud, preflightTmdb) } } }

    private suspend fun scanLocked(
        roots: List<Pair<String, String>>,
        fullDrive: Boolean,
        cloud: CloudDrive,
        preflightTmdb: Boolean,
    ): ScrapePlan = withContext(Dispatchers.IO) {
        cancelFlag = false
        runCounter++
        val started = System.currentTimeMillis()
        _progress.value = Progress("scan", "开始扫描…", startedAt = started, runId = runCounter)
        NetLog.resetActivity()
        NetLog.line("scan", "start provider=${cloud.kind.key} roots=${roots.size} full=$fullDrive")
        TmdbClient.resetFailures()
        try {
            if (preflightTmdb) {
                step("检查 TMDB 连接：${com.tamu.pan123.data.tmdb.TmdbNet.describe()}")
                TmdbClient.test().getOrElse { throw java.io.IOException(it.message ?: "无法连接 TMDB", it) }
                NetLog.note("TMDB", "preflight ok")
            }
            scanInner(roots, fullDrive, cloud).also {
                NetLog.line("scan", "done in ${System.currentTimeMillis() - started}ms")
            }
        } catch (e: CancellationException) {
            NetLog.line("scan", "cancelled after ${System.currentTimeMillis() - started}ms")
            val cur = _progress.value
            _progress.value = cur.copy(phase = "cancelled", message = "已取消扫描", cancelled = true, step = "")
            throw e
        } catch (e: Throwable) {
            val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            NetLog.error("scan", msg)
            val cur = _progress.value
            _progress.value = cur.copy(phase = "error", message = "扫描失败", error = msg, step = "")
            throw e
        }
    }

    // ======================= v0.3 扫描核心 =======================

    /** 扫描用的 TMDB 入口（测试里可替换成假的，绝不连真实服务）。 */
    internal interface Tmdb {
        suspend fun show(names: List<String>, fixedId: Int?): Pair<MatchInfo?, Int>
        suspend fun movie(stem: String, fallbackNames: List<String>, fallbackId: Int?): MatchInfo?
        suspend fun movieInfo(id: Int): MatchInfo?
        /** 电影所属合集 (合集 ID, 中文名)；不属于任何合集返回 null。 */
        suspend fun collectionOf(movieId: Int): Pair<Int, String>?
    }

    internal object RealTmdb : Tmdb {
        override suspend fun show(names: List<String>, fixedId: Int?) = TmdbClient.resolveShowDetailed(names, fixedId)
        override suspend fun movie(stem: String, fallbackNames: List<String>, fallbackId: Int?) =
            TmdbClient.resolveMovie(stem, fallbackNames, fallbackId)
        override suspend fun movieInfo(id: Int) = TmdbClient.movieDetails(id)?.let { TmdbClient.movieInfo(it) }
        override suspend fun collectionOf(movieId: Int): Pair<Int, String>? {
            val col = TmdbClient.movieDetails(movieId)?.belongsToCollection ?: return null
            if (col.id == 0) return null
            return col.id to TmdbClient.zhTitle("collection", col.id, col.name.orEmpty())
        }
    }

    @Volatile internal var tmdb: Tmdb = RealTmdb

    /** 历史记录查询（设置里可关闭）。返回 null 表示不使用历史。 */
    @Volatile internal var historyLookup: ((accountId: String, pathIds: Boolean, folderId: String, folderName: String) ->
        com.tamu.pan123.data.history.HistoryGroup?)? = { acc, paths, id, name ->
        com.tamu.pan123.data.history.HistoryStore.remembered(acc, paths, id, name)
    }

    private val listCache = java.util.concurrent.ConcurrentHashMap<String, List<CloudEntry>>()

    /** 列目录（同一次扫描内缓存，避免分类判断时重复请求）。 */
    private suspend fun ls(id: String): List<CloudEntry> {
        checkActive()
        listCache[id]?.let { return it }
        val got = drive.list(id).map { if (it.parentFileId == null) it.copy(parentFileId = id) else it }
        listCache[id] = got
        return got
    }

    private fun isVideo(e: CloudEntry) = !e.isDir && Naming.splitNameExt(e.fileName).second.lowercase() in Naming.VIDEO_EXT
    private fun isSub(e: CloudEntry) = !e.isDir && Naming.splitNameExt(e.fileName).second.lowercase() in Naming.SUB_EXT
    private val COMPANION_EXT = setOf(".jpg", ".jpeg", ".png", ".webp", ".nfo")
    private fun isCompanion(e: CloudEntry) = !e.isDir && Naming.splitNameExt(e.fileName).second.lowercase() in COMPANION_EXT

    /** 完全不碰的子目录（样片、预告、字幕包、Emby 额外内容约定目录、NAS 系统目录）。 */
    private val IGNORE_DIRS = setOf(
        "sample", "samples", "trailer", "trailers", "预告", "预告片", "subs", "subtitles", "sub", "字幕",
        "extras", "extra", "featurettes", "bonus", "behind the scenes", "deleted scenes", "interviews", "scenes", "shorts",
        "other", "others", "scans", "@eadir", "#recycle", ".trash", "\$recycle.bin", "lost+found", "metadata", ".actors",
    )
    internal fun ignoredDir(name: String) = name == TRASH_DIR || name.trim().lowercase() in IGNORE_DIRS

    private val SPECIAL_DIR = Regex("""^\s*(?:花絮|番外|番外篇|特典|特别篇|特別篇|SPs?|OVAs?|OADs?|Specials?|加更|彩蛋|纯享|纯享版|先导|先导片|幕后)\s*$""", RegexOption.IGNORE_CASE)

    /** 目录名对应的季号（季文件夹 / 特别篇文件夹 → 0），不是则 null。 */
    internal fun seasonOfDir(name: String): Int? = when {
        SPECIAL_DIR.matches(name) -> 0
        Naming.isSeasonDir(name) -> Naming.seasonFromName(name)
        else -> null
    }

    /** 像片名的子目录（不是季 / 特别篇 / 无意义 / 分类 / 忽略目录）。 */
    private fun titleLikeDir(name: String) = seasonOfDir(name) == null && !Naming.isGenericDir(name) &&
        !ignoredDir(name) && !Naming.isCategoryDir(name)

    internal enum class Role { TITLE, CONTAINER }

    /**
     * 按名字判断一个文件夹是「一部片子」还是「分类 / 归档目录」。
     * 分类 / 地区 / 年份名（剧集、电影、国产、美剧、2026、Videos、资源…）永远不是片名。
     */
    internal suspend fun classify(d: CloudEntry, kids: List<CloudEntry>): Role {
        val name = d.fileName.trim()
        val vids = kids.filter { isVideo(it) }
        val dirs = kids.filter { it.isDir && !ignoredDir(it.fileName) }
        if (Naming.isCategoryDir(name)) {
            // 只有纯年份名、且里面直接就是视频（没有子目录）时，才可能是片名（如《2012》）
            val yearOnly = Regex("""^(?:19|20)\d{2}$""").matches(name)
            return if (yearOnly && vids.isNotEmpty() && dirs.isEmpty()) Role.TITLE else Role.CONTAINER
        }
        if (Naming.isGenericDir(name)) return Role.CONTAINER
        val titleDirs = dirs.filter { titleLikeDir(it.fileName) }
        if (Naming.looksLikeCollectionName(name) && titleDirs.isNotEmpty()) return Role.CONTAINER
        if (dirs.any { Naming.isSeasonDir(it.fileName) }) return Role.TITLE
        if (titleDirs.isEmpty()) return Role.TITLE
        val epVids = vids.count { Naming.parseEpisode(Naming.splitNameExt(it.fileName).first) != null }
        if (vids.isNotEmpty()) {
            if (epVids > 0 && titleDirs.size <= 1) return Role.TITLE   // 剧集 + 一个杂项目录
            if (vids.size == 1 && epVids == 0) return Role.TITLE       // 单部电影 + 杂项目录
            return Role.CONTAINER                                       // 多部散落电影 + 别的片子文件夹
        }
        // 没有直属视频、只有像片名的子目录：子目录名都含本目录的片名、且里面是剧集 → 本目录是剧（子目录是不规范的季文件夹）
        val core = Naming.norm(Naming.queryFromName(name).first)
        if (core.length < 2 || titleDirs.size > 4) return Role.CONTAINER
        if (!titleDirs.all { Naming.norm(it.fileName).contains(core) }) return Role.CONTAINER
        for (c in titleDirs) {
            val ck = ls(c.fileId)
            if (ck.any { it.isDir && Naming.isSeasonDir(it.fileName) }) return Role.CONTAINER // 子目录才是真正的剧
            if (ck.none { isVideo(it) && Naming.parseEpisode(Naming.splitNameExt(it.fileName).first) != null }) return Role.CONTAINER
        }
        return Role.TITLE
    }

    private suspend fun scanInner(
        roots: List<Pair<String, String>>,
        fullDrive: Boolean,
        cloud: CloudDrive,
    ): ScrapePlan {
        drive = cloud
        listCache.clear()
        visited.clear()
        historySkipped = 0
        val actions = mutableListOf<PlanAction>()
        val groups = mutableListOf<PlanGroup>()
        val flags = mutableListOf<String>()
        var filesSeen = 0

        val rootList = if (fullDrive) listOf(cloud.rootId to "根目录") else roots.ifEmpty { listOf(cloud.rootId to "根目录") }
        prog("scan", "开始扫描…", 0, rootList.size)

        rootList.forEachIndexed { idx, (rootId, rootName) ->
            checkActive()
            prog("scan", "扫描：$rootName", idx, rootList.size, filesSeen, step = "读取目录：$rootName")
            val items = ls(rootId)
            val rootEntry = CloudEntry(fileId = rootId, fileName = rootName.trimEnd('/').substringAfterLast('/'), isDir = true)
            val role = if (fullDrive || rootId == cloud.rootId) Role.CONTAINER else classify(rootEntry, items)
            filesSeen += if (role == Role.CONTAINER) {
                walkContainer(rootEntry, items, 0, actions, groups, flags) { done ->
                    prog("scan", "扫描：$rootName", idx, rootList.size, filesSeen + done)
                }
            } else {
                step("匹配 TMDB：$rootName")
                planTitleFolder(rootEntry, items, actions, groups, flags, mutableSetOf())
            }
            prog("scan", "完成：$rootName", idx + 1, rootList.size, filesSeen)
        }
        checkActive()
        if (historySkipped > 0) flags += "有 $historySkipped 个文件夹复用了历史匹配，已重新检查缺失操作（设置 › 历史记录 可关闭或删除记录）"

        val stats = computeStats(actions, groups).copy(history = historySkipped)
        val plan = ScrapePlan(
            accountId = cloud.accountId,
            provider = cloud.kind.key,
            createdTs = System.currentTimeMillis() / 1000.0,
            createdAt = PlanStore.nowLabel(),
            roots = rootList.map { PlanRoot(it.first, it.second, fullDrive) },
            groups = groups,
            actions = actions,
            flags = flags,
            stats = stats,
        )
        PlanStore.save(plan)
        prog("done", "扫描完成：${groups.size} 组，${actions.size} 项", rootList.size, rootList.size, filesSeen)
        listCache.clear()
        return plan
    }

    @Volatile private var historySkipped = 0

    /** 分类 / 归档目录：处理散文件，再逐个子目录判断是片子还是继续往下的分类目录。 */
    private suspend fun walkContainer(
        dir: CloudEntry, items: List<CloudEntry>, depth: Int,
        actions: MutableList<PlanAction>, groups: MutableList<PlanGroup>, flags: MutableList<String>,
        onProgress: (Int) -> Unit = {},
    ): Int {
        if (!visited.add(dir.fileId)) return 0
        var seen = 0
        val taken = items.map { it.fileName.lowercase() }.toMutableSet()
        if (items.any { isVideo(it) }) {
            step("匹配 TMDB：${dir.fileName} 里的散文件")
            seen += planLooseFiles(dir, items, actions, groups, flags, taken)
        }
        for (d0 in items.filter { it.isDir }) {
            checkActive()
            val d = if (d0.parentFileId == null) d0.copy(parentFileId = dir.fileId) else d0
            if (ignoredDir(d.fileName)) continue
            step("读取目录：${d.fileName}")
            val kids = ls(d.fileId)
            if (Naming.isSeasonDir(d.fileName)) {
                step("匹配 TMDB：${d.fileName}")
                seen += planDirAsShowOrMovie(d, kids, Naming.seasonFromName(d.fileName), actions, groups, flags, taken, false)
                continue
            }
            when (classify(d, kids)) {
                Role.TITLE -> {
                    step("匹配 TMDB：${d.fileName}")
                    seen += planTitleFolder(d, kids, actions, groups, flags, taken)
                }
                Role.CONTAINER -> if (depth < 6) seen += walkContainer(d, kids, depth + 1, actions, groups, flags, onProgress)
                else flags += "「${d.fileName}」目录层级太深，未继续扫描"
            }
            onProgress(seen)
        }
        return seen
    }

    /** 片子文件夹内部的目录树（最多往下 3 层，跳过忽略目录）。 */
    private class Node(val entry: CloudEntry, val items: List<CloudEntry>, val parent: Node?, val depth: Int) {
        val id: String get() = entry.fileId
        fun chain(): Sequence<Node> = generateSequence(this) { it.parent }
        /** 根目录下的直属子目录（自己在根下就是自己）。 */
        fun holder(root: Node): Node? = holderOf(this, root, { it.parent })
        fun pathFrom(root: Node): String = chain().takeWhile { it !== root }.toList().reversed().joinToString("/") { it.entry.fileName }
    }

    /**
     * 节点 [node] 在 [root] 下的直属子目录（自己在根下就是自己）。
     * 不在 [root] 这棵树里（父链走到头也没碰到 root）或 node 就是 root 时返回 null，不会越根 NPE（M10）。
     */
    internal fun <T : Any> holderOf(node: T, root: T, parent: (T) -> T?): T? {
        if (node === root) return null
        var n = node
        while (true) {
            val p = parent(n) ?: return null
            if (p === root) return n
            n = p
        }
    }

    const val MAX_TREE_NODES = 80
    internal fun truncatedFlag(folder: String) = "「$folder」文件太多，只扫描了前 $MAX_TREE_NODES 项（其余子文件夹没有处理，可以分开选择这些子文件夹再扫描）"

    private suspend fun buildTree(folder: CloudEntry, items: List<CloudEntry>, descend: Boolean, maxDepth: Int = 3, flags: MutableList<String>? = null): List<Node> {
        val root = Node(folder, items, null, 0)
        val out = mutableListOf(root)
        if (!descend) return out
        val seen = mutableSetOf(folder.fileId)
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.depth >= maxDepth) continue
            for (d in n.items.filter { it.isDir }) {
                if (ignoredDir(d.fileName) || d.fileId in visited || !seen.add(d.fileId)) continue
                if (out.size >= MAX_TREE_NODES) {
                    val f = truncatedFlag(folder.fileName)
                    if (flags != null && f !in flags) flags += f
                    break
                }
                val c = Node(if (d.parentFileId == null) d.copy(parentFileId = n.id) else d, ls(d.fileId), n, n.depth + 1)
                out += c
                queue += c
            }
        }
        return out
    }

    private fun subtree(n: Node, tree: List<Node>): List<Node> = tree.filter { t -> t.chain().any { it === n } }

    /**
     * 多余文件夹：视频 / 字幕都被移走、本身也不是目标季文件夹的目录，生成可选的「删除」操作（默认不勾选）。
     * 备注里写清楚移走后还剩哪些文件；当前网盘不支持删除时标记为不可执行并给出提示。
     */
    private fun planLeftovers(
        root: Node, tree: List<Node>, keepIds: Set<String>, movedIds: Set<String>, gid: String,
        actions: MutableList<PlanAction>,
    ) {
        val movedFrom = tree.filter { n -> n.items.any { it.fileId in movedIds } }.toSet()
        if (movedFrom.isEmpty()) return
        val redundant = tree.filter { n ->
            n !== root && n.id !in keepIds &&
                tree.none { t -> t.id in keepIds && t.chain().any { it === n } } &&
                subtree(n, tree).any { it in movedFrom }
        }.toSet()
        for (n in redundant.filter { it.parent !in redundant }) {
            val sub = subtree(n, tree)
            val others = sub.flatMap { s -> s.items.filter { !it.isDir && it.fileId !in movedIds } }
            val unlisted = sub.flatMap { s -> s.items.filter { it.isDir && tree.none { t -> t.id == it.fileId } } }
            val rest = others.map { it.fileName } + unlisted.map { it.fileName + "/" }
            val note = if (rest.isEmpty()) "移走视频后这个文件夹是空的"
            else "移走视频后仍有其它文件 ${rest.size} 个：" + rest.take(3).joinToString("、") + (if (rest.size > 3) " 等" else "") + "，删除时会一起删除"
            val support = drive.trashSupport
            val blocked = if (support == com.tamu.pan123.data.drive.TrashSupport.NONE)
                com.tamu.pan123.data.drive.TrashSupport.noneHint(drive.kind) else null
            actions += PlanAction(aid(), "delete", gid, n.id, n.parent?.id, n.pathFrom(root), "",
                kind = "leftover", selected = false, blocked = blocked,
                note = "$note · ${support.label}")
        }
    }

    private fun moveAct(gid: String, f: CloudEntry, fromId: String, name: String, destId: String?, destLabel: String,
                        destRef: String?, kind: String, note: String, season: Int? = null, episode: Int? = null) =
        PlanAction(aid(), "move", gid, f.fileId, fromId, name, name, destParentId = destId, destLabel = destLabel,
            kind = kind, destRef = destRef, note = note, season = season, episode = episode)

    private suspend fun planTitleFolder(
        folder: CloudEntry,
        items: List<CloudEntry>,
        actions: MutableList<PlanAction>,
        groups: MutableList<PlanGroup>,
        flags: MutableList<String>,
        rootTaken: MutableSet<String>,
        forced: MatchInfo? = null,
    ): Int {
        if (!visited.add(folder.fileId)) return 0
        val tree = buildTree(folder, items, descend = forced?.kind != "movie", flags = flags)
        tree.drop(1).forEach { visited.add(it.id) }
        val allVideos = tree.flatMap { n -> n.items.filter { isVideo(it) } }
        if (allVideos.isEmpty()) {
            flags += "「${folder.fileName}」没有视频文件，跳过"
            return 0
        }
        // 历史记录：整理过的文件夹不再匹配 TMDB；没有新视频就整个跳过
        var remembered: MatchInfo? = null
        if (forced == null) {
            val hit = runCatching { historyLookup?.invoke(drive.accountId, drive.kind.idsArePaths, folder.fileId, folder.fileName) }.getOrNull()
            if (hit != null) {
                historySkipped++ // 只复用身份，仍核验字幕、目录和缺失的海报 / NFO
                remembered = MatchInfo(hit.kind, hit.tmdbId, hit.title, hit.year)
            }
        }
        val match0 = forced ?: remembered
        val isTv = match0?.let { it.kind == "tv" } ?: (tree.size > 1 && tree.drop(1).any { seasonOfDir(it.entry.fileName) != null } ||
            allVideos.any { Naming.parseEpisode(Naming.splitNameExt(it.fileName).first) != null })
        val doneId = Naming.folderDoneId(folder.fileName)
        val start = actions.size
        return if (isTv) planTvFolder(folder, tree, allVideos, actions, groups, flags, rootTaken, forced, remembered, doneId, start)
        else planMovieFolder(folder, tree, allVideos, actions, groups, flags, rootTaken, forced, remembered, doneId)
    }

    private val CN_EXTRA = Regex("""番外|花絮|加更|彩蛋|先导|纯享|特辑|特别篇|特別篇|幕后|剧场版|劇場版|OVA|OAD""", RegexOption.IGNORE_CASE)

    private class Vid(val e: CloudEntry, val node: Node, val stem: String, val ext: String) {
        var season = 1
        var episode = 0
        var special = false
        var number: Int? = null
        var newName = e.fileName
        var newBase = stem
        var target: SeasonTarget? = null
    }
    private class SeasonTarget(val season: Int, val label: String, val dirId: String?, val mkdirId: String?)

    private suspend fun planTvFolder(
        folder: CloudEntry, tree: List<Node>, allVideos: List<CloudEntry>,
        actions: MutableList<PlanAction>, groups: MutableList<PlanGroup>, flags: MutableList<String>,
        rootTaken: MutableSet<String>, forced: MatchInfo?, remembered: MatchInfo?, doneId: Int?, start: Int,
    ): Int {
        val root = tree[0]
        val names = listOf(folder.fileName) + allVideos.take(1).map { Naming.splitNameExt(it.fileName).first }
        var matchedBy = -1
        // 文件名里已有 {tmdb-ID}（之前用「播放器兼容命名」改过）且都一致时，作为强匹配线索
        val fileIds = allVideos.mapNotNull { Naming.scrapedTmdbId(it.fileName) }.distinct()
        val hintId = doneId ?: fileIds.singleOrNull()
        val show = forced ?: remembered ?: tmdb.show(names, hintId).let { (m, idx) -> matchedBy = idx; m }
        if (show == null) {
            val gid = "unmatched-${folder.fileId}"
            groups += PlanGroup(gid, "unmatched", title = folder.fileName, folderId = folder.fileId,
                folderName = folder.fileName, note = "剧集 TMDB 未匹配")
            flags += "剧集「${folder.fileName}」TMDB 未匹配，整个文件夹未改名"
            allVideos.forEach { v ->
                actions += PlanAction(aid(), "unmatched", gid, v.fileId, v.parentFileId ?: folder.fileId, v.fileName, kind = "file")
            }
            return allVideos.size
        }
        val gid = "tv-${show.id}-${folder.fileId}"
        // 特别篇关键词不能来自片名本身（如片名里就有「先导」）
        val titleNorms = listOf(show.title, Naming.queryFromName(folder.fileName).first).map { it.trim() }.filter { it.length >= 2 }
        fun stemForExtra(stem: String) = titleNorms.fold(stem) { acc, t -> acc.replace(t, " ", ignoreCase = true) }

        // ① 收集视频：正片 / 特别篇 / 剧场版
        val vids = mutableListOf<Vid>()
        val movieCands = mutableListOf<Vid>()
        for (n in tree) {
            val hint = n.chain().map { seasonOfDir(it.entry.fileName) }.firstOrNull { it != null }
            for (f in n.items) {
                if (!isVideo(f)) continue
                val (stem, ext) = Naming.splitNameExt(f.fileName)
                val v = Vid(if (f.parentFileId == null) f.copy(parentFileId = n.id) else f, n, stem, ext)
                val ep = Naming.parseEpisode(stem)
                val s2 = stemForExtra(stem)
                var extra = Naming.extraKind(s2)
                // 「Special.Ops.S01E01」这种：有标准 SxxEyy（非 S00）且只命中英文词时按正片
                if (extra != null && ep?.first != null && ep.first != 0 && !CN_EXTRA.containsMatchIn(s2)) extra = null
                when {
                    extra == Naming.Extra.MOVIE && ep == null -> { movieCands += v; continue }
                    extra != null || hint == 0 -> {
                        v.special = true; v.season = 0
                        v.number = if (ep?.first == 0) ep.second else Naming.extraNumber(stem)
                    }
                    ep == null -> {
                        flags += "剧集文件认不出集数，未改名：${f.fileName}"
                        actions += PlanAction(aid(), "unmatched", gid, f.fileId, n.id, f.fileName, kind = "file", note = "无集数")
                        continue
                    }
                    else -> { v.season = ep.first ?: hint ?: 1; v.episode = ep.second }
                }
                vids += v
            }
        }
        // ② 剧场版：按电影匹配 TMDB，匹配上就在剧集文件夹旁边单独建电影文件夹；匹配不上当作特别篇
        for (v in movieCands) {
            val parent = folder.parentFileId
            val q = Naming.stripExtraWords(Naming.cleanForSearch(v.stem)).ifBlank { show.title }
            val info = if (parent != null) runCatching { tmdb.movie(q, listOf(v.stem), null) }.getOrNull() else null
            val folderName = info?.let { Naming.buildFolderName(it.title, it.year, it.id) }
            if (info != null && parent != null && folderName!!.lowercase() !in rootTaken) {
                planExtraMovie(v, info, folderName, parent, show, actions, groups, flags)
                rootTaken += folderName.lowercase()
            } else {
                v.special = true; v.season = 0; v.number = Naming.extraNumber(v.stem)
                vids += v
            }
        }
        // ③ 特别篇编号：自带序号且不重复就用自带的，否则按 (序号, 文件名) 排序后从 1 编
        val sp = vids.filter { it.special }.sortedWith(compareBy({ it.number ?: Int.MAX_VALUE }, { it.e.fileName }))
        val nums = sp.mapNotNull { it.number }
        val own = nums.size == sp.size && nums.toSet().size == nums.size && nums.none { it <= 0 }
        sp.forEachIndexed { i, v -> v.episode = if (own) v.number!! else i + 1 }

        // ④ 季文件夹：已有的季文件夹先占位；只装着一季视频的其它子目录直接改名为季文件夹（不新建、不移动）
        val holders = tree.filter { it.parent === root }
        val targets = HashMap<Int, SeasonTarget>()
        for (h in holders) {
            val n = seasonOfDir(h.entry.fileName) ?: continue
            if (targets.containsKey(n)) continue
            val want = Naming.seasonDirName(n)
            targets[n] = SeasonTarget(n, want, h.id, null)
            if (h.entry.fileName != want) {
                actions += PlanAction(aid(), "rename", gid, h.id, folder.fileId, h.entry.fileName, want, kind = "folder", season = n)
            }
        }
        for (h in holders) {
            if (targets.values.any { it.dirId == h.id }) continue
            if (seasonOfDir(h.entry.fileName) != null) continue // 重复的季文件夹：视频移进已占位的那个
            val seasons = vids.filter { it.node.holder(root) === h }.map { it.season }.toSet()
            if (seasons.size != 1) continue
            val n = seasons.first()
            if (targets.containsKey(n)) continue
            val want = Naming.seasonDirName(n)
            if (root.items.any { it.isDir && it.fileName.equals(want, true) && it.fileId != h.id }) continue
            targets[n] = SeasonTarget(n, want, h.id, null)
            actions += PlanAction(aid(), "rename", gid, h.id, folder.fileId, h.entry.fileName, want, kind = "folder", season = n,
                note = "这个子文件夹只装着第 $n 季的视频，直接改名为季文件夹（不新建、不移动）".let { if (n == 0) "这个子文件夹只装着特别篇，直接改名为 $want" else it })
        }
        val newSeasonDirs = linkedMapOf<Int, String>()
        for (v in vids.sortedWith(compareBy({ it.season }, { it.episode }))) {
            v.target = targets.getOrPut(v.season) {
                val name = Naming.seasonDirName(v.season)
                val id = aid()
                actions += PlanAction(id, "mkdir", gid, parentId = folder.fileId, newName = name, kind = "folder",
                    note = if (v.season == 0) "特别篇需要 $name 文件夹（执行阶段创建）" else "散落单集需要季文件夹（执行阶段创建）", season = v.season)
                newSeasonDirs[v.season] = id
                SeasonTarget(v.season, name, null, id)
            }
        }

        // ⑤ 改名（按目标季文件夹查重）
        val taken = HashMap<Int, MutableSet<String>>()
        fun takenFor(t: SeasonTarget): MutableSet<String> = taken.getOrPut(t.season) {
            (tree.firstOrNull { it.id == t.dirId }?.items ?: emptyList()).map { it.fileName.lowercase() }.toMutableSet()
        }
        for (v in vids) {
            val media = MediaParse.parseMedia(v.stem)
            val base = Naming.buildEpisodeBase(show.title, show.year, v.season, v.episode, media, show.id)
            val target = Naming.claim(base + v.ext, if (v.node.id == v.target!!.dirId) v.e.fileName else "", takenFor(v.target!!)) ?: continue
            v.newName = target
            v.newBase = Naming.splitNameExt(target).first
            val op = if (target == v.e.fileName) "skip" else "rename"
            actions += PlanAction(aid(), op, gid, v.e.fileId, v.node.id, v.e.fileName, target,
                kind = "file", mediaKind = "tv", tmdbId = show.id, season = v.season, episode = v.episode,
                note = if (v.special) "特别篇 → S00E%02d".format(v.episode) else null)
        }
        // ⑥ 字幕：同目录内按原名前缀 / 新名前缀 / 集数 / 唯一视频匹配
        val subsOf = HashMap<Vid, MutableList<Pair<CloudEntry, String>>>()
        for (n in tree) {
            val local = vids.filter { it.node === n }
            for (s in n.items.filter { isSub(it) }) {
                val (stem, ext) = Naming.splitNameExt(s.fileName)
                var match: Vid? = null
                var lang = ""
                loop@ for (useOld in listOf(true, false)) {
                    for (v in local.sortedByDescending { (if (useOld) it.stem else it.newBase).length }) {
                        val k = if (useOld) v.stem else v.newBase
                        if (stem.lowercase().startsWith(k.lowercase())) {
                            val r = stem.substring(k.length)
                            if (r.isEmpty() || r[0] in ".-_ ") { match = v; lang = r.trim('.', '-', '_', ' ').replace(' ', '.'); break@loop }
                        }
                    }
                }
                if (match == null) {
                    val ep = Naming.parseEpisode(stem)
                    match = if (ep != null) local.firstOrNull { !it.special && it.episode == ep.second && it.season == (ep.first ?: it.season) }
                    else local.singleOrNull()
                    lang = Naming.langSuffix(stem)
                }
                val m = match
                if (m == null) { flags += "字幕找不到对应的视频，未改名：${s.fileName}"; continue }
                val want = Naming.claim(m.newBase + (if (lang.isNotEmpty()) ".$lang" else "") + ext, if (n.id == m.target!!.dirId) s.fileName else "", takenFor(m.target!!)) ?: continue
                if (want != s.fileName) actions += PlanAction(aid(), "rename", gid, s.fileId, n.id, s.fileName, want, kind = "subtitle")
                subsOf.getOrPut(m) { mutableListOf() } += s to want
            }
        }
        // ⑦ 移动：不在目标季文件夹里的视频（连同字幕）移进去
        val moved = mutableSetOf<String>()
        for (v in vids) {
            val t = v.target!!
            if (t.dirId == v.node.id) continue
            actions += moveAct(gid, v.e, v.node.id, v.newName, t.dirId, t.label, t.mkdirId, "file",
                "移入 ${t.label}" + if (v.node !== root) "（原在 ${v.node.pathFrom(root)}）" else "（执行阶段）", v.season, v.episode)
            moved += v.e.fileId
            for ((s, name) in subsOf[v].orEmpty()) {
                actions += moveAct(gid, s, v.node.id, name, t.dirId, t.label, t.mkdirId, "subtitle", "字幕跟随视频移入 ${t.label}")
                moved += s.fileId
            }
        }
        // ⑧ 多余文件夹（可选删除）
        planLeftovers(root, tree, targets.values.mapNotNull { it.dirId }.toSet(), moved, gid, actions)

        // ⑨ 剧集文件夹改名
        var folderTarget = Naming.buildFolderName(show.title, show.year, show.id)
        if (folderTarget != folder.fileName) {
            if (folderTarget.lowercase() in rootTaken && folderTarget.lowercase() != folder.fileName.lowercase()) {
                flags += "文件夹目标名「$folderTarget」已被占用，「${folder.fileName}」保持不变"
                folderTarget = folder.fileName
            } else {
                actions += PlanAction(aid(), "rename", gid, folder.fileId, folder.parentFileId,
                    folder.fileName, folderTarget, kind = "folder", mediaKind = "tv", tmdbId = show.id)
                rootTaken.remove(folder.fileName.lowercase())
                rootTaken += folderTarget.lowercase()
            }
        } else {
            actions += PlanAction(aid(), "skip", gid, folder.fileId, note = "文件夹已是目标名",
                oldName = folder.fileName, newName = folderTarget, kind = "folder")
        }
        // ⑩ 海报 / NFO：季海报按最终的季文件夹（改名后的 / 新建的）
        val seasonDirsAfter = targets.values.filter { it.dirId != null }.map { CloudEntry(it.dirId!!, it.label, true, parentFileId = folder.fileId) }
        val virtualItems = root.items.filter { !it.isDir } + seasonDirsAfter
        val itemIds = mutableListOf<String>()
        planArtwork(folder.fileId, folder.fileName, show, gid, actions, itemIds, virtualItems,
            newSeasonDirs.mapValues { (s, id) -> id to Naming.seasonDirName(s) }, tree.associate { it.id to it.items })

        val mismatch = forced == null && remembered == null && matchedBy > 0 && folderTitleMismatch(folder.fileName, show.title)
        val conf = when {
            forced != null -> "手动"
            remembered != null -> "历史"
            doneId != null || matchedBy == 0 -> "高"
            mismatch -> "低"
            else -> "中"
        }
        val warn = if (mismatch) "文件夹名与文件名不一致：按文件名匹配到《${show.title}》" else null
        if (warn != null) flags += "「${folder.fileName}」$warn，请确认或点分组重新匹配"
        val ids = actions.subList(start, actions.size).filter { it.groupId == gid }.map { it.id }
        groups += PlanGroup(gid, "tv", show.id, show.title, show.year, show.posterPath,
            folder.fileId, folder.fileName, folderTarget.takeIf { it != folder.fileName }, ids,
            confidence = conf, warn = warn,
            note = listOfNotNull(
                vids.count { it.special }.takeIf { it > 0 }?.let { "特别篇 $it 个 → ${Naming.seasonDirName(0)}" },
                movieCands.size.takeIf { it > 0 }?.let { "剧场版 $it 个" },
            ).joinToString(" · ").ifBlank { null })
        return allVideos.size
    }

    /** 剧集文件夹里的剧场版：在剧集文件夹旁边建 `名称(年份){tmdb-ID}`，改名后连同字幕移进去。 */
    private suspend fun planExtraMovie(
        v: Vid, info: MatchInfo, folderName: String, parentId: String, show: MatchInfo,
        actions: MutableList<PlanAction>, groups: MutableList<PlanGroup>, flags: MutableList<String>,
    ) {
        val gid = "movie-${info.id}-extra-${v.e.fileId}"
        val start = actions.size
        val mk = aid()
        actions += PlanAction(mk, "mkdir", gid, parentId = parentId, newName = folderName, kind = "folder",
            mediaKind = "movie", tmdbId = info.id, note = "剧场版单独建电影文件夹（与剧集文件夹同级）")
        val base = Naming.buildMovieBase(info.title, info.year, MediaParse.parseMedia(v.stem), v.stem, info.id)
        val target = base + v.ext
        if (target != v.e.fileName) actions += PlanAction(aid(), "rename", gid, v.e.fileId, v.node.id, v.e.fileName, target,
            kind = "file", mediaKind = "movie", tmdbId = info.id)
        actions += moveAct(gid, v.e, v.node.id, target, null, folderName, mk, "file", "剧场版移进电影文件夹")
        for (s in v.node.items.filter { isSub(it) && it.fileName.lowercase().startsWith(v.stem.lowercase()) }) {
            val rest = Naming.splitNameExt(s.fileName).first.substring(v.stem.length).trim('.', '-', '_', ' ')
            val name = base + (if (rest.isNotEmpty()) ".$rest" else "") + Naming.splitNameExt(s.fileName).second
            if (name != s.fileName) actions += PlanAction(aid(), "rename", gid, s.fileId, v.node.id, s.fileName, name, kind = "subtitle")
            actions += moveAct(gid, s, v.node.id, name, null, folderName, mk, "subtitle", "字幕跟随剧场版移动")
        }
        val ids = mutableListOf<String>()
        planMovieArtwork(null, info.id, info.posterPath, gid, actions, ids, emptyList(), destRef = mk, destLabel = folderName)
        flags += "《${show.title}》里的「${v.e.fileName}」识别为剧场版，按电影匹配到《${info.title}》(${info.year})，请确认"
        groups += PlanGroup(gid, "movie", info.id, info.title, info.year, info.posterPath, null, folderName, null,
            actions.subList(start, actions.size).map { it.id }, confidence = "中", note = "剧场版（来自《${show.title}》）")
    }

    private class MovieVid(val e: CloudEntry, val node: Node, val stem: String, val ext: String) {
        var info: MatchInfo? = null
        var newBase = ""
        var newName = e.fileName
    }

    private suspend fun planMovieFolder(
        folder: CloudEntry, tree: List<Node>, allVideos: List<CloudEntry>,
        actions: MutableList<PlanAction>, groups: MutableList<PlanGroup>, flags: MutableList<String>,
        rootTaken: MutableSet<String>, forced: MatchInfo?, remembered: MatchInfo?, doneId: Int?,
    ): Int {
        val root = tree[0]
        val gidBase = "folder-${folder.fileId}"
        val start = actions.size
        val single = allVideos.size == 1
        val fbNames = if (single) listOf(folder.fileName) else emptyList()
        val fbId = if (single) doneId else null
        val taken = tree.flatMap { n -> n.items.map { it.fileName.lowercase() } }.toMutableSet()
        var unmatched = 0
        val mv = mutableListOf<MovieVid>()
        for (n in tree) for (f in n.items) {
            if (!isVideo(f)) continue
            val (stem, ext) = Naming.splitNameExt(f.fileName)
            val v = MovieVid(if (f.parentFileId == null) f.copy(parentFileId = n.id) else f, n, stem, ext)
            val folderFirst = single && Naming.queryFromName(folder.fileName).let { (t, y) -> y != null && Naming.CJK.containsMatchIn(t) }
            val info = forced ?: remembered
                ?: if (folderFirst) tmdb.movie(folder.fileName, listOf(stem), fbId) else tmdb.movie(stem, fbNames, fbId)
            if (info == null) {
                unmatched++
                actions += PlanAction(aid(), "unmatched", gidBase, f.fileId, f.parentFileId ?: n.id, f.fileName, kind = "file")
                mv += v
                continue
            }
            v.info = info
            val base = Naming.buildMovieBase(info.title, info.year, MediaParse.parseMedia(stem), stem, info.id)
            val target = Naming.claim(base + ext, f.fileName, taken)
            if (target == null) { flags += "目标名重复且无法加后缀：${f.fileName}"; continue }
            v.newName = target
            v.newBase = Naming.splitNameExt(target).first
            actions += PlanAction(aid(), if (target == f.fileName) "skip" else "rename", gidBase, f.fileId, f.parentFileId ?: n.id,
                f.fileName, target, kind = "file", mediaKind = "movie", tmdbId = info.id)
            mv += v
        }
        val matched = mv.filter { it.info != null && it.newBase.isNotBlank() }
        // 字幕（与 rename_movies.py 一致：原名前缀 → 新名前缀 → 同目录唯一视频）
        val subsOf = HashMap<MovieVid, MutableList<Pair<CloudEntry, String>>>()
        for (n in tree) for (s in n.items.filter { isSub(it) }) {
            val (stem, ext) = Naming.splitNameExt(s.fileName)
            val local = matched.filter { it.node === n }
            var hit: MovieVid? = null
            var rest = ""
            loop@ for (useOld in listOf(true, false)) {
                for (v in local.sortedByDescending { (if (useOld) it.stem else it.newBase).length }) {
                    val k = if (useOld) v.stem else v.newBase
                    if (stem.lowercase().startsWith(k.lowercase())) {
                        val r = stem.substring(k.length)
                        if (r.isEmpty() || r[0] in ".-_ ") { hit = v; rest = r.trim('.', '-', '_', ' ').replace(' ', '.'); break@loop }
                    }
                }
            }
            if (hit == null) {
                // 字幕名比视频名短（常见：钢铁侠1.chs.srt ↔ 钢铁侠1.2160p.mkv）：去掉语言后缀后，是唯一一个视频原名的前缀
                val lang = Naming.langSuffix(stem)
                val core = (if (lang.isEmpty()) stem else stem.dropLast(lang.length + 1)).lowercase()
                if (core.length >= 2) {
                    val c = local.filter { v -> val o = v.stem.lowercase(); o.startsWith(core) && (o.length == core.length || o[core.length] in ".-_ [(") }
                    if (c.size == 1) { hit = c[0]; rest = lang }
                }
            }
            if (hit == null && local.size == 1 && mv.count { it.node === n } == 1) { hit = local[0]; rest = Naming.langSuffix(stem) }
            val h = hit
            if (h == null) { flags += "字幕找不到对应的视频，未改名：${s.fileName}"; continue }
            val want = Naming.claim(h.newBase + (if (rest.isNotEmpty()) ".$rest" else "") + ext, s.fileName, taken) ?: continue
            if (want != s.fileName) actions += PlanAction(aid(), "rename", gidBase, s.fileId, n.id, s.fileName, want, kind = "subtitle")
            subsOf.getOrPut(h) { mutableListOf() } += s to want
        }

        val distinct = matched.mapNotNull { it.info?.id }.distinct()
        var folderTarget: String? = null
        var groupKind = "movie"
        var groupTitle = folder.fileName
        var groupYear = ""
        var groupTmdb: Int? = null
        var poster: String? = null
        var perMovie = false
        when {
            distinct.size == 1 && unmatched == 0 -> {
                val info = matched.first().info!!
                folderTarget = Naming.buildFolderName(info.title, info.year, info.id)
                groupTitle = info.title; groupYear = info.year; groupTmdb = info.id; poster = info.posterPath
            }
            distinct.size == 1 -> flags += "文件夹「${folder.fileName}」有 $unmatched 个视频没匹配上 TMDB，文件夹名保持不变"
            distinct.size >= 2 -> {
                perMovie = true
                val cols = linkedMapOf<Int, String>()
                val missing = mutableListOf<String>()
                for (mid in distinct) {
                    val c = runCatching { tmdb.collectionOf(mid) }.getOrNull()
                    if (c != null) cols[c.first] = c.second else missing += "tmdb-$mid"
                }
                if (unmatched == 0 && missing.isEmpty() && cols.size == 1) {
                    val (cid, cname) = cols.entries.first()
                    folderTarget = Naming.buildCollectionName(cname, cid)
                    groupKind = "collection"; groupTitle = cname; groupTmdb = cid
                } else {
                    val why = when {
                        unmatched > 0 -> "有 $unmatched 个视频没匹配上 TMDB"
                        missing.isNotEmpty() -> "这些电影不属于任何 TMDB 合集：${missing.joinToString()}"
                        else -> "电影分属不同合集"
                    }
                    flags += "文件夹「${folder.fileName}」$why，文件夹名保持不变；已为每部电影单独建文件夹"
                }
            }
            else -> if (unmatched > 0) flags += "文件夹「${folder.fileName}」有 $unmatched 个视频没匹配上 TMDB，文件夹名保持不变"
        }

        val moved = mutableSetOf<String>()
        val keep = mutableSetOf<String>()
        val itemIds = mutableListOf<String>()
        fun companionsOf(v: MovieVid) = v.node.items.filter { c ->
            isCompanion(c) && Naming.splitNameExt(c.fileName).first.lowercase().let { s ->
                s == v.stem.lowercase() || s.startsWith(v.stem.lowercase() + ".") || s.startsWith(v.stem.lowercase() + "-") ||
                    s.startsWith(v.stem.lowercase() + "_")
            }
        }
        if (perMovie) {
            // 合集 / 多部电影：每部一个 `名称(年份){tmdb-ID}` 文件夹，视频 + 字幕 + 同名图片 / NFO 一起移进去
            for (mid in distinct) {
                val group = matched.filter { it.info?.id == mid }
                val info = group.first().info!!
                val name = Naming.buildFolderName(info.title, info.year, info.id)
                val existing = tree.firstOrNull { it.parent === root && it.entry.fileName.equals(name, true) }
                val mk = if (existing == null) aid() else null
                if (mk != null) actions += PlanAction(mk, "mkdir", gidBase, parentId = folder.fileId, newName = name, kind = "folder",
                    mediaKind = "movie", tmdbId = info.id, note = "每部电影单独一个文件夹")
                else keep += existing!!.id
                val companions = mutableListOf<CloudEntry>()
                for (v in group) {
                    if (existing != null && v.node === existing) continue
                    actions += moveAct(gidBase, v.e, v.node.id, v.newName, existing?.id, name, mk, "file", "移进电影文件夹 $name")
                    moved += v.e.fileId
                    for ((s, sn) in subsOf[v].orEmpty()) {
                        actions += moveAct(gidBase, s, v.node.id, sn, existing?.id, name, mk, "subtitle", "字幕跟随电影移动")
                        moved += s.fileId
                    }
                    for (c in companionsOf(v)) {
                        val rest = Naming.splitNameExt(c.fileName).first.substring(v.stem.length)
                        val cn = Naming.claim(v.newBase + rest + Naming.splitNameExt(c.fileName).second, c.fileName, taken) ?: c.fileName
                        if (cn != c.fileName) actions += PlanAction(aid(), "rename", gidBase, c.fileId, v.node.id, c.fileName, cn, kind = "companion")
                        actions += moveAct(gidBase, c, v.node.id, cn, existing?.id, name, mk, "companion", "同名图片 / NFO 跟随电影移动")
                        moved += c.fileId
                        companions += c.copy(fileName = cn)
                    }
                }
                planMovieArtwork(existing?.id, info.id, info.posterPath, gidBase, actions, itemIds,
                    companions + (existing?.items ?: emptyList()), destRef = mk, destLabel = name)
            }
        } else if (matched.isNotEmpty()) {
            // 单部电影：子目录里的视频移到电影文件夹根下
            for (v in matched) {
                if (v.node === root) continue
                actions += moveAct(gidBase, v.e, v.node.id, v.newName, folder.fileId, folder.fileName, null, "file", "从 ${v.node.pathFrom(root)} 移到电影文件夹根下")
                moved += v.e.fileId
                for ((s, sn) in subsOf[v].orEmpty()) {
                    actions += moveAct(gidBase, s, v.node.id, sn, folder.fileId, folder.fileName, null, "subtitle", "字幕跟随视频移动")
                    moved += s.fileId
                }
                for (c in companionsOf(v)) {
                    actions += moveAct(gidBase, c, v.node.id, c.fileName, folder.fileId, folder.fileName, null, "companion", "同名图片 / NFO 跟随视频移动")
                    moved += c.fileId
                }
            }
        }
        planLeftovers(root, tree, keep, moved, gidBase, actions)

        if (folderTarget != null && folderTarget != folder.fileName) {
            if (folderTarget.lowercase() in rootTaken && folderTarget.lowercase() != folder.fileName.lowercase()) {
                flags += "文件夹目标名「$folderTarget」已被占用，「${folder.fileName}」保持不变"
            } else {
                actions += PlanAction(aid(), "rename", gidBase, folder.fileId, folder.parentFileId,
                    folder.fileName, folderTarget, kind = "folder", mediaKind = groupKind, tmdbId = groupTmdb)
                rootTaken.remove(folder.fileName.lowercase())
                rootTaken += folderTarget.lowercase()
            }
        }
        if (groupKind == "movie" && groupTmdb != null && unmatched == 0) {
            val movedIn = tree.drop(1).flatMap { n -> n.items.filter { it.fileId in moved } }
            planMovieArtwork(folder.fileId, groupTmdb, poster, gidBase, actions, itemIds, root.items + movedIn)
        }
        val mismatch = forced == null && remembered == null && groupKind == "movie" && groupTmdb != null &&
            folderTitleMismatch(folder.fileName, groupTitle)
        val conf = when {
            forced != null -> "手动"
            remembered != null -> "历史"
            groupTmdb == null -> null
            doneId != null -> "高"
            mismatch -> "低"
            else -> "中"
        }
        val warn = if (mismatch) "文件夹名与文件名不一致：匹配到《$groupTitle》" else null
        if (warn != null) flags += "「${folder.fileName}」$warn，请确认或点分组重新匹配"
        val ids = actions.subList(start, actions.size).filter { it.groupId == gidBase }.map { it.id }
        groups += PlanGroup(gidBase, groupKind, groupTmdb, groupTitle, groupYear, poster,
            folder.fileId, folder.fileName,
            folderTarget?.takeIf { actions.any { a -> a.groupId == gidBase && a.op == "rename" && a.fileId == folder.fileId } }, ids,
            confidence = conf, warn = warn,
            note = if (perMovie) "${distinct.size} 部电影，各自单独建文件夹" else null)
        return allVideos.size
    }

    private suspend fun planDirAsShowOrMovie(
        folder: CloudEntry, items: List<CloudEntry>, defaultSeason: Int?,
        actions: MutableList<PlanAction>, groups: MutableList<PlanGroup>, flags: MutableList<String>,
        rootTaken: MutableSet<String>, renameFolder: Boolean,
    ): Int {
        if (!visited.add(folder.fileId)) return 0
        // season folder at root: resolve from episodes
        val videos = items.filter { isVideo(it) }
        if (videos.isEmpty()) return 0
        val names = videos.take(1).map { Naming.splitNameExt(it.fileName).first }
        val show = tmdb.show(names, null).first
        if (show == null) {
            flags += "季文件夹「${folder.fileName}」无法匹配剧集"
            return videos.size
        }
        val gid = "tv-${show.id}-${folder.fileId}"
        val ids = planDir(items, show, defaultSeason, gid, actions, flags)
        groups += PlanGroup(gid, "tv", show.id, show.title, show.year, show.posterPath,
            folder.fileId, folder.fileName, null, ids, note = "季文件夹（不改名）")
        return videos.size
    }

    /**
     * 分类目录里的散文件：
     * - 电影：每部单独建 `名称(年份){tmdb-ID}` 文件夹，视频 + 同名字幕 / 图片 / NFO 一起移进去，并补海报 / NFO
     * - 剧集单集：只改名
     */
    private suspend fun planLooseFiles(
        dir: CloudEntry,
        items: List<CloudEntry>,
        actions: MutableList<PlanAction>,
        groups: MutableList<PlanGroup>,
        flags: MutableList<String>,
        taken: MutableSet<String>,
        forced: Pair<String?, MatchInfo>? = null,
        sharedMkdirs: List<PlanAction> = emptyList(),
    ): Int {
        var seen = 0
        val folderMade = HashMap<Int, Pair<String?, String?>>() // tmdbId → (existing dir id, mkdir id)
        sharedMkdirs.filter { it.parentId == dir.fileId && it.tmdbId != null }.forEach {
            folderMade[it.tmdbId!!] = null to it.id
        }
        for (it in items) {
            if (!isVideo(it) || (forced != null && it.fileId != forced.first)) continue
            val (stem, ext) = Naming.splitNameExt(it.fileName)
            seen++
            val ep = Naming.parseEpisode(stem)
            val info = forced?.second ?: if (ep != null) tmdb.show(listOf(stem), null).first else tmdb.movie(stem, emptyList(), null)
            if (info == null) {
                val gid = "unmatched-loose-${it.fileId}"
                groups += PlanGroup(gid, "unmatched", title = it.fileName, note = "散文件未匹配")
                actions += PlanAction(aid(), "unmatched", gid, it.fileId, it.parentFileId ?: dir.fileId, it.fileName, kind = "file")
                continue
            }
            val media = MediaParse.parseMedia(stem)
            val gid = "${info.kind}-${info.id}-loose-${it.fileId}"
            val start = actions.size
            val parent = it.parentFileId ?: dir.fileId
            if (info.kind == "tv") {
                if (ep == null) {
                    actions += PlanAction(aid(), "unmatched", gid, it.fileId, parent, it.fileName,
                        kind = "file", mediaKind = "tv", tmdbId = info.id, note = "认不出集数，请先补充 SxxEyy 后重新扫描")
                    groups += PlanGroup(gid, "tv", info.id, info.title, info.year, info.posterPath,
                        itemIds = actions.subList(start, actions.size).map { a -> a.id }, note = "无集数，未操作")
                    continue
                }
                val season = ep.first ?: 1
                val base = Naming.buildEpisodeBase(info.title, info.year, season, ep.second, media, info.id)
                val target = Naming.claim(base + ext, it.fileName, taken) ?: continue
                actions += PlanAction(aid(), if (target == it.fileName) "skip" else "rename", gid, it.fileId, parent, it.fileName, target,
                    kind = "file", mediaKind = info.kind, tmdbId = info.id)
                groups += PlanGroup(gid, info.kind, info.id, info.title, info.year, info.posterPath,
                    itemIds = actions.subList(start, actions.size).map { a -> a.id }, note = "散文件（剧集单集，只改名）")
                continue
            }
            val base = Naming.buildMovieBase(info.title, info.year, media, stem, info.id)
            val name = Naming.buildFolderName(info.title, info.year, info.id)
            val existingDir = items.firstOrNull { d -> d.isDir && d.fileName.equals(name, true) }
            val (destId, mk) = folderMade.getOrPut(info.id) {
                if (existingDir != null) existingDir.fileId to null
                else {
                    val id = aid()
                    actions += PlanAction(id, "mkdir", gid, parentId = dir.fileId, newName = name, kind = "folder",
                        mediaKind = "movie", tmdbId = info.id, note = "散落的电影单独建文件夹")
                    taken += name.lowercase()
                    null to id
                }
            }
            val target = base + ext
            if (target != it.fileName) actions += PlanAction(aid(), "rename", gid, it.fileId, parent, it.fileName, target,
                kind = "file", mediaKind = "movie", tmdbId = info.id)
            actions += moveAct(gid, it, parent, target, destId, name, mk, "file", "移进电影文件夹 $name")
            val companions = mutableListOf<CloudEntry>()
            for (c in items) {
                if (c.isDir || c.fileId == it.fileId) continue
                val (cs, cext) = Naming.splitNameExt(c.fileName)
                val lc = cs.lowercase(); val ls0 = stem.lowercase()
                if (!(lc == ls0 || lc.startsWith("$ls0.") || lc.startsWith("$ls0-") || lc.startsWith("${ls0}_"))) continue
                if (!(isSub(c) || isCompanion(c))) continue
                val rest = cs.substring(stem.length)
                val cn = base + rest + cext
                val kind = if (isSub(c)) "subtitle" else "companion"
                if (cn != c.fileName) actions += PlanAction(aid(), "rename", gid, c.fileId, parent, c.fileName, cn, kind = kind)
                actions += moveAct(gid, c, parent, cn, destId, name, mk, kind, "跟随电影移动")
                if (kind == "companion") companions += c.copy(fileName = cn)
            }
            val ids = mutableListOf<String>()
            planMovieArtwork(destId, info.id, info.posterPath, gid, actions, ids, companions, destRef = mk, destLabel = name)
            groups += PlanGroup(gid, info.kind, info.id, info.title, info.year, info.posterPath,
                folderName = name, itemIds = actions.subList(start, actions.size).map { a -> a.id },
                note = "散落的电影：单独建文件夹并移入")
        }
        return seen
    }

    /** 标题文件夹里常见的附属子目录（不代表“这是分类父目录”）。 */
    private val EXTRA_DIRS = setOf(
        "extras", "extra", "featurettes", "bonus", "sample", "samples", "subs", "subtitles", "sub",
        "trailers", "behind the scenes", "other", "others", "scans", "字幕", "花絮", "特典", "sps", "sp",
    )

    /**
     * 选中的文件夹本身是不是“一部片子”的文件夹。
     * - 有季文件夹 → 是（剧集）
     * - 有其它普通子文件夹（如「电影」下既有散落的片子也有别的片子文件夹）→ 不是，按父目录处理，绝不改它的名字
     * - 只有视频（加字幕 / 花絮目录）→ 是
     */
    internal fun looksLikeTitleFolder(items: List<CloudEntry>): Boolean {
        val videos = items.filter { !it.isDir && Naming.splitNameExt(it.fileName).second.lowercase() in Naming.VIDEO_EXT }
        val seasonDirs = items.count { it.isDir && Naming.isSeasonDir(it.fileName) }
        if (seasonDirs > 0) return true
        val otherDirs = items.filter { it.isDir && !Naming.isSeasonDir(it.fileName) && it.fileName.trim().lowercase() !in EXTRA_DIRS }
        if (otherDirs.isNotEmpty()) return false
        return videos.isNotEmpty()
    }

    private suspend fun planDir(
        items: List<CloudEntry>,
        show: MatchInfo,
        defaultSeason: Int?,
        gid: String,
        actions: MutableList<PlanAction>,
        flags: MutableList<String>,
    ): List<String> {
        val taken = items.map { it.fileName.lowercase() }.toMutableSet()
        val ids = mutableListOf<String>()
        val videos = mutableListOf<VideoPlan>()
        for (it in items) {
            if (it.isDir) continue
            val (stem, ext) = Naming.splitNameExt(it.fileName)
            if (ext.lowercase() in Naming.SUB_EXT) continue
            if (ext.lowercase() !in Naming.VIDEO_EXT) continue
            val ep = Naming.parseEpisode(stem)
            if (ep == null) {
                flags += "剧集文件认不出集数，未改名：${it.fileName}"
                val id = aid()
                actions += PlanAction(id, "unmatched", gid, it.fileId, it.parentFileId, it.fileName, kind = "file", note = "无集数")
                ids += id
                continue
            }
            val season = ep.first ?: (defaultSeason ?: 1)
            val media = MediaParse.parseMedia(stem)
            // TV hdr uses parse_media which already set movie_hdr-compatible via MediaParse
            val base = Naming.buildEpisodeBase(show.title, show.year, season, ep.second, media, show.id)
            val target = Naming.claim(base + ext, it.fileName, taken)
            if (target == null) continue
            videos += VideoPlan(stem, Naming.splitNameExt(target).first, season, ep.second, it)
            if (target == it.fileName) {
                val id = aid()
                actions += PlanAction(id, "skip", gid, it.fileId, it.parentFileId, it.fileName, target,
                    kind = "file", mediaKind = "tv", tmdbId = show.id, season = season, episode = ep.second)
                ids += id
            } else {
                val id = aid()
                actions += PlanAction(id, "rename", gid, it.fileId, it.parentFileId, it.fileName, target,
                    kind = "file", mediaKind = "tv", tmdbId = show.id, season = season, episode = ep.second)
                ids += id
            }
        }
        for (it in items) {
            if (it.isDir) continue
            val (stem, ext) = Naming.splitNameExt(it.fileName)
            if (ext.lowercase() !in Naming.SUB_EXT) continue
            var match: VideoPlan? = null
            var lang = ""
            for (key in listOf("old", "new")) {
                for (v in videos.sortedByDescending { if (key == "old") it.oldStem.length else it.newBase.length }) {
                    val k = if (key == "old") v.oldStem else v.newBase
                    if (stem.lowercase().startsWith(k.lowercase())) {
                        val rest = stem.substring(k.length)
                        if (rest.isEmpty() || rest[0] in ".-_ ") {
                            match = v; lang = rest.trim('.', '-', '_', ' ').replace(' ', '.'); break
                        }
                    }
                }
                if (match != null) break
            }
            if (match == null) {
                val ep = Naming.parseEpisode(stem)
                if (ep != null) {
                    val s = ep.first ?: (defaultSeason ?: 1)
                    match = videos.firstOrNull { it.episode == ep.second && it.season == s }
                } else if (videos.size == 1) match = videos[0]
                lang = Naming.langSuffix(stem)
            }
            if (match == null) {
                flags += "字幕找不到对应的视频，未改名：${it.fileName}"
                continue
            }
            val target = Naming.claim(match.newBase + (if (lang.isNotEmpty()) ".$lang" else "") + ext, it.fileName, taken) ?: continue
            if (target == it.fileName) continue
            val id = aid()
            actions += PlanAction(id, "rename", gid, it.fileId, it.parentFileId, it.fileName, target, kind = "subtitle")
            ids += id
        }
        return ids
    }

    private data class VideoPlan(val oldStem: String, val newBase: String, val season: Int, val episode: Int, val item: CloudEntry)

    internal fun planMovieArtwork(
        folderId: String?, tmdbId: Int, posterPath: String?, gid: String,
        actions: MutableList<PlanAction>, itemIds: MutableList<String>, items: List<CloudEntry>,
        destRef: String? = null, destLabel: String? = null,
    ) {
        val files = items.filter { !it.isDir }.map { it.fileName.lowercase() }
        val img = Regex("""\.(jpg|jpeg|png|webp)$""")
        val hasPoster = files.any {
            img.containsMatchIn(it) && (it.substringBeforeLast('.') in setOf("poster", "folder", "cover", "movie") ||
                it.substringBeforeLast('.').endsWith("-poster"))
        }
        val hasFanart = files.any {
            img.containsMatchIn(it) && (it.substringBeforeLast('.') in setOf("fanart", "backdrop", "background") ||
                it.substringBeforeLast('.').endsWith("-fanart"))
        }
        val hasNfo = files.any { it.endsWith(".nfo") && it !in setOf("season.nfo", "tvshow.nfo") }
        fun add(name: String, kind: String, url: String? = null) {
            val id = aid()
            actions += PlanAction(id, "artwork", gid, parentId = folderId, newName = name, kind = kind,
                mediaKind = "movie", tmdbId = tmdbId, artworkUrl = url, destRef = destRef, destLabel = destLabel)
            itemIds += id
        }
        if (!hasPoster) add("poster.jpg", "image", posterPath?.let { "https://image.tmdb.org/t/p/w780$it" })
        if (!hasFanart) add("fanart.jpg", "image")
        if (!hasNfo) add("movie.nfo", "movie_nfo")
    }

    private fun planArtwork(
        folderId: String, folderName: String, show: MatchInfo, gid: String,
        actions: MutableList<PlanAction>, itemIds: MutableList<String>, items: List<CloudEntry>,
        newSeasonDirs: Map<Int, Pair<String, String>> = emptyMap(), // 季号 → (mkdir 操作 ID, 文件夹名)
        seasonItems: Map<String, List<CloudEntry>> = emptyMap(),
    ) {
        val have = items.map { it.fileName.lowercase() }.toSet()
        val equiv = mapOf(
            "poster.jpg" to setOf("poster.jpg", "poster.png", "folder.jpg", "folder.png", "cover.jpg", "cover.png", "show.jpg", "show.png"),
            "fanart.jpg" to setOf("fanart.jpg", "fanart.png", "background.jpg", "background.png"),
            "backdrop.jpg" to setOf("backdrop.jpg", "backdrop.png", "background.jpg", "background.png"),
            "logo.png" to setOf("logo.png", "logo.jpg", "clearlogo.png", "clearlogo.jpg"),
            "tvshow.nfo" to setOf("tvshow.nfo"),
        )
        fun missing(fn: String) = (equiv[fn] ?: setOf(fn)).none { it in have }
        fun addImage(fn: String, url: String?) {
            if (!missing(fn)) return
            // url 可空：执行时由 ArtworkFetcher 按文件名+tmdbId 再解析
            val id = aid()
            actions += PlanAction(id, "artwork", gid, parentId = folderId, newName = fn, kind = "image",
                artworkUrl = url, tmdbId = show.id)
            itemIds += id
        }
        addImage("poster.jpg", show.posterPath?.let { "https://image.tmdb.org/t/p/w780$it" })
        addImage("fanart.jpg", null)
        addImage("backdrop.jpg", null)
        addImage("logo.png", null)
        if (missing("tvshow.nfo")) {
            val id = aid()
            actions += PlanAction(id, "artwork", gid, parentId = folderId, newName = "tvshow.nfo", kind = "tvshow_nfo", tmdbId = show.id)
            itemIds += id
        }
        for (d in items.filter { it.isDir }) {
            val n = Naming.seasonFromName(d.fileName) ?: continue
            val posterName = if (n == 0) "season-specials-poster.jpg" else "season%02d-poster.jpg".format(n)
            if (posterName !in have && have.none { it.matches(Regex("""(?i)season0*$n(?:-poster)?\.(jpg|jpeg|png)""")) }) {
                val id = aid()
                actions += PlanAction(id, "artwork", gid, parentId = folderId, newName = posterName, kind = "image",
                    season = n, tmdbId = show.id, note = "季海报（执行时下载）")
                itemIds += id
            }
            // 目录树已读取季内容，直接核验，不需要额外网络请求。
            if (seasonItems[d.fileId].orEmpty().any { !it.isDir && it.fileName.equals("season.nfo", true) }) continue
            val id = aid()
            actions += PlanAction(id, "artwork", gid, parentId = d.fileId, newName = "season.nfo", kind = "season_nfo",
                season = n, tmdbId = show.id, note = "若季文件夹尚无 season.nfo")
            itemIds += id
        }
        // 执行阶段才新建的季文件夹：季海报放剧集根目录，season.nfo 等文件夹建好后上传进去
        for ((n, ref) in newSeasonDirs) {
            val posterName = if (n == 0) "season-specials-poster.jpg" else "season%02d-poster.jpg".format(n)
            if (posterName !in have && have.none { it.matches(Regex("""(?i)season0*$n(?:-poster)?\.(jpg|jpeg|png)""")) } && posterName !in have) {
                val pid = aid()
                actions += PlanAction(pid, "artwork", gid, parentId = folderId, newName = posterName, kind = "image",
                    season = n, tmdbId = show.id, note = "季海报（执行时下载）")
                itemIds += pid
            }
            val id = aid()
            actions += PlanAction(id, "artwork", gid, parentId = null, newName = "season.nfo", kind = "season_nfo",
                season = n, tmdbId = show.id, destRef = ref.first, destLabel = ref.second, note = "上传到新建的 ${ref.second}")
            itemIds += id
        }
    }
}
