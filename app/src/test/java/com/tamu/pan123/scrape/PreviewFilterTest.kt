package com.tamu.pan123.scrape

import com.tamu.pan123.data.scrape.PlanAction
import com.tamu.pan123.data.scrape.PlanGroup
import com.tamu.pan123.data.scrape.PreviewFilter
import com.tamu.pan123.data.scrape.PreviewFilter.Status
import com.tamu.pan123.data.scrape.ScrapePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 预刮削预览的两排筛选（上：状态；下：操作）。数字和设计稿 I-orange 一致。 */
class PreviewFilterTest {
    private var n = 0
    private fun act(op: String, g: String, kind: String = "file", selected: Boolean = true, done: Boolean = false) =
        PlanAction(id = "a${n++}", op = op, groupId = g, kind = kind, selected = selected, done = done)

    /** 雀骨 8（命名 4 + 海报 4）· 盗梦空间 3 · 黑暗骑士 9 · 清理 5（默认不勾）· 未匹配 2 · 跳过 1 · 已完成 1（不出现）。 */
    private val plan: ScrapePlan = run {
        val a = mutableListOf<PlanAction>()
        repeat(4) { a += act("rename", "tv-1") }; repeat(4) { a += act("artwork", "tv-1", "image") }
        a += act("rename", "movie-2"); repeat(2) { a += act("artwork", "movie-2", "image") }
        a += act("mkdir", "set-3", "folder"); repeat(3) { a += act("move", "set-3") }; a += act("rename", "set-3")
        repeat(4) { a += act("artwork", "set-3", "image") }
        repeat(3) { a += act("move", "tv-1", "cleanup", selected = false) }
        repeat(2) { a += act("mkdir", "tv-1", "cleanup", selected = false) }
        a += act("unmatched", "unmatched-4"); a += act("unmatched", "tv-1")
        a += act("skip", "movie-2")
        a += act("rename", "movie-2", done = true)
        ScrapePlan(
            groups = listOf(
                PlanGroup("tv-1", "tv", title = "雀骨"), PlanGroup("movie-2", "movie", title = "盗梦空间"),
                PlanGroup("set-3", "collection", title = "黑暗骑士（系列）"), PlanGroup("unmatched-4", "unmatched", title = "某部老电影"),
            ),
            actions = a,
        )
    }

    @Test fun statusCountsAreItemsAndSkipDone() {
        assertEquals(28, PreviewFilter.statusCount(plan, Status.ALL))
        assertEquals(25, PreviewFilter.statusCount(plan, Status.MATCHED))
        assertEquals(2, PreviewFilter.statusCount(plan, Status.UNMATCHED))
        assertEquals(1, PreviewFilter.statusCount(plan, Status.SKIPPED))
    }

    @Test fun opTilesShowSelectedOverTotal() {
        val c = PreviewFilter.OPS.associate { (k, _) -> k to PreviewFilter.opCount(plan, k) }
        assertEquals(PreviewFilter.OpCount(6, 6), c["rename"])
        assertEquals(PreviewFilter.OpCount(1, 1), c["mkdir"])
        assertEquals(PreviewFilter.OpCount(3, 3), c["move"])
        assertEquals(PreviewFilter.OpCount(10, 10), c["artwork"])
        assertEquals(PreviewFilter.OpCount(0, 5), c["cleanup"])
        // 已选合计 = 执行数
        assertEquals(20, c.values.sumOf { it.selected })
        assertEquals("6/6", c["rename"]!!.label)
    }

    @Test fun labelsAreTheUnifiedShortNames() {
        assertEquals(listOf("命名", "新建", "移动", "海报", "清理"), PreviewFilter.tileOps(plan).map { it.second })
        assertEquals(listOf("全部", "已匹配", "未匹配", "跳过"), Status.entries.map { it.label })
    }

