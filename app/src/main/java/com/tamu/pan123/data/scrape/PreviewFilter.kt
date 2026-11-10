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

    fun statusCount(plan: ScrapePlan, s: Status): Int = aggregate(plan, State()).statusCount(s)

    fun opCount(plan: ScrapePlan, op: String): OpCount = aggregate(plan, State()).opCount(op)

    /** 要显示的操作格子：固定五个；计划里有「删除多余文件夹」时追加「删除」。 */
    fun tileOps(plan: ScrapePlan): List<Pair<String, String>> = aggregate(plan, State()).tiles

    fun opLabel(op: String): String = (OPS + DELETE).firstOrNull { it.first == op }?.second ?: op

    /** 有筛选时列表上方的提示，例如「正在筛选：未匹配 · 2 项」；没有筛选时为 null。 */
    fun chipText(plan: ScrapePlan, st: State): String? = aggregate(plan, st).chipText

    /**
     * 要显示的分组（按计划顺序）。没有筛选时与原来一致：还有未执行项、或本来就没有操作的分组；
     * 有筛选时只留至少有一项符合的分组。
     */
    fun visibleGroupIds(plan: ScrapePlan, st: State): List<String> = aggregate(plan, st).visibleGroupIds

    /** 收起时显示在片名下面的摘要：「8 项 · 已选 8」；分组里没有可执行操作时说明未匹配 / 跳过的数量。 */
    fun groupSummary(plan: ScrapePlan, groupId: String, st: State): String = aggregate(plan, st).groupSummary(groupId)

    /** 选「未匹配」时，整组未匹配的分组排在前面（它们最需要处理）；其它情况保持计划顺序。 */
    private fun orderedGroups(plan: ScrapePlan, st: State): List<PlanGroup> =
        if (st.status == Status.UNMATCHED) plan.groups.sortedBy { if (it.kind == "unmatched") 0 else 1 } else plan.groups

    /** 一个分组的累计值（[aggregate] 里一次遍历填好）。 */
    internal class GroupAgg {
        var hasActions = false
        var hasPending = false
        var matched = 0
        var exec = 0
        var execSelected = 0
        var unmatched = 0
        var skipped = 0
    }

    /**
     * 预览页要用的全部计数（V35-3）：**一次**遍历 plan.actions 算出状态数、操作格子「已选/总数」、
     * 提示条的项数、要显示的分组和每组摘要。预览页用 remember(plan, state) 缓存，重组时不再反复全量扫描。
     * 与上面的单项函数语义完全一致（它们就是从这里取值）。
     */
    class Summary internal constructor(
        private val status: IntArray,
        private val ops: Map<String, OpCount>,
        val tiles: List<Pair<String, String>>,
        /** 符合当前筛选的项数（不含已执行）。 */
        val matchCount: Int,
        val visibleGroupIds: List<String>,
        /** 全部执行完成、已从预览里移除的分组数。 */
        val doneGroupCount: Int,
        val chipText: String?,
        private val groups: Map<String, GroupAgg>,
    ) {
        fun statusCount(s: Status): Int = status[s.ordinal]
        fun opCount(op: String): OpCount = ops[op] ?: OpCount(0, 0)
        fun groupSummary(groupId: String): String {
            val g = groups[groupId] ?: return "没有操作"
            if (g.exec > 0) return "${g.exec} 项 · 已选 ${g.execSelected}"
            return listOfNotNull(if (g.unmatched > 0) "${g.unmatched} 个未匹配" else null, if (g.skipped > 0) "${g.skipped} 个跳过" else null)
                .joinToString(" · ").ifEmpty { "没有操作" }
        }
    }

    fun aggregate(plan: ScrapePlan, st: State): Summary {
        val status = IntArray(Status.entries.size)
        val opKeys = (OPS + DELETE).map { it.first }.toSet()
        val opTotal = HashMap<String, Int>(); val opSel = HashMap<String, Int>()
        val groups = HashMap<String, GroupAgg>()
        var match = 0
        for (a in plan.actions) {
            val g = groups.getOrPut(a.groupId) { GroupAgg() }
            g.hasActions = true
            if (a.done) continue
            g.hasPending = true
            val s = statusOf(a)
            status[Status.ALL.ordinal]++
            status[s.ordinal]++
            val selected = PlanSelection.executable(a) && a.selected
            // 与 opMatches 相同：清理（kind=cleanup）单独算，其余按 op
            val key = if (a.kind == "cleanup") "cleanup" else a.op
            if (key in opKeys) {
                opTotal[key] = (opTotal[key] ?: 0) + 1
                if (selected) opSel[key] = (opSel[key] ?: 0) + 1
            }
            if ((st.status == Status.ALL || s == st.status) && (st.op == null || st.op == key)) {
                match++
                g.matched++
                when (s) {
                    Status.MATCHED -> { g.exec++; if (selected) g.execSelected++ }
                    Status.UNMATCHED -> g.unmatched++
                    Status.SKIPPED -> g.skipped++
                    Status.ALL -> Unit
                }
            }
        }
        val ops = opKeys.associateWith { OpCount(opSel[it] ?: 0, opTotal[it] ?: 0) }
        val tiles = if ((opTotal["delete"] ?: 0) > 0) OPS + DELETE else OPS
        val visible = orderedGroups(plan, st).filter { pg ->
            val g = groups[pg.id]
            if (!st.active) g == null || g.hasPending else g != null && g.matched > 0
        }.map { it.id }
        val done = plan.groups.count { pg -> groups[pg.id]?.let { it.hasActions && !it.hasPending } == true }
        val chip = if (!st.active) null else {
            val parts = buildList {
                if (st.status != Status.ALL) add(st.status.label)
                st.op?.let { add(opLabel(it)) }
            }
            "正在筛选：${parts.joinToString(" · ")} · $match 项"
        }
        return Summary(status, ops, tiles, match, visible, done, chip, groups)
    }
}
