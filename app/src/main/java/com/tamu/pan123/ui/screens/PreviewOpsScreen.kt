package com.tamu.pan123.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.tamu.pan123.data.exec.ExecPhases
import com.tamu.pan123.data.exec.TaskItem
import com.tamu.pan123.data.scrape.PlanAction
import com.tamu.pan123.data.scrape.PlanGroup
import com.tamu.pan123.data.scrape.PlanSelection
import com.tamu.pan123.data.scrape.PlanStore
import com.tamu.pan123.data.scrape.ScrapePlan
import com.tamu.pan123.ui.bottomInset
import com.tamu.pan123.ui.components.BarButton
import com.tamu.pan123.ui.components.EmptyState
import com.tamu.pan123.ui.components.GroupedSection
import com.tamu.pan123.ui.components.LargeTitlePage
import com.tamu.pan123.ui.theme.Ios
import com.tamu.pan123.ui.theme.IosType
import kotlinx.coroutines.launch

/** 预览的分类：汇总行点进来的二级页面。 */
val PREVIEW_OP_TYPES = listOf(
    "rename" to "命名", "mkdir" to "新建", "move" to "移动", "artwork" to "海报",
    "cleanup" to "清理", "delete" to "删除", "skip" to "跳过", "unmatched" to "未匹配",
)

/** 已经执行成功的项不再出现在预览里（v0.3：执行后预览只剩未执行的）。 */
fun previewOpMatches(a: PlanAction, type: String): Boolean = !a.done && when (type) {
    "cleanup" -> a.kind == "cleanup"
    else -> a.kind != "cleanup" && a.op == type
}

/** 计划里的一项 → 任务列表的同款展示（上：操作前；下：预览结果）。 */
internal fun previewItem(a: PlanAction, g: PlanGroup?): TaskItem = TaskItem(
    id = a.id, op = a.op, kind = a.kind, phase = ExecPhases.of(a.op),
    from = when (a.op) { "mkdir", "artwork" -> ""; else -> a.oldName },
    to = when (a.op) {
        "move" -> a.destLabel ?: a.newName
        "artwork" -> if (a.destLabel != null) "${a.destLabel}/${a.newName}" else a.newName
        "skip" -> a.note?.takeIf { it.isNotBlank() } ?: "不处理"
        "unmatched" -> a.note?.takeIf { it.isNotBlank() } ?: "TMDB 未匹配，点文件夹右侧「重新匹配」"
        else -> a.newName
    },
    group = g?.title.orEmpty(),
    folder = g?.let { it.folderName.ifBlank { it.title } }.orEmpty(),
    status = when { a.done -> TaskItem.OK; !a.error.isNullOrBlank() -> TaskItem.FAIL; else -> TaskItem.WAIT },
    error = a.error,
    note = when {
        a.op == "delete" -> a.note
        a.op in setOf("rename", "move", "mkdir") -> a.note?.takeIf { it.isNotBlank() && !it.contains("执行阶段") }
        else -> null
    },
)

/**
 * 预览二级页：某一类操作，按所在文件夹分组（可展开 / 收起），每项上下两行显示操作前与预览结果，
 * 左侧勾选，文件夹标题三态勾选；右上角全选 / 全不选。勾选变化写回计划（移动会带上新建文件夹）。
 */