    @Test fun deleteTileOnlyWhenPlanHasDeletes() {
        assertFalse(PreviewFilter.tileOps(plan).any { it.first == "delete" })
        val withDel = plan.copy(actions = plan.actions + act("delete", "tv-1", "folder", selected = false))
        assertTrue(PreviewFilter.tileOps(withDel).any { it.first == "delete" })
    }

    @Test fun defaultShowsEverythingNotDone() {
        val st = PreviewFilter.State()
        assertFalse(st.active)
        assertEquals(28, plan.actions.count { PreviewFilter.matches(it, st) })
    }

    @Test fun statusAndOpCombineWithAnd() {
        val st = PreviewFilter.toggleOp(PreviewFilter.selectStatus(PreviewFilter.State(), Status.MATCHED), "rename")
        assertEquals(Status.MATCHED, st.status); assertEquals("rename", st.op)
        val vis = plan.actions.filter { PreviewFilter.matches(it, st) }
        assertEquals(6, vis.size)
        assertTrue(vis.all { it.op == "rename" && !it.done })
        // 清理 不算进 新建/移动
        assertEquals(1, plan.actions.count { PreviewFilter.matches(it, PreviewFilter.State(op = "mkdir")) })
        assertEquals(5, plan.actions.count { PreviewFilter.matches(it, PreviewFilter.State(op = "cleanup")) })
    }

    @Test fun tappingSelectedTileAgainClearsIt() {
        val on = PreviewFilter.toggleOp(PreviewFilter.State(), "artwork")
        assertEquals("artwork", on.op)
        val off = PreviewFilter.toggleOp(on, "artwork")
        assertNull(off.op)
        assertEquals(Status.ALL, off.status)
        // 换一个格子 = 直接切换
        assertEquals("move", PreviewFilter.toggleOp(on, "move").op)
    }

    @Test fun unmatchedOrSkippedDisablesAndClearsOps() {
        val withOp = PreviewFilter.toggleOp(PreviewFilter.State(), "rename")
        val un = PreviewFilter.selectStatus(withOp, Status.UNMATCHED)
        assertNull(un.op)
        assertFalse(PreviewFilter.opsEnabled(un.status))
        assertFalse(PreviewFilter.opsEnabled(Status.SKIPPED))
        assertTrue(PreviewFilter.opsEnabled(Status.ALL)); assertTrue(PreviewFilter.opsEnabled(Status.MATCHED))
        // 灰掉时点格子没有反应
        assertEquals(un, PreviewFilter.toggleOp(un, "move"))
        // 切回 已匹配 不会自动恢复刚才的格子
        assertNull(PreviewFilter.selectStatus(un, Status.MATCHED).op)
        assertEquals(2, plan.actions.count { PreviewFilter.matches(it, un) })
    }

    @Test fun statusRowAlwaysHasOneSelectionAndClearGoesBackToAll() {
        val st = PreviewFilter.selectStatus(PreviewFilter.State(op = "move"), Status.SKIPPED)
        assertEquals(PreviewFilter.State(), PreviewFilter.clear())
        assertEquals(Status.ALL, PreviewFilter.clear().status)
        assertTrue(st.active)
    }

    @Test fun chipTextNamesFilterAndCount() {
        assertNull(PreviewFilter.chipText(plan, PreviewFilter.State()))
        assertEquals("正在筛选：未匹配 · 2 项", PreviewFilter.chipText(plan, PreviewFilter.State(Status.UNMATCHED)))
        assertEquals("正在筛选：命名 · 6 项", PreviewFilter.chipText(plan, PreviewFilter.State(op = "rename")))
        assertEquals("正在筛选：已匹配 · 海报 · 10 项", PreviewFilter.chipText(plan, PreviewFilter.State(Status.MATCHED, "artwork")))
    }

    @Test fun visibleGroupsFollowTheFilter() {
        assertEquals(listOf("unmatched-4", "tv-1"), PreviewFilter.visibleGroupIds(plan, PreviewFilter.State(Status.UNMATCHED)))
        assertEquals(listOf("set-3"), PreviewFilter.visibleGroupIds(plan, PreviewFilter.State(op = "mkdir")))
        assertEquals(listOf("tv-1", "movie-2", "set-3", "unmatched-4"), PreviewFilter.visibleGroupIds(plan, PreviewFilter.State()))
    }

