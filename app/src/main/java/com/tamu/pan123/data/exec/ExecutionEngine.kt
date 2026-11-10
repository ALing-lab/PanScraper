package com.tamu.pan123.data.exec

import com.tamu.pan123.data.drive.CloudDrive
import com.tamu.pan123.data.drive.DriveRegistry
import com.tamu.pan123.data.drive.IdRemap
import com.tamu.pan123.data.scrape.PlanAction
import com.tamu.pan123.data.scrape.PlanStore
import com.tamu.pan123.data.scrape.ScrapePlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import com.tamu.pan123.data.net.NetLog

/**
 * 按计划执行改名 / 建文件夹 / 移动 / 上传海报。
 * 节奏约 5 次/秒；连续失败 3 次暂停。进度写入 TaskStore + 计划文件 done 标记。
 */
object ExecutionEngine {
    var OP_SLEEP_MS = 200L
    /** 上传海报 / NFO 失败时的自动重试等待（2s / 5s / 10s）。测试里可以设为 0。 */
    var UPLOAD_RETRY_MS: List<Long> = listOf(2_000, 5_000, 10_000)
    /** 结构操作（改名 / 新建 / 移动）全部完成后、开始上传前的等待：光鸭等网盘的改名 / 建文件夹是后台任务，需要一点时间生效。 */
    var SETTLE_MS = 2_000L
    const val MAX_CONSECUTIVE_FAILS = 3

    private val mutex = Mutex()
    /** 保护 _live / TaskStore 的读改写：通知栏按钮（暂停 / 结束）和执行循环可能同时更新状态。 */
    private val statusLock = Any()
    @Volatile private var pauseRequested = false
    @Volatile private var stopRequested = false

    private val _live = MutableStateFlow<TaskRun?>(null)
    val live: StateFlow<TaskRun?> = _live.asStateFlow()

    /** 可注入的网盘（测试用 Fake）。null = 按计划里的 accountId 取真实实现。 */
    @Volatile var driveOverride: CloudDrive? = null

    /** 测试用：替代 ArtworkFetcher（不访问 TMDB）。 */
    @Volatile var artworkOverride: (suspend (com.tamu.pan123.data.scrape.PlanAction) -> ByteArray)? = null

    fun pause() {
        pauseRequested = true
        updateStatus(TaskRun.STATUS_PAUSED, "已暂停")
    }

    fun resume() {
        if (pauseRequested) {
            pauseRequested = false
            updateStatus(TaskRun.STATUS_RUNNING, "继续执行…")
        }
    }

    fun stop() {
        stopRequested = true
        pauseRequested = false
        updateStatus(TaskRun.STATUS_STOPPING, "正在结束…", userStopped = true)
    }

    /** 启动失败（读不到计划 / 网盘账号不可用 / 没有可执行项）时，写一条任务记录让「任务」页显示原因，而不是静默无反应。 */
    fun reportLive(run: TaskRun?) { _live.value = run }

    fun reportStartError(message: String) {
        val run = TaskRun(
            id = UUID.randomUUID().toString().take(8),
            planName = PlanStore.latestFile()?.name ?: "plan",
            actionIds = emptyList(),
            status = TaskRun.STATUS_DONE,
            startedAt = System.currentTimeMillis(),
            finishedAt = System.currentTimeMillis(),
            message = message,
        )
        TaskStore.save(run)
        _live.value = run
    }

    private fun updateStatus(status: String, message: String, userStopped: Boolean = false) = synchronized(statusLock) {
        val cur = _live.value ?: TaskStore.load() ?: return@synchronized
        if (cur.status == TaskRun.STATUS_DONE) return@synchronized // 已结束的任务不再改状态
        val next = cur.copy(status = status, message = message, userStopped = cur.userStopped || userStopped)
        TaskStore.save(next)
        _live.value = next
    }

