package com.tamu.pan123.data.exec

import kotlinx.serialization.Serializable

@Serializable
data class TaskRun(
    val id: String,
    val planName: String,
    val actionIds: List<String>,
    val status: String = STATUS_IDLE, // idle|running|paused|stopping|done
    val startedAt: Long = 0,
    val finishedAt: Long? = null,
    val ok: Int = 0,
    val fail: Int = 0,
    val skipped: Int = 0,
    val consecutiveFails: Int = 0,
    val currentActionId: String? = null,
    val message: String = "",
    /** parentId:name → 新建文件夹的 FileId */
    val mkdirRefs: Map<String, String> = emptyMap(),
    /** 路径型网盘（WebDAV）改名 / 移动后的 旧ID → 新ID。 */
    val idRemap: Map<String, String> = emptyMap(),
    val accountId: String = "p123-main",
    val failedIds: List<String> = emptyList(),
    val elapsedMs: Long = 0,
    /** 按执行顺序排列的每一项（任务页二级列表用）。 */
    val items: List<TaskItem> = emptyList(),
    /** 当前阶段 1..4（0 = 未开始 / 已结束）。 */
    val phase: Int = 0,
    /** 用户点过「结束」。进程被系统杀掉、服务被重新拉起时，这样的任务不会自动恢复。 */
    val userStopped: Boolean = false,
    val planKey: String = "",
    val terminalError: String? = null,
) {
    val fullySucceeded: Boolean get() = status == STATUS_DONE && terminalError == null && !userStopped &&
        actionIds.isNotEmpty() && fail == 0 && items.none { it.status == TaskItem.WAIT || it.status == TaskItem.RUNNING }
    companion object {
        const val STATUS_IDLE = "idle"
        const val STATUS_RUNNING = "running"
        const val STATUS_PAUSED = "paused"
        const val STATUS_STOPPING = "stopping"
        const val STATUS_DONE = "done"
    }
}

@Serializable
data class TaskSummary(
    val ok: Int,
    val fail: Int,
    val skipped: Int,
    val elapsedMs: Long,
    val failed: List<FailedItem> = emptyList(),
    val stoppedEarly: Boolean = false,
    val stoppedOnFailures: Boolean = false,
)

@Serializable
data class FailedItem(
    val actionId: String,
    val op: String,
    val label: String,
    val error: String,
)

/** 任务里的一项操作（快照：计划之后被重新生成也能看到当时做了什么）。 */
@Serializable
data class TaskItem(
    val id: String,
    val op: String,
    val kind: String = "",
    val phase: Int,
    val from: String = "",
    val to: String = "",
    val group: String = "",
    /** 所在文件夹（失败页按它分组）。 */
    val folder: String = "",
    val status: String = WAIT,
    /** 开始 / 结束时间（毫秒）。 */
    val at: Long? = null,
    val error: String? = null,
    val note: String? = null,
    val attempts: Int = 0,
) {
    companion object {
        const val WAIT = "wait"
        const val RUNNING = "running"
        const val OK = "ok"
        const val FAIL = "fail"
        const val SKIP = "skip"
    }
}

/** 执行阶段：先全部改名，再新建文件夹，再移动，最后上传海报 / NFO。 */
object ExecPhases {
    const val COUNT = 5
    val names = listOf("重命名", "新建文件夹", "移动文件", "上传海报和 NFO", "删除多余文件夹")
    fun of(op: String): Int = when (op) { "rename" -> 1; "mkdir" -> 2; "move" -> 3; "delete" -> 5; else -> 4 }
    fun name(phase: Int): String = names.getOrElse(phase - 1) { "" }
    fun label(phase: Int): String = if (phase in 1..COUNT) "第 $phase/$COUNT 步：${name(phase)}" else ""
}

/** 失败页的勾选与重试：勾选移动会自动带上它依赖的、同样失败的新建文件夹；取消新建文件夹会一起取消依赖它的移动。 */
object FailedRetry {
    private fun deps(id: String, plan: com.tamu.pan123.data.scrape.ScrapePlan): List<String> {
        val a = plan.actions.firstOrNull { it.id == id } ?: return emptyList()
        val d = com.tamu.pan123.data.scrape.PlanSelection.dependencyOf(a, plan.actions) ?: return emptyList()
        return if (d.done) emptyList() else listOf(d.id)
    }

    private fun dependents(id: String, plan: com.tamu.pan123.data.scrape.ScrapePlan): List<String> =
        plan.actions.filter { m -> com.tamu.pan123.data.scrape.PlanSelection.dependencyOf(m, plan.actions)?.id == id }.map { it.id }

    fun toggle(selected: Set<String>, ids: Collection<String>, on: Boolean, failed: Set<String>,
               plan: com.tamu.pan123.data.scrape.ScrapePlan?): Set<String> {
        val out = selected.toMutableSet()
        if (on) {
            out += ids
            if (plan != null) ids.forEach { id -> deps(id, plan).filter { it in failed }.forEach { out += it } }
        } else {
            out -= ids.toSet()
            if (plan != null) ids.forEach { id -> dependents(id, plan).forEach { out -= it } }
        }
        return out.intersect(failed)
    }

    /** 实际要执行的 id：所选项 + 它们依赖的、尚未完成的新建文件夹（即使不在失败列表里）。 */
    fun retryIds(selected: Collection<String>, plan: com.tamu.pan123.data.scrape.ScrapePlan?): List<String> {
        if (plan == null) return selected.toList()
        val extra = selected.flatMap { deps(it, plan) }
        return (extra + selected).distinct()
    }
}