    @Test fun filteringNeverChangesTicks() {
        val before = plan.actions.map { it.selected }
        listOf(PreviewFilter.State(Status.UNMATCHED), PreviewFilter.State(op = "cleanup"), PreviewFilter.State(Status.MATCHED, "rename"))
            .forEach { st -> plan.actions.filter { PreviewFilter.matches(it, st) }; PreviewFilter.chipText(plan, st); PreviewFilter.visibleGroupIds(plan, st) }
        assertEquals(before, plan.actions.map { it.selected })
        // 执行的仍是全部已勾选项（与筛选无关）
        assertEquals(20, com.tamu.pan123.data.scrape.PlanSelection.selectedIds(plan).size)
    }

    @Test fun collapsedSummary() {
        // 收起时的摘要只数可执行的操作（清理默认不勾：13 项里已选 8）
        assertEquals("13 项 · 已选 8", PreviewFilter.groupSummary(plan, "tv-1", PreviewFilter.State()))
        assertEquals("4 项 · 已选 4", PreviewFilter.groupSummary(plan, "tv-1", PreviewFilter.State(Status.MATCHED, "rename")))
        assertEquals("1 个未匹配", PreviewFilter.groupSummary(plan, "unmatched-4", PreviewFilter.State()))
        assertEquals("3 项 · 已选 3", PreviewFilter.groupSummary(plan, "movie-2", PreviewFilter.State(Status.MATCHED)))
        assertEquals("9 项 · 已选 9", PreviewFilter.groupSummary(plan, "set-3", PreviewFilter.State()))
    }

    /** V35-3：一次遍历的汇总与逐项计算完全一致（所有筛选组合）。 */
    @Test fun aggregateMatchesPerItemCountsInOnePass() {
        val states = Status.entries.flatMap { st -> (listOf<String?>(null) + PreviewFilter.OPS.map { it.first } + "delete").map { PreviewFilter.State(st, it) } }
        for (st in states) {
            val agg = PreviewFilter.aggregate(plan, st)
            for (s in Status.entries) assertEquals(plan.actions.count { !it.done && (s == Status.ALL || PreviewFilter.statusOf(it) == s) }, agg.statusCount(s))
            for ((k, _) in PreviewFilter.OPS) {
                val all = plan.actions.filter { PreviewFilter.opMatches(it, k) }
                assertEquals(PreviewFilter.OpCount(all.count { com.tamu.pan123.data.scrape.PlanSelection.executable(it) && it.selected }, all.size), agg.opCount(k))
            }
            val n = plan.actions.count { PreviewFilter.matches(it, st) }
            assertEquals(n, agg.matchCount)
            if (st.active) assertTrue(agg.chipText!!.endsWith("· $n 项")) else assertNull(agg.chipText)
            val expectVisible = (if (st.status == Status.UNMATCHED) plan.groups.sortedBy { if (it.kind == "unmatched") 0 else 1 } else plan.groups)
                .filter { g -> if (!st.active) plan.actions.none { it.groupId == g.id } || plan.actions.any { it.groupId == g.id && !it.done }
                               else plan.actions.any { it.groupId == g.id && PreviewFilter.matches(it, st) } }.map { it.id }
            assertEquals(st.toString(), expectVisible, agg.visibleGroupIds)
        }
        val all = PreviewFilter.aggregate(plan, PreviewFilter.State())
        assertEquals("13 项 · 已选 8", all.groupSummary("tv-1"))
        assertEquals("1 个未匹配", all.groupSummary("unmatched-4"))
        assertEquals("没有操作", all.groupSummary("nope"))
        assertEquals(PreviewFilter.OPS, all.tiles)
        assertEquals(0, all.doneGroupCount)
    }
}