    /**
     * @param actionIds null = 所有 selected 且未 done 的可执行项；否则只跑这些 id
     */
    suspend fun run(
        plan: ScrapePlan = PlanStore.load(),
        actionIds: List<String>? = null,
        resumeExisting: Boolean = false,
        /** 进程被系统杀掉后恢复一个「已暂停」的任务：恢复后仍保持暂停，等用户点继续。 */
        startPaused: Boolean = false,
    ): TaskSummary = mutex.withLock {
        withContext(Dispatchers.IO) {
            stopRequested = false
            pauseRequested = startPaused
            val drive = driveOverride ?: DriveRegistry.forAccount(plan.accountId)

            val existing = if (resumeExisting) TaskStore.load() else null
            val ids = when {
                actionIds != null -> actionIds
                existing != null && existing.status in listOf(TaskRun.STATUS_RUNNING, TaskRun.STATUS_PAUSED, TaskRun.STATUS_STOPPING) ->
                    existing.actionIds.filter { id ->
                        plan.actions.any { it.id == id && !it.done }
                    }
                else -> plan.actions.filter {
                    it.selected && !it.done && it.op in EXEC_OPS
                }.map { it.id }
            }
            val known = plan.actions.mapTo(HashSet()) { it.id }
            val missing = ids.count { it !in known }
            if (missing > 0) {
                NetLog.error("exec", "$missing 个操作在当前计划里找不到（计划已被重新生成？）")
                if (missing == ids.size) {
                    reportStartError("计划已变化（例如刚重新匹配过），所选操作在最新计划里找不到。请回到预览重新勾选后再执行")
                    return@withContext TaskSummary(0, 0, 0, 0)
                }
            }
            if (ids.isEmpty()) {
                reportStartError("没有可执行的操作：所选项目都已完成、被跳过或未勾选")
                return@withContext TaskSummary(0, 0, 0, 0)
            }

            val previous = existing ?: TaskStore.load()
            val started = existing?.startedAt?.takeIf { it > 0 } ?: System.currentTimeMillis()
            var run = TaskRun(
                id = existing?.id ?: UUID.randomUUID().toString().take(8),
                planName = PlanStore.latestFile()?.name ?: "plan",
                actionIds = ids,
                status = TaskRun.STATUS_RUNNING,
                startedAt = started,
                ok = existing?.ok ?: 0,
                fail = existing?.fail ?: 0,
                skipped = existing?.skipped ?: 0,
                consecutiveFails = 0,
                message = "开始执行…",
                mkdirRefs = existing?.mkdirRefs ?: emptyMap(),
                idRemap = existing?.idRemap ?: emptyMap(),
                accountId = drive.accountId,
                failedIds = existing?.failedIds ?: emptyList(),
            )
            persist(run)

            val refs = run.mkdirRefs.toMutableMap()
            val remap = IdRemap(run.idRemap, pathMode = drive.kind.idsArePaths)
            val failed = run.failedIds.toMutableList()
            // 成功 / 失败 / 跳过的数量一律从 items 里数（counted），不再另外累加，避免继续执行 / 重试时重复计数
            var streak = 0
            val newlyOk = mutableListOf<String>()
            var stoppedOnFails = false
            var stoppedEarly = false
            val planActions = plan.actions.associateBy { it.id }.toMutableMap()
            val ordered = executionOrder(ids, planActions)
            var settled = ordered.none { planActions[it]?.op != "artwork" }
            val groupNames = plan.groups.associate { g -> g.id to g.title.ifBlank { g.folderName } }
            val groupFolders = plan.groups.associate { g -> g.id to (g.folderNewName ?: g.folderName) }
            // 二级列表：重试失败项时沿用上一次的列表，只把重试的项重置为「等待」
            val prevItems = existing?.items?.takeIf { it.isNotEmpty() } ?: previous?.takeIf { prev ->
                prev.status == TaskRun.STATUS_DONE && prev.items.isNotEmpty() && ids.all { id -> prev.items.any { it.id == id } }
            }?.items
            val items = (prevItems?.toMutableList() ?: mutableListOf())
            val pos = HashMap<String, Int>()
            items.forEachIndexed { i, it -> pos[it.id] = i }
            for (id in ordered) {
                val a = planActions[id]
                val fresh = a?.let { itemOf(it, groupNames[it.groupId].orEmpty(), groupFolders[it.groupId].orEmpty()) }
                    ?: TaskItem(id, "unknown", phase = ExecPhases.COUNT, error = "计划里找不到这一项")
                val i = pos[id]
                if (i == null) { pos[id] = items.size; items += fresh.copy(status = TaskItem.WAIT) }
                else if (items[i].status == TaskItem.OK && a?.done == true) Unit // 已完成的保持「成功」，不重做
                else items[i] = fresh.copy(status = TaskItem.WAIT, attempts = items[i].attempts)
            }
            if (prevItems == null) items.sortBy { ordered.indexOf(it.id) }
            fun setItem(id: String, f: (TaskItem) -> TaskItem) { pos[id]?.let { items[it] = f(items[it]) } }
            fun counted(r: TaskRun) = r.copy(
                items = items.toList(),
                ok = items.count { it.status == TaskItem.OK },
                fail = items.count { it.status == TaskItem.FAIL },
                skipped = items.count { it.status == TaskItem.SKIP },
                actionIds = items.map { it.id },
            )
            run = counted(run)
            persist(run)

            for (id in ordered) {
                if (stopRequested) {
                    stoppedEarly = true
                    break
                }
                while (pauseRequested) {
                    run = run.copy(status = TaskRun.STATUS_PAUSED, message = "已暂停", elapsedMs = System.currentTimeMillis() - started)
                    persist(run)
                    // 等待 resume：简单轮询
                    while (pauseRequested && !stopRequested) delay(200)
                    if (stopRequested) { stoppedEarly = true; break }
                    run = run.copy(status = TaskRun.STATUS_RUNNING, message = "继续执行…")
                    persist(run)
                }
                if (stoppedEarly) break

                val action = planActions[id]
                if (action == null) {
                    setItem(id) { it.copy(status = TaskItem.SKIP, at = System.currentTimeMillis(), note = "计划里找不到这一项") }
                    run = counted(run); persist(run); continue
                }
                if (action.done && items.getOrNull(pos[id] ?: -1)?.status == TaskItem.OK) continue // 继续 / 再次执行时跳过已成功的项
                if (action.done || action.op !in EXEC_OPS) {
                    setItem(id) { it.copy(status = TaskItem.SKIP, at = System.currentTimeMillis(), note = if (action.done) "之前已完成" else "不需要执行") }
                    run = counted(run); persist(run); continue
                }
                val phase = ExecPhases.of(action.op)
                setItem(id) { it.copy(status = TaskItem.RUNNING, at = System.currentTimeMillis(), attempts = it.attempts + 1) }
                run = counted(run).copy(currentActionId = id, phase = phase,
                    message = "${ExecPhases.label(phase)} · ${labelOf(action)}", elapsedMs = System.currentTimeMillis() - started)
                persist(run)

                if (action.op == "artwork" && !settled) {
                    settled = true
                    if (OP_SLEEP_MS > 0 && SETTLE_MS > 0) {
                        run = run.copy(message = "等待网盘处理改名 / 新建文件夹…")
                        persist(run)
                        delay(SETTLE_MS)
                    }
                }
                var result = executeOne(drive, action, refs, remap)
                if (result.isFailure && action.op == "artwork") {
                    for ((i, wait) in UPLOAD_RETRY_MS.withIndex()) {
                        if (stopRequested) break
                        val why = result.exceptionOrNull()?.message ?: "未知错误"
                        NetLog.error("exec", "上传 ${action.newName} 失败（$why），${wait / 1000}s 后第 ${i + 1}/${UPLOAD_RETRY_MS.size} 次重试")
                        run = run.copy(message = "上传 ${action.newName} 失败，${wait / 1000} 秒后重试（${i + 1}/${UPLOAD_RETRY_MS.size}）")
                        persist(run)
                        delay(if (OP_SLEEP_MS == 0L) 0L else wait)
                        result = executeOne(drive, action, refs, remap)
                        if (result.isSuccess) break
                    }
                }
                delay(maxOf(OP_SLEEP_MS, if (OP_SLEEP_MS == 0L) 0L else drive.writeIntervalMs))

                if (result.isSuccess) {
                    streak = 0
                    failed.remove(id)
                    val existed = result.getOrNull() == "已存在"
                    if (!existed) newlyOk += id
                    setItem(id) { it.copy(status = if (existed) TaskItem.SKIP else TaskItem.OK, at = System.currentTimeMillis(), error = null,
                        note = if (existed) "网盘里已有同名文件，未覆盖" else null) }
                    markPlanAction(id) { it.copy(done = true, error = null, note = result.getOrNull()) }
                    planActions[id]?.let { planActions[id] = it.copy(done = true, error = null) }
                    // 若 mkdir，refs 已在 executeOne 写入
                    run = counted(run).copy(consecutiveFails = 0,
                        mkdirRefs = refs.toMap(), idRemap = remap.snapshot(), failedIds = failed.toList())
                } else {
                    streak++
                    if (id !in failed) failed += id
                    val err = result.exceptionOrNull()?.let { e -> e.message ?: e.javaClass.simpleName } ?: "未知错误"
                    NetLog.error("exec", "${labelOf(action)} 失败：$err")
                    markPlanAction(id) { it.copy(done = false, error = err) }
                    planActions[id]?.let { planActions[id] = it.copy(error = err) }
                    setItem(id) { it.copy(status = TaskItem.FAIL, at = System.currentTimeMillis(), error = err) }
                    run = counted(run).copy(consecutiveFails = streak,
                        mkdirRefs = refs.toMap(), idRemap = remap.snapshot(), failedIds = failed.toList(), message = "失败：$err")
                    persist(run)
                    if (streak >= MAX_CONSECUTIVE_FAILS) {
                        stoppedOnFails = true
                        pauseRequested = true
                        run = run.copy(status = TaskRun.STATUS_PAUSED, message = "连续失败 ${MAX_CONSECUTIVE_FAILS} 次，已暂停")
                        persist(run)
                        // 等待用户继续或结束
                        while (pauseRequested && !stopRequested) delay(200)
                        if (stopRequested) { stoppedEarly = true; break }
                        streak = 0
                        run = run.copy(status = TaskRun.STATUS_RUNNING, consecutiveFails = 0, message = "继续执行…")
                        persist(run)
                    }
                    continue
                }
                persist(run)
            }

            val elapsed = System.currentTimeMillis() - started
            run = counted(run)
            val ok = run.ok; val fail = run.fail; val skipped = run.skipped
            val summary = TaskSummary(
                ok = ok, fail = fail, skipped = skipped, elapsedMs = elapsed,
                failed = failed.mapNotNull { fid ->
                    val a = planActions[fid] ?: return@mapNotNull null
                    FailedItem(fid, a.op, labelOf(a), a.error ?: "")
                },
                stoppedEarly = stoppedEarly,
                stoppedOnFailures = stoppedOnFails,
            )
            run = run.copy(
                status = TaskRun.STATUS_DONE,
                finishedAt = System.currentTimeMillis(),
                elapsedMs = elapsed,
                mkdirRefs = refs.toMap(),
                idRemap = remap.snapshot(),
                failedIds = failed.toList(),
                currentActionId = null,
                phase = 0,
                message = buildString {
                    append("完成：成功 $ok · 失败 $fail")
                    if (skipped > 0) append(" · 跳过 $skipped")
                    if (stoppedEarly) append("（提前结束）")
                    if (stoppedOnFails) append("（曾因连续失败暂停）")
                    val reasons = failed.mapNotNull { planActions[it]?.error }.groupingBy { it }.eachCount()
                    if (reasons.isNotEmpty()) {
                        append("\n失败原因：")
                        append(reasons.entries.sortedByDescending { it.value }.take(3).joinToString("；") { (e, n) -> if (n > 1) "$e（$n 项）" else e })
                    }
                },
            )
            persist(run)
            runCatching { com.tamu.pan123.data.history.HistoryStore.recordRun(plan, planActions, newlyOk) }
                .onFailure { NetLog.error("history", it.message ?: "记录历史失败") }
            summary
        }
    }

