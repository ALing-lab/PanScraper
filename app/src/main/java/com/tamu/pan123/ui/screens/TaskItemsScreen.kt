package com.tamu.pan123.ui.screens

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.tamu.pan123.data.exec.ExecPhases
import com.tamu.pan123.data.exec.ExecutionEngine
import com.tamu.pan123.data.exec.FailedRetry
import com.tamu.pan123.data.exec.TaskItem
import com.tamu.pan123.data.exec.TaskRun
import com.tamu.pan123.data.exec.TaskStore
import com.tamu.pan123.data.scrape.PlanSelection
import com.tamu.pan123.data.scrape.PlanStore
import com.tamu.pan123.service.ExecForegroundService
import com.tamu.pan123.ui.bottomInset
import com.tamu.pan123.ui.components.BarButton
import com.tamu.pan123.ui.components.EmptyState
import com.tamu.pan123.ui.components.GroupedSection
import com.tamu.pan123.ui.components.IosRow
import com.tamu.pan123.ui.components.IosSegmented
import com.tamu.pan123.ui.components.LargeTitlePage
import com.tamu.pan123.ui.components.pressable
import com.tamu.pan123.ui.components.section
import com.tamu.pan123.ui.theme.Ios
import com.tamu.pan123.ui.theme.IosType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 任务页二级列表：进度 / 成功 / 失败 / 跳过。
 * 全部按「所在文件夹」分组（点文件夹标题展开 / 收起），每项上下两行：操作前 → 预览结果，左边箭头指向结果，右边状态标记。
 * 执行中实时刷新。失败页另有勾选与底部「再次执行未完成项 (N)」。
 */
