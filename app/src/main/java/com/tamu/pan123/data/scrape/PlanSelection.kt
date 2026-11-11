package com.tamu.pan123.data.scrape

/**
 * 预览页的勾选逻辑（纯函数，便于测试）。
 * - 只有可执行的操作（改名 / 新建 / 移动 / 海报）可以勾选
 * - 勾选「移动」会自动勾上它依赖的「新建文件夹」；取消「新建文件夹」会同时取消依赖它的「移动」
 */
object PlanSelection {
    val EXEC_OPS = setOf("rename", "mkdir", "move", "artwork", "delete")

    fun executable(a: PlanAction) = a.op in EXEC_OPS && !a.done && a.blocked == null

    /** 删除多余文件夹前必须先完成的移动（把视频 / 字幕从这个文件夹移走）。 */
    fun deleteNeeds(del: PlanAction, all: List<PlanAction>): List<PlanAction> =
        if (del.op != "delete" || del.fileId == null) emptyList() else all.filter { m ->
            if (m.op != "move" || m.kind == "cleanup") false else {
                var parent = m.parentId
                val seen = mutableSetOf<String>()
                var inside = false
                while (parent != null && seen.add(parent)) {
                    if (parent == del.fileId || (del.fileId.startsWith("/") && parent.startsWith(del.fileId.trimEnd('/') + "/"))) {
                        inside = true; break
                    }
                    parent = all.firstOrNull { it.fileId == parent && (it.kind == "folder" || it.op == "delete") }?.parentId
                }
                inside
            }
        }

    /**
     * move 依赖的 mkdir（同分组、同父目录、名字等于 destLabel，且 move 没有固定目标 ID）。
     *
     * 不变量（L12）：返回值只会是 **mkdir 叶子**——mkdir 自己不依赖别的操作（规划器给 mkdir 的父目录总是已存在的
     * 文件夹 ID，不会带 destRef / destLabel），所以依赖链最长一层。[FailedRetry] 和 [set] 都只取一层依赖，
     * 就是基于这一点；以后如果允许「新建文件夹套新建文件夹」，必须把这两处改成递归。
     */
    fun dependencyOf(move: PlanAction, all: List<PlanAction>): PlanAction? {
        val dep = move.destRef?.let { ref -> all.firstOrNull { it.id == ref && it.op == "mkdir" } }
            ?: run {
                if (move.destRef != null || move.op != "move" || move.destParentId != null) return null
                val label = move.destLabel ?: return null
                all.firstOrNull { it.op == "mkdir" && it.newName == label && it.parentId == move.parentId && it.groupId == move.groupId }
                    ?: all.firstOrNull { it.op == "mkdir" && it.newName == label && it.parentId == move.parentId }
            }
        // 只在 debug 构建里断言（测试会跑到）；release 里不因为一条异常数据让预览页闪退
        if (com.tamu.pan123.BuildConfig.DEBUG) check(dep == null || (dep.op == "mkdir" && dep.destRef == null)) { "dependencyOf 只能返回无依赖的 mkdir（L12）" }
        return dep
    }

    fun set(plan: ScrapePlan, ids: Collection<String>, on: Boolean): ScrapePlan {
        val target = ids.toMutableSet()
        val byId = plan.actions.associateBy { it.id }
        var previousSize: Int
        do {
        previousSize = target.size
        if (on) {
            for (id in target.toList()) {
                val a = byId[id] ?: continue
                dependencyOf(a, plan.actions)?.let { target += it.id }
                for (m in deleteNeeds(a, plan.actions)) {
                    target += m.id
                    dependencyOf(m, plan.actions)?.let { target += it.id }
                }
            }
        } else {
            val mkdirs = target.mapNotNull { byId[it] }.filter { it.op == "mkdir" }
            if (mkdirs.isNotEmpty()) plan.actions.filter { it.op == "move" }.forEach { m ->
                val dep = dependencyOf(m, plan.actions)
                if (dep != null && mkdirs.any { it.id == dep.id }) target += m.id
            }
            if (mkdirs.isNotEmpty()) plan.actions.filter { it.op == "artwork" && it.destRef != null }.forEach { a ->
                if (mkdirs.any { it.id == a.destRef }) target += a.id
            }
            // 取消移动 → 依赖这些移动的删除也取消（否则文件夹里还有视频，不会删除）
            val moves = target.mapNotNull { byId[it] }.filter { it.op == "move" }
            if (moves.isNotEmpty()) plan.actions.filter { it.op == "delete" }.forEach { d ->
                if (deleteNeeds(d, plan.actions).any { m -> moves.any { it.id == m.id } }) target += d.id
            }
        }
        } while (target.size != previousSize)
        return plan.copy(actions = plan.actions.map {
            if (it.id in target && executable(it)) it.copy(selected = on) else it
        })
    }

    fun setAll(plan: ScrapePlan, on: Boolean, filter: (PlanAction) -> Boolean = { true }): ScrapePlan =
        set(plan, plan.actions.filter { executable(it) && filter(it) }.map { it.id }, on)

    /** 分组勾选状态：全选 / 部分 / 未选 / 无可执行项。 */
    enum class Tri { ALL, SOME, NONE, EMPTY }

    fun groupIds(plan: ScrapePlan, groupId: String): List<String> = plan.actions.filter {
        it.groupId == groupId && it.kind != "cleanup" && it.op != "delete" && executable(it)
    }.map { it.id }

    fun groupState(plan: ScrapePlan, groupId: String): Tri {
        val ids = groupIds(plan, groupId).toSet()
        val acts = plan.actions.filter { it.id in ids }
        if (acts.isEmpty()) return Tri.EMPTY
        val n = acts.count { it.selected }
        return when (n) { 0 -> Tri.NONE; acts.size -> Tri.ALL; else -> Tri.SOME }
    }

    /** 要执行的 ID（按计划顺序，已补齐依赖）。 */
    fun selectedIds(plan: ScrapePlan): List<String> {
        val sel = plan.actions.filter { executable(it) && it.selected }.map { it.id }.toMutableSet()
        plan.actions.filter { it.id in sel }.forEach { a ->
            dependencyOf(a, plan.actions)?.takeIf { executable(it) }?.let { sel += it.id }
            deleteNeeds(a, plan.actions).filter { executable(it) }.forEach { m ->
                sel += m.id
                dependencyOf(m, plan.actions)?.takeIf { executable(it) }?.let { sel += it.id }
            }
        }
        return plan.actions.filter { it.id in sel }.map { it.id }
    }

    fun summary(plan: ScrapePlan, ids: List<String>): String {
        val set = ids.toSet()
        val a = plan.actions.filter { it.id in set }
        val parts = listOf(
            "命名" to a.count { it.op == "rename" },
            "新建" to a.count { it.op == "mkdir" && it.kind != "cleanup" },
            "移动" to a.count { it.op == "move" && it.kind != "cleanup" },
            "海报" to a.count { it.op == "artwork" },
            "清理" to a.count { it.kind == "cleanup" && it.op == "move" },
            "删除" to a.count { it.op == "delete" },
        ).filter { it.second > 0 }
        return parts.joinToString(" · ") { "${it.first} ${it.second}" }
    }
}