    private val EXEC_OPS = setOf("rename", "mkdir", "move", "artwork", "delete")

    /**
     * 严格的执行顺序：① 全部重命名（先文件夹、后文件；外层文件夹先于内层）→ ② 新建文件夹 → ③ 移动 → ④ 上传海报 / NFO。
     * 同一阶段内保持计划顺序（例如先把视频移出旧 Season 文件夹，再把空的 Season 文件夹移进回收目录）。
     * 一次只做一项。改名不改变 123 / 115 / 光鸭的文件 ID；WebDAV 的路径变化由 IdRemap 按前缀换算。
     */
    fun executionOrder(ids: List<String>, actions: Map<String, PlanAction>): List<String> {
        fun rank(id: String): Int {
            val a = actions[id] ?: return 9
            val p = ExecPhases.of(a.op)
            return p * 2 + if (a.op == "rename" && a.kind != "folder") 1 else 0
        }
        fun depth(id: String): Int = actions[id]?.let { a ->
            if (a.op == "rename" && a.kind == "folder") (a.fileId ?: "").count { it == '/' } else 0
        } ?: 0
        return ids.withIndex().sortedWith(compareBy({ rank(it.value) }, { depth(it.value) }, { it.index })).map { it.value }
    }

    private fun itemOf(a: PlanAction, group: String, folder: String): TaskItem = TaskItem(
        id = a.id, op = a.op, kind = a.kind, phase = ExecPhases.of(a.op),
        from = when (a.op) { "mkdir", "artwork" -> ""; else -> a.oldName },
        to = when (a.op) { "move" -> a.destLabel ?: a.newName; "artwork" -> if (a.destLabel != null) "${a.destLabel}/${a.newName}" else a.newName; else -> a.newName },
        note = if (a.op == "delete") a.note else null,
        group = group,
        folder = folder.ifBlank { group },
    )