@Composable
fun TaskItemsScreen(
    nav: NavHostController,
    filter: String,
    runOverride: TaskRun? = null,
    initialSelected: Set<String>? = null,
    initialCollapsed: Set<String> = emptySet(),
    initialPhase: Int = 0,
) {
    val c = Ios.colors
    val ctx = LocalContext.current
    val live by ExecutionEngine.live.collectAsState()
    val stored by TaskStore.run.collectAsState()
    var loaded by remember { mutableStateOf<TaskRun?>(null) }
    LaunchedEffect(Unit) { if (live == null && stored == null) loaded = TaskStore.load() }
    val r = runOverride ?: live ?: stored ?: loaded
    val items = remember(r) { r?.let { itemsOf(it) }.orEmpty() }
    val isFail = filter == "fail"
    val title = when (filter) { "ok" -> "成功"; "fail" -> "失败"; "skip" -> "跳过"; else -> "进度" }
    var phase by remember { mutableStateOf(initialPhase) }
    val shown = when (filter) {
        "ok" -> items.filter { it.status == TaskItem.OK }
        "fail" -> items.filter { it.status == TaskItem.FAIL }
        "skip" -> items.filter { it.status == TaskItem.SKIP }
        else -> items.filter { phase == 0 || it.phase == phase }
    }
    val busy = r?.status == TaskRun.STATUS_RUNNING || r?.status == TaskRun.STATUS_STOPPING
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 失败页：勾选
    val failedIds = remember(shown, isFail) { if (isFail) shown.map { it.id }.toSet() else emptySet() }
    var selected by remember(failedIds) { mutableStateOf(initialSelected?.intersect(failedIds) ?: failedIds) }
    val plan = remember(failedIds, r) { if (isFail && r != null) runCatching { PlanStore.forTask(r) }.getOrNull() else null }
    fun toggle(ids: Collection<String>, on: Boolean) { selected = FailedRetry.toggle(selected, ids, on, failedIds, plan) }
    val allOn = isFail && selected.size == failedIds.size && failedIds.isNotEmpty()

    var collapsed by remember { mutableStateOf(initialCollapsed) }
    val folders = remember(shown) { shown.groupBy { folderOf(it) } } // 保持执行顺序里首次出现的顺序

    LargeTitlePage(
        title, onBack = { nav.popBackStack() }, backLabel = "任务", bottomInset = bottomInset(),
        trailing = {
            if (isFail && shown.isNotEmpty()) {
                BarButton(if (allOn) "全不选" else "全选", enabled = !busy) { toggle(failedIds, !allOn) }
            } else if (folders.size > 1) {
                val allCollapsed = folders.keys.all { it in collapsed }
                BarButton(if (allCollapsed) "全部展开" else "全部收起") { collapsed = if (allCollapsed) emptySet() else folders.keys }
            }
        },
    ) {
        if (filter == "progress" && r != null) {
            item(key = "phases") {
                Column {
                    Text(phaseLine(r, items), style = IosType.footnote, color = c.secondaryLabel,
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
                    IosSegmented((0..ExecPhases.COUNT).toList(), phase, { p -> if (p == 0) "全部" else PHASE_SHORT[p - 1] }, { phase = it },
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
        }
        if (shown.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    when (filter) { "fail" -> Icons.Rounded.CheckCircle; "skip" -> Icons.Rounded.RemoveCircle; else -> Icons.Rounded.Schedule },
                    when (filter) { "ok" -> "还没有成功的操作"; "fail" -> "没有失败的操作"; "skip" -> "没有跳过的操作"; else -> "这一步没有操作" },
                    when (filter) {
                        "skip" -> "之前已完成、网盘里已有同名海报 / NFO，或计划里已找不到的项目会列在这里。"
                        "fail" -> "执行中出错的项目会按所在文件夹列在这里，可以勾选后重试。"
                        else -> "开始执行后，这里会实时显示每一项。"
                    },
                )
            }
        } else {
            if (isFail) item(key = "hint") {
                Text("已选 ${selected.size}/${shown.size} 项。勾选要重做的失败项，再点最下面的「再次执行未完成项」（按 重命名 → 新建 → 移动 → 上传 的顺序；勾选移动会自动带上它需要的新建文件夹）。",
                    style = IosType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
            }
            for ((folder, list) in folders) {
                val ids = list.map { it.id }
                val open = folder !in collapsed
                val tri = if (!isFail) null else ids.count { it in selected }.let { n ->
                    when (n) { 0 -> PlanSelection.Tri.NONE; ids.size -> PlanSelection.Tri.ALL; else -> PlanSelection.Tri.SOME }
                }
                val badge = when (filter) {
                    "progress" -> "${list.count { it.status != TaskItem.WAIT && it.status != TaskItem.RUNNING }}/${list.size}"
                    "fail" -> "${ids.count { it in selected }}/${list.size}"
                    else -> "${list.size}"
                }
                item(key = "f-$folder") {
                    GroupedSection {
                        FolderHeader(folder, badge, open, tri, busy,
                            onCheck = { toggle(ids, tri != PlanSelection.Tri.ALL) },
                            onToggle = { collapsed = if (open) collapsed + folder else collapsed - folder })
                        AnimatedVisibility(open) {
                            Column {
                                list.forEach { it ->
                                    HorizontalDivider(Modifier.padding(start = if (isFail) 48.dp else 16.dp), thickness = 0.5.dp, color = c.separator)
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                        if (isFail) Box(Modifier.padding(start = 4.dp, top = 6.dp)) {
                                            CheckCircle(if (it.id in selected) PlanSelection.Tri.ALL else PlanSelection.Tri.NONE, enabled = !busy) {
                                                toggle(listOf(it.id), it.id !in selected)
                                            }
                                        }
                                        Box(Modifier.weight(1f)) { BeforeAfterRow(it, showPhase = filter == "progress" && phase == 0) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (isFail && r != null) {
            // 未执行到的（暂停 / 提前结束 / 连续失败自动暂停时还在「等待」的）+ 勾选的失败项
            val pending = items.filter { it.status == TaskItem.WAIT || (it.status == TaskItem.RUNNING && r.status != TaskRun.STATUS_RUNNING) }.map { it.id }
            val n = pending.size + selected.size
            val ok = !busy && n > 0
            section("again", footer = "继续所有还没执行到的项（${pending.size}）+ 上面勾选的失败项（${selected.size}）；没勾选的失败项会跳过，已完成的不会重做。") {
                IosRow("再次执行未完成项 ($n)", titleColor = if (ok) c.link else c.tertiaryLabel, chevron = ok, showDivider = false) {
                    if (!ok) return@IosRow
                    val want = (pending + selected).toSet()
                    val ordered = items.map { it.id }.filter { it in want }
                    val boundPlan = runCatching { PlanStore.forTask(r) }.getOrElse {
                        ExecutionEngine.reportStartError(it.message ?: "任务计划不可用"); return@IosRow
                    }
                    val ids = FailedRetry.retryIds(ordered, boundPlan)
                    scope.launch {
                        // 暂停中的任务先结束，等它收尾后再开始新的一轮（同一时间只跑一个任务）
                        if (r.status == TaskRun.STATUS_PAUSED) {
                            ExecutionEngine.stop()
                            kotlinx.coroutines.withTimeoutOrNull(5_000) {
                                ExecutionEngine.live.first { it == null || it.status == TaskRun.STATUS_DONE }
                            }
                        }
                        ExecForegroundService.start(ctx, ids, plan = boundPlan)
                        nav.popBackStack()
                    }
                }
            }
        }
    }
}

private val PHASE_SHORT = listOf("重命名", "新建", "移动", "上传", "删除")

internal fun folderOf(it: TaskItem) = it.folder.ifBlank { it.group.ifBlank { "其他" } }

@Composable
internal fun FolderHeader(
    folder: String, badge: String, open: Boolean, tri: PlanSelection.Tri?, busy: Boolean,
    onCheck: () -> Unit, onToggle: () -> Unit,
) {
    val c = Ios.colors
    val rot by animateFloatAsState(if (open) 90f else 0f, label = "chev")
    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (tri != null) CheckCircle(tri, enabled = !busy, onClick = onCheck) else Spacer(Modifier.width(12.dp))
        Row(
            Modifier.weight(1f).pressable(onClick = onToggle).padding(top = 12.dp, bottom = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Folder, null, tint = c.blue, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(folder, style = IosType.headline, color = c.label, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(badge, style = IosType.footnote, color = c.secondaryLabel,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.fill).padding(horizontal = 8.dp, vertical = 2.dp))
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.ChevronRight, if (open) "收起" else "展开", tint = c.tertiaryLabel, modifier = Modifier.size(20.dp).rotate(rot))
        }
    }
}

/** 操作前（上）与预览结果（下）。 */
internal fun beforeAfter(it: TaskItem): Pair<String, String> {
    val folder = folderOf(it)
    return when (it.op) {
        "rename" -> it.from to it.to
        "move" -> it.from to "${it.to}/${it.from}"
        "mkdir" -> "$folder/" to "${it.to}/"
        "artwork" -> "$folder/" to it.to
        "delete" -> "${it.from}/" to when {
            it.note?.contains("不支持") == true -> "不会删除（请手动删除）"
            it.note?.contains("不可恢复") == true -> "删除（不可恢复）"
            else -> "删除（进回收站）"
        }
        else -> it.from to it.to
    }
}

@Composable
internal fun BeforeAfterRow(it: TaskItem, showPhase: Boolean, hideWait: Boolean = false, title: String? = null) {
    val c = Ios.colors
    val (before, after) = beforeAfter(it)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title ?: opTitle(it), style = IosType.footnote, color = if (it.kind == "cleanup") c.purple else c.secondaryLabel)
                if (showPhase) {
                    Spacer(Modifier.width(6.dp))
                    Text("第 ${it.phase} 步", style = IosType.footnote, color = c.link,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.blue.copy(alpha = 0.12f)).padding(horizontal = 5.dp))
                }
                if (it.folder.isBlank() && it.group.isNotBlank() && it.group != folderOf(it)) {
                    Spacer(Modifier.width(6.dp))
                    Text(it.group, style = IosType.footnote, color = c.tertiaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.height(IntrinsicSize.Min)) {
                val line = c.tertiaryLabel
                val accent = when (it.status) { TaskItem.OK -> c.green; TaskItem.FAIL -> c.red; else -> c.blue }
                Canvas(Modifier.width(18.dp).fillMaxHeight()) {
                    val x = 5.dp.toPx(); val top = 10.dp.toPx(); val bottom = size.height - 10.dp.toPx()
                    drawCircle(line, radius = 2.5.dp.toPx(), center = Offset(x, top))
                    drawLine(line, Offset(x, top + 4.dp.toPx()), Offset(x, bottom), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(line, Offset(x, bottom), Offset(size.width - 2.dp.toPx(), bottom), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                    val tip = Offset(size.width - 1.dp.toPx(), bottom)
                    val path = Path().apply {
                        moveTo(tip.x - 5.dp.toPx(), tip.y - 4.dp.toPx()); lineTo(tip.x, tip.y); lineTo(tip.x - 5.dp.toPx(), tip.y + 4.dp.toPx())
                    }
                    drawPath(path, accent, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
                }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(before.ifBlank { "—" }, style = IosType.callout, color = c.secondaryLabel, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 4.dp))
                    Text(after.ifBlank { "—" }, style = IosType.callout, color = c.label, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            it.error?.takeIf { e -> it.status == TaskItem.FAIL && e.isNotBlank() }?.let { e ->
                Text("原因：$e", style = IosType.footnote, color = c.red, modifier = Modifier.padding(top = 4.dp))
            }
            it.note?.takeIf { n -> n.isNotBlank() && it.status != TaskItem.FAIL }?.let { n ->
                Text(n, style = IosType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(top = 2.dp))
            }
            val meta = listOfNotNull(
                it.at?.let { t -> timeFmt.format(Date(t)) },
                if (it.attempts > 1) "第 ${it.attempts} 次执行" else null,
            ).joinToString(" · ")
            if (meta.isNotBlank()) Text(meta, style = IosType.footnote, color = c.tertiaryLabel, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(28.dp).padding(top = 2.dp), contentAlignment = Alignment.Center) {
            when (it.status) {
                TaskItem.OK -> Icon(Icons.Rounded.CheckCircle, "成功", tint = c.green, modifier = Modifier.size(24.dp))
                TaskItem.FAIL -> Icon(Icons.Rounded.Cancel, "失败", tint = c.red, modifier = Modifier.size(24.dp))
                TaskItem.SKIP -> Icon(Icons.Rounded.RemoveCircle, "跳过", tint = c.tertiaryLabel, modifier = Modifier.size(24.dp))
                TaskItem.RUNNING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.blue)
                else -> if (!hideWait) Icon(Icons.Rounded.RadioButtonUnchecked, "等待", tint = c.tertiaryLabel, modifier = Modifier.size(22.dp))
            }
        }
    }
}

private fun phaseLine(r: TaskRun, items: List<TaskItem>): String = buildString {
    val cur = r.phase
    if (r.status != TaskRun.STATUS_DONE && cur in 1..ExecPhases.COUNT) append("正在进行 ${ExecPhases.label(cur)}。")
    else if (r.status == TaskRun.STATUS_DONE) append("已结束。")
    append("顺序：")
    append((1..ExecPhases.COUNT).joinToString(" → ") { p ->
        val n = items.count { it.phase == p }
        "${ExecPhases.name(p)}（$n）"
    })
}

/** 旧版本的任务记录没有逐项快照：按计划里的状态补出来。 */
private fun itemsOf(r: TaskRun): List<TaskItem> {
    if (r.items.isNotEmpty()) return r.items
    val plan = runCatching { PlanStore.forTask(r) }.getOrNull() ?: return emptyList()
    val byId = plan.actions.associateBy { it.id }
    val names = plan.groups.associate { it.id to it.title.ifBlank { it.folderName } }
    val folders = plan.groups.associate { it.id to (it.folderNewName ?: it.folderName) }
    return ExecutionEngine.executionOrder(r.actionIds, byId).mapNotNull { id ->
        val a = byId[id] ?: return@mapNotNull null
        TaskItem(
            id, a.op, a.kind, ExecPhases.of(a.op),
            from = if (a.op == "mkdir" || a.op == "artwork") "" else a.oldName,
            to = if (a.op == "move") a.destLabel ?: a.newName else a.newName,
            group = names[a.groupId].orEmpty(),
            folder = folders[a.groupId].orEmpty(),
            status = when { a.done -> TaskItem.OK; id in r.failedIds -> TaskItem.FAIL; else -> TaskItem.WAIT },
            error = a.error,
        )
    }
}

internal fun opTitle(it: TaskItem) = when {
    it.kind == "cleanup" -> if (it.op == "mkdir") "清理 · 新建回收文件夹" else "清理 · 移进回收文件夹"
    it.op == "rename" -> if (it.kind == "folder") "重命名文件夹" else "重命名"
    it.op == "mkdir" -> "新建文件夹"
    it.op == "move" -> "移动"
    it.op == "artwork" -> if (it.to.endsWith(".nfo")) "上传 NFO" else "上传海报"
    it.op == "delete" -> "删除多余文件夹"
    it.kind == "companion" -> "同名图片 / NFO"
    else -> it.op
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
