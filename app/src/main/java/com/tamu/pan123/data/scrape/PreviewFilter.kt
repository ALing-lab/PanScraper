package com.tamu.pan123.data.scrape

/**
 * 预刮削预览的两排筛选（纯函数，便于测试；v0.3.5 / 设计稿 I-orange）。
 *
 * - 上面一排按「状态」：全部 / 已匹配 / 未匹配 / 跳过，总有一项选中，默认「全部」。
 * - 下面一排按「操作」：命名 / 新建 / 移动 / 海报 / 清理（计划里有删除时再加「删除」），
 *   可以不选或选一个；再点一次已选的格子就取消。
 * - 两排一起作用（状态 且 操作）。未匹配 / 跳过没有操作可筛：下面一排变灰，已选的格子自动取消。
 * - 数字的单位都是“项”（计划里的一条操作 / 一个文件）；已执行成功的项不算。
 * - 筛选只决定显示哪些，不改变勾选，执行的仍是全部已勾选项。
 */
object PreviewFilter {
    enum class Status(val label: String) { ALL("全部"), MATCHED("已匹配"), UNMATCHED("未匹配"), SKIPPED("跳过") }

    /** 操作格子：key 与 [opMatches] 对应，label 是统一的两字短名。 */
    val OPS: List<Pair<String, String>> = listOf(
        "rename" to "命名", "mkdir" to "新建", "move" to "移动", "artwork" to "海报", "cleanup" to "清理",
    )
    private val DELETE = "delete" to "删除"

    data class State(val status: Status = Status.ALL, val op: String? = null) {
        val active: Boolean get() = status != Status.ALL || op != null
    }

    data class OpCount(val selected: Int, val total: Int) {
        val label: String get() = "$selected/$total"
    }

    /** 同 [previewOpMatches]：清理旧文件（kind=cleanup）单独算，不算进新建 / 移动。 */
    fun opMatches(a: PlanAction, op: String): Boolean = !a.done && when (op) {
        "cleanup" -> a.kind == "cleanup"
        else -> a.kind != "cleanup" && a.op == op
    }

    fun statusOf(a: PlanAction): Status = when (a.op) {
        "unmatched" -> Status.UNMATCHED
        "skip" -> Status.SKIPPED
        else -> Status.MATCHED
    }

    fun opsEnabled(s: Status): Boolean = s == Status.ALL || s == Status.MATCHED

    fun selectStatus(st: State, s: Status): State = State(s, if (opsEnabled(s)) st.op else null)

    fun toggleOp(st: State, op: String): State =
        if (!opsEnabled(st.status)) st else st.copy(op = if (st.op == op) null else op)

    fun clear(): State = State()

    fun matches(a: PlanAction, st: State): Boolean =
        !a.done && (st.status == Status.ALL || statusOf(a) == st.status) && (st.op == null || opMatches(a, st.op))

    fun statusCount(plan: ScrapePlan, s: Status): Int =
        plan.actions.count { !it.done && (s == Status.ALL || statusOf(it) == s) }

    fun opCount(plan: ScrapePlan, op: String): OpCount {
        val all = plan.actions.filter { opMatches(it, op) }
        return OpCount(all.count { PlanSelection.executable(it) && it.selected }, all.size)
    }

    /** 要显示的操作格子：固定五个；计划里有「删除多余文件夹」时追加「删除」。 */
    fun tileOps(plan: ScrapePlan): List<Pair<String, String>> =
        if (plan.actions.any { opMatches(it, "delete") }) OPS + DELETE else OPS

    fun opLabel(op: String): String = (OPS + DELETE).firstOrNull { it.first == op }?.second ?: op

    /** 有筛选时列表上方的提示，例如「正在筛选：未匹配 · 2 项」；没有筛选时为 null。 */
    fun chipText(plan: ScrapePlan, st: State): String? {
        if (!st.active) return null
        val parts = buildList {
            if (st.status != Status.ALL) add(st.status.label)
            st.op?.let { add(opLabel(it)) }
        }
        val n = plan.actions.count { matches(it, st) }
        return "正在筛选：${parts.joinToString(" · ")} · $n 项"
    }

    /**
     * 要显示的分组（按计划顺序）。没有筛选时与原来一致：还有未执行项、或本来就没有操作的分组；
     * 有筛选时只留至少有一项符合的分组。
     */
    fun visibleGroupIds(plan: ScrapePlan, st: State): List<String> {
        val ordered = orderedGroups(plan, st)
        return if (!st.active) ordered.filter { g -> plan.actions.none { it.groupId == g.id } || plan.actions.any { it.groupId == g.id && !it.done } }.map { it.id }
        else ordered.filter { g -> plan.actions.any { it.groupId == g.id && matches(it, st) } }.map { it.id }
    }

    /** 选「未匹配」时，整组未匹配的分组排在前面（它们最需要处理）；其它情况保持计划顺序。 */
    private fun orderedGroups(plan: ScrapePlan, st: State): List<PlanGroup> =
        if (st.status == Status.UNMATCHED) plan.groups.sortedBy { if (it.kind == "unmatched") 0 else 1 } else plan.groups

    /** 收起时显示在片名下面的摘要：「8 项 · 已选 8」；分组里没有可执行操作时说明未匹配 / 跳过的数量。 */
    fun groupSummary(plan: ScrapePlan, groupId: String, st: State): String {
        val acts = plan.actions.filter { it.groupId == groupId && matches(it, st) }
        val exec = acts.filter { statusOf(it) == Status.MATCHED }
        if (exec.isNotEmpty()) return "${exec.size} 项 · 已选 ${exec.count { PlanSelection.executable(it) && it.selected }}"
        val un = acts.count { statusOf(it) == Status.UNMATCHED }
        val sk = acts.count { statusOf(it) == Status.SKIPPED }
        return listOfNotNull(if (un > 0) "$un 个未匹配" else null, if (sk > 0) "$sk 个跳过" else null).joinToString(" · ").ifEmpty { "没有操作" }
    }
}