    private fun persist(run: TaskRun) = synchronized(statusLock) {
        // 用户点过「结束」就一直记着（进程被杀后重启服务时不会再自动恢复这个任务）
        val r = if (stopRequested && !run.userStopped && run.status != TaskRun.STATUS_DONE) run.copy(userStopped = true) else run
        TaskStore.save(r)
        _live.value = r
    }

    private fun markPlanAction(id: String, transform: (PlanAction) -> PlanAction) {
        runCatching {
            PlanStore.update { plan ->
                plan.copy(actions = plan.actions.map { if (it.id == id) transform(it) else it })
            }
        }
    }

    private fun labelOf(a: PlanAction): String = when (a.op) {
        "rename" -> "改名 ${a.oldName} → ${a.newName}"
        "mkdir" -> "新建 ${a.newName}"
        "move" -> "移动 ${a.oldName} → ${a.destLabel ?: a.destParentId}"
        "artwork" -> "上传 ${a.newName}"
        "delete" -> "删除 ${a.oldName}"
        else -> a.op
    }

    private suspend fun executeOne(
        drive: CloudDrive,
        action: PlanAction,
        refs: MutableMap<String, String>,
        remap: IdRemap,
    ): Result<String> {
        return try {
            when (action.op) {
                "rename" -> {
                    val fid = remap.resolve(action.fileId) ?: error("缺少 fileId")
                    val newId = drive.rename(fid, com.tamu.pan123.data.naming.Naming.execName(action.newName)).getOrThrow()
                    remap.record(fid, newId)
                    action.fileId?.let { remap.record(it, newId) }
                    Result.success("ok")
                }
                "mkdir" -> {
                    val parent = remap.resolve(action.parentId) ?: error("缺少 parentId")
                    val name = com.tamu.pan123.data.naming.Naming.execName(action.newName)
                    val fid = drive.mkdir(parent, name).getOrThrow()
                    // 以计划里的原始 parentId 作键，便于 move 按 destLabel 找到
                    refs["${action.parentId}:$name"] = fid
                    refs["action:${action.id}"] = fid
                    Result.success("id=$fid")
                }
                "move" -> {
                    val fid = remap.resolve(action.fileId) ?: error("缺少 fileId")
                    val dest = remap.resolve(resolveDest(action, refs)) ?: error("目标文件夹还没建好")
                    val newId = drive.move(fid, dest).getOrThrow()
                    remap.record(fid, newId)
                    action.fileId?.let { remap.record(it, newId) }
                    Result.success("ok")
                }
                "artwork" -> {
                    val parent = (if (action.destRef != null) refs["action:${action.destRef}"]?.let { remap.resolve(it) }
                        else remap.resolve(action.parentId)) ?: error(if (action.destRef != null) "目标文件夹 ${action.destLabel ?: ""} 还没建好" else "缺少 parentId")
                    val name = com.tamu.pan123.data.naming.Naming.execName(action.newName)
                    // 已存在则跳过（不覆盖）
                    val exists = runCatching { drive.exists(parent, name) }.getOrDefault(false)
                    if (exists) return Result.success("已存在")
                    val bytes = artworkOverride?.invoke(action) ?: ArtworkFetcher.contentFor(action)
                    val msg = drive.upload(parent, name, bytes).getOrThrow()
                    Result.success(msg)
                }
                "delete" -> {
                    if (action.blocked != null) error(action.blocked)
                    if (drive.trashSupport == com.tamu.pan123.data.drive.TrashSupport.NONE)
                        error(com.tamu.pan123.data.drive.TrashSupport.noneHint(drive.kind))
                    val fid = remap.resolve(action.fileId) ?: error("缺少 fileId")
                    // 安全检查：递归检查文件夹里（所有层级）还有没有视频；检查不完（太大 / 太深）就不删
                    val scan = countVideosBounded(drive, fid)
                    if (!scan.complete) error(DELETE_TOO_BIG)
                    if (scan.videos > 0) error("文件夹里还有 ${scan.videos} 个视频（可能移动没成功），为安全起见未删除")
                    val msg = drive.trash(fid).getOrElse { e ->
                        throw IllegalStateException(com.tamu.pan123.data.drive.TrashSupport.explain(drive.kind, e.message ?: e.javaClass.simpleName), e)
                    }
                    Result.success(msg)
                }
                else -> Result.success("skip")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取消（结束任务 / 服务被销毁）要往上传，不能当成一次普通失败继续执行
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    const val DELETE_TOO_BIG = "文件夹太大，未检查完，已跳过删除"

    data class VideoScan(val videos: Int, val complete: Boolean)

    /**
     * 删除前的安全检查：广度优先递归数视频。超过 [maxDepth] 层、[maxEntries] 个条目或 [maxDirs] 个文件夹就停下，
     * 返回 complete=false（调用方据此拒绝删除）。
     */
    internal suspend fun countVideosBounded(
        drive: CloudDrive, folderId: String,
        maxDepth: Int = 8, maxEntries: Int = 5_000, maxDirs: Int = 300,
    ): VideoScan {
        var videos = 0
        var entries = 0
        var dirs = 0
        var frontier = listOf(folderId)
        var depth = 0
        while (frontier.isNotEmpty()) {
            if (depth > maxDepth) return VideoScan(videos, complete = false)
            val next = mutableListOf<String>()
            for (dir in frontier) {
                if (++dirs > maxDirs) return VideoScan(videos, complete = false)
                val kids = drive.list(dir)
                entries += kids.size
                if (entries > maxEntries) return VideoScan(videos, complete = false)
                for (k in kids) {
                    if (k.isDir) next += k.fileId
                    else if (com.tamu.pan123.data.naming.Naming.splitNameExt(k.fileName).second.lowercase() in com.tamu.pan123.data.naming.Naming.VIDEO_EXT) videos++
                }
            }
            frontier = next
            depth++
        }
        return VideoScan(videos, complete = true)
    }

    private fun resolveDest(action: PlanAction, refs: Map<String, String>): String? {
        action.destParentId?.let { return it }
        action.destRef?.let { ref -> return refs["action:$ref"] }
        val label = action.destLabel ?: return null
        val parent = action.parentId
        if (parent != null) refs["$parent:$label"]?.let { return it }
        // 在所有 refs 里按 name 后缀找
        return refs.entries.firstOrNull { it.key.endsWith(":$label") }?.value
    }
}