@Composable
fun PreviewOpsScreen(
    nav: NavHostController,
    type: String,
    planOverride: ScrapePlan? = null,
    initialCollapsed: Set<String> = emptySet(),
) {
    val c = Ios.colors
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf(planOverride) }
    LaunchedEffect(Unit) { if (planOverride == null) plan = runCatching { PlanStore.load() }.getOrNull() }
    var collapsed by remember { mutableStateOf(initialCollapsed) }
    val title = PREVIEW_OP_TYPES.firstOrNull { it.first == type }?.second ?: "操作"
    val p = plan
    fun commit(next: ScrapePlan) {
        plan = next
        if (planOverride == null) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            planWriteLock.lock()
            try { runCatching { PlanStore.update { cur -> cur.copy(actions = next.actions) } } } finally { planWriteLock.unlock() }
        }
    }
    val groups = p?.groups?.associateBy { it.id }.orEmpty()
    val acts = p?.actions?.filter { previewOpMatches(it, type) }.orEmpty()
    val selectable = acts.filter { PlanSelection.executable(it) }
    val allOn = selectable.isNotEmpty() && selectable.all { it.selected }
    val byFolder = acts.groupBy { a -> groups[a.groupId]?.let { it.folderName.ifBlank { it.title } } ?: "其他" }

    LargeTitlePage(
        title, onBack = { nav.popBackStack() }, backLabel = "预览", bottomInset = bottomInset(),
        trailing = {
            if (p != null && selectable.isNotEmpty()) BarButton(if (allOn) "全不选" else "全选", bold = true) {
                commit(PlanSelection.set(p, selectable.map { it.id }, !allOn))
            }
            else if (byFolder.size > 1) {
                val allCollapsed = byFolder.keys.all { it in collapsed }
                BarButton(if (allCollapsed) "全部展开" else "全部收起") { collapsed = if (allCollapsed) emptySet() else byFolder.keys }
            }
        },
    ) {
        if (p == null) return@LargeTitlePage
        if (acts.isEmpty()) {
            item(key = "empty") { EmptyState(Icons.Rounded.Inbox, "没有「$title」操作", "扫描后这一类的每一项会按文件夹列在这里。") }
            return@LargeTitlePage
        }
        item(key = "hint") {
            val sel = selectable.count { it.selected }
            Text(
                buildString {
                    append("${acts.size} 项，分布在 ${byFolder.size} 个文件夹")
                    if (selectable.isNotEmpty()) append("；已勾选 $sel 项")
                    append("。上面一行是现在的样子，下面一行是执行后的结果。")
                    if (type == "move") append("勾选移动会自动带上它需要的新建文件夹。")
                    if (type == "cleanup") append("清理只会把旧刮削文件移进 _刮削旧文件，不会删除。")
                    if (type == "delete") append("这些文件夹里的视频已被移走；默认不勾选，勾选后最后一步才删除，执行前还会再检查一次里面没有视频。")
                },
                style = IosType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
        }
        for ((folder, list) in byFolder) {
            val exec = list.filter { PlanSelection.executable(it) }
            val n = exec.count { it.selected }
            val tri = when {
                exec.isEmpty() -> null
                n == 0 -> PlanSelection.Tri.NONE
                n == exec.size -> PlanSelection.Tri.ALL
                else -> PlanSelection.Tri.SOME
            }
            val open = folder !in collapsed
            item(key = "f-$folder") {
                GroupedSection {
                    FolderHeader(folder, if (tri != null) "$n/${list.size}" else "${list.size}", open, tri, busy = false,
                        onCheck = { p.let { commit(PlanSelection.set(it, exec.map { a -> a.id }, tri != PlanSelection.Tri.ALL)) } },
                        onToggle = { collapsed = if (open) collapsed + folder else collapsed - folder })
                    AnimatedVisibility(open) {
                        Column {
                            list.forEach { a ->
                                val canSel = PlanSelection.executable(a)
                                HorizontalDivider(Modifier.padding(start = if (tri != null) 48.dp else 16.dp), thickness = 0.5.dp, color = c.separator)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                    if (tri != null) Box(Modifier.padding(start = 4.dp, top = 6.dp)) {
                                        if (canSel) CheckCircle(if (a.selected) PlanSelection.Tri.ALL else PlanSelection.Tri.NONE) {
                                            commit(PlanSelection.set(p, listOf(a.id), !a.selected))
                                        } else CheckCircle(PlanSelection.Tri.EMPTY, enabled = false) {}
                                    }
                                    Box(Modifier.weight(1f)) {
                                        Column {
                                            BeforeAfterRow(previewItem(a, groups[a.groupId]), showPhase = false, hideWait = true,
                                                title = previewTitle(a))
                                            a.blocked?.let { why ->
                                                Text("无法删除：$why", style = IosType.footnote, color = c.red,
                                                    modifier = Modifier.padding(start = 16.dp, end = 12.dp, bottom = 10.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun previewTitle(a: PlanAction): String = when {
    a.kind == "cleanup" -> if (a.op == "mkdir") "清理 · 新建回收文件夹" else "清理 · 移进回收文件夹"
    a.op == "rename" -> when (a.kind) { "folder" -> "改名 · 文件夹"; "subtitle" -> "改名 · 字幕"; "companion" -> "改名 · 同名图片 / NFO"; else -> "改名" }
    a.op == "delete" -> "删除多余文件夹"
    a.op == "mkdir" -> "新建文件夹"
    a.op == "move" -> "移动"
    a.op == "artwork" -> when {
        a.newName.endsWith(".nfo") -> "上传 NFO"
        else -> "上传图片"
    }
    a.op == "skip" -> "跳过"
    a.op == "unmatched" -> "未匹配"
    else -> a.op
}
