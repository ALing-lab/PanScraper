package com.tamu.pan123.ui.screens

import kotlinx.coroutines.sync.withLock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import com.tamu.pan123.data.scrape.PreviewFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.tamu.pan123.data.SecureStore
import com.tamu.pan123.data.scrape.PlanAction
import com.tamu.pan123.data.scrape.PlanGroup
import com.tamu.pan123.data.scrape.PlanStore
import com.tamu.pan123.data.scrape.Scanner
import com.tamu.pan123.data.scrape.ScrapePlan
import com.tamu.pan123.data.tmdb.MatchInfo
import com.tamu.pan123.data.tmdb.TmdbClient
import com.tamu.pan123.ui.bottomInset
import com.tamu.pan123.ui.components.*
import com.tamu.pan123.ui.theme.Ios
import com.tamu.pan123.ui.theme.IosType
import com.tamu.pan123.service.ExecForegroundService
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@Composable
fun ScrapeScreen(nav: NavHostController) {
    val c = Ios.colors
    val sel by ScanTargets.selectionFlow.collectAsState()
    val full by ScanTargets.fullDriveFlow.collectAsState()
    val session by SecureStore.session.collectAsState()
    val accounts by com.tamu.pan123.data.drive.AccountStore.accounts.collectAsState()
    val activeId by com.tamu.pan123.data.drive.AccountStore.activeId.collectAsState()
    val tmdb by SecureStore.tmdbKey.collectAsState()
    // 扫描目标所属账号：有选中文件夹时用选择所属账号，否则（全盘）用当前浏览的账号
    val scanAcct = remember(accounts, activeId, sel, session) {
        val id = if (sel.count > 0) sel.accountId else activeId
        accounts.firstOrNull { it.id == id } ?: com.tamu.pan123.data.drive.AccountStore.active()
    }
    val driveReady = remember(scanAcct, session) {
        scanAcct.provider.implemented && com.tamu.pan123.data.drive.AccountStore.isLoggedIn(scanAcct)
    }
    val summary by PlanStore.summary.collectAsState()
    val progress by Scanner.progress.collectAsState()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val scanning = progress.phase == "scan"
    // 本页打开时已有的 runId；之后新完成的扫描才自动跳到预览
    val seenRun = remember { mutableStateOf(progress.runId.takeIf { progress.phase != "scan" } ?: (progress.runId - 1)) }
    LaunchedEffect(progress.phase, progress.runId) {
        if (progress.phase == "done" && progress.runId > seenRun.value) {
            seenRun.value = progress.runId
            nav.navigate("scrape/preview")
        }
    }
    val canScan = driveReady && tmdb.isNotBlank() && (sel.count > 0 || full) && !scanning

    LargeTitlePage(
        "刮削",
        bottomInset = bottomInset(),
        trailing = {
            if (summary != null) BarButton("预览") { nav.navigate("scrape/preview") }
        },
    ) {
        if (!driveReady || tmdb.isBlank()) {
            item {
                EmptyState(
                    Icons.Rounded.Key,
                    "还不能扫描",
                    buildString {
                        if (!driveReady) append(
                            if (!scanAcct.provider.implemented) "暂不支持扫描 ${scanAcct.provider.label}，请切换到其它网盘账号。"
                            else "请先在「设置 › 资源中心」登录 ${scanAcct.label}。"
                        )
                        if (tmdb.isBlank()) append(if (isNotEmpty()) "\n" else "").append("请设置 TMDB API 密钥。")
                    },
                )
            }
        } else if (sel.count == 0 && !full && summary == null) {
            item {
                EmptyState(
                    Icons.Rounded.AutoAwesome,
                    "暂无预刮削数据",
                    "在「云盘」里点右上角「选择」勾选文件夹，或点「全盘扫描」，然后回到这里开始。",
                )
            }
        }

        if (sel.count > 0 || full) {
            section("targets", header = "待扫描目标 · ${scanAcct.label}") {
                if (full) IosRow("全盘扫描", subtitle = "根目录", icon = Icons.Rounded.TravelExplore, iconTint = c.purple, showDivider = sel.count > 0)
                sel.folders.entries.toList().forEachIndexed { i, (id, path) ->
                    IosRow(path.ifBlank { "文件夹 $id" }, subtitle = "ID $id", showDivider = i < sel.folders.size - 1)
                }
            }
            item {
                FilledButton(
                    when {
                        scanning -> "扫描中…"
                        else -> "开始扫描"
                    },
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    enabled = canScan,
                ) {
                    error = null
                    val roots = if (full) emptyList() else sel.folders.map { it.key to it.value }
                    val drive = runCatching { com.tamu.pan123.data.drive.DriveRegistry.forAccount(scanAcct.id) }
                        .onFailure { error = it.message }.getOrNull()
                    if (drive != null) Scanner.start(roots, full, drive)
                }
            }
            if (scanning) item { ScanProgressPanel(progress) }
            if (!scanning && progress.phase == "error") item {
                val tmdbErr = progress.error.orEmpty().contains("TMDB")
                StatusNote("扫描失败：${progress.error ?: "未知错误"}", c.red,
                    if (tmdbErr) "TMDB 连不上时：开启代理，或到「设置 › TMDB 网络」改用备用地址 / DoH / 代理，测试通过后再扫描。"
                    else "可以直接再点「开始扫描」重试；如果反复失败，请在「设置 › 调试日志」导出日志发给开发者。")
                if (tmdbErr) Text("打开 TMDB 网络设置", style = IosType.footnote, color = c.link,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp).pressable { nav.navigate("settings/tmdb-net") })
            }
            if (!scanning && progress.phase == "cancelled") item {
                StatusNote("已取消扫描", c.secondaryLabel, null)
            }
            section("clear") {
                IosRow("清除目标", titleColor = c.red, showDivider = false) {
                    Scanner.reset()
                    ScanTargets.selection = sel.clear()
                    ScanTargets.fullDrive = false
                }
            }
        }

        if (summary != null) {
            section("last", header = "最近一次计划", footer = summary!!.createdAt) {
                // 方案 I：数字带单位（影片 / 剧集按「部」，操作按「项」）
                IosRow("分组", value = "${summary!!.groups} 部")
                IosRow("操作项", value = "${summary!!.actions} 项")
                IosRow("未匹配", value = "${summary!!.unmatched} 项")
                IosRow("查看预览", icon = Icons.Rounded.FactCheck, iconTint = c.blue, chevron = true, showDivider = false) {
                    nav.navigate("scrape/preview")
                }
            }
        }
        if (error != null) item {
            Text(error!!, style = IosType.footnote, color = c.red, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
        }
    }
}

internal val planWriteLock = kotlinx.coroutines.sync.Mutex()

@Composable
fun PreviewScreen(nav: NavHostController, planOverride: ScrapePlan? = null) {
    val c = Ios.colors
    val ctx = LocalContext.current
    val switchTab = com.tamu.pan123.ui.LocalTabSwitch.current
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var plan by remember { mutableStateOf(planOverride) }
    var loadErr by remember { mutableStateOf<String?>(null) }
    // 方案 I：上排状态 + 下排操作两排筛选（见 PreviewFilter）；筛选只影响显示，不改勾选
    var fst by remember { mutableStateOf(PreviewFilter.State()) }
    // 展开的分组；null = 还没动过 → 默认展开第一组
    var expanded by remember { mutableStateOf<Set<String>?>(null) }
    var confirmIds by remember { mutableStateOf<List<String>?>(null) }
    var execMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (planOverride == null) plan = planWriteLock.withLock { runCatching { PlanStore.load() }.onFailure { loadErr = it.message }.getOrNull() }
    }
    fun commit(next: ScrapePlan) {
        val before = plan ?: return
        plan = next
        if (planOverride == null) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            planWriteLock.lock()
            try { runCatching { PlanStore.mergeSelection(before, next) } }
            finally { planWriteLock.unlock() }
                .let { r -> r.exceptionOrNull()?.let { execMsg = "保存勾选失败：${it.message}" } }
        }
    }
    val p = plan
    // V35-3：所有计数一次遍历算好，plan / 筛选不变时重组直接复用
    val aggOrNull = remember(p, fst) { p?.let { PreviewFilter.aggregate(it, fst) } }
    val selectedIds = p?.let { com.tamu.pan123.data.scrape.PlanSelection.selectedIds(it) }.orEmpty()
    // 「全选」作用于当前显示的项；不筛操作时与原来一样不含可选的清理 / 删除
    val visibleExec = p?.actions?.filter {
        PreviewFilter.matches(it, fst) && com.tamu.pan123.data.scrape.PlanSelection.executable(it) &&
            (fst.op != null || (it.kind != "cleanup" && it.op != "delete"))
    }.orEmpty()
    val allOn = visibleExec.isNotEmpty() && visibleExec.all { it.selected }
    LargeTitlePage(
        "预刮削预览",
        onBack = { nav.popBackStack() },
        backLabel = "刮削",
        bottomInset = bottomInset(),
        trailing = {
            if (p != null) {
                BarButton(if (allOn) "全不选" else "全选", enabled = visibleExec.isNotEmpty()) {
                    commit(com.tamu.pan123.data.scrape.PlanSelection.set(p, visibleExec.map { it.id }, !allOn))
                }
                BarButton("执行 (${selectedIds.size})", bold = true, enabled = selectedIds.isNotEmpty()) {
                    execMsg = null
                    confirmIds = selectedIds
                }
            }
        },
    ) {
        if (loadErr != null) item {
            EmptyState(Icons.Rounded.Error, "读不了计划", loadErr!!)
        } else if (p == null) {
            item { Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.blue, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            } }
        } else {
            val agg = aggOrNull ?: return@LargeTitlePage
            item(key = "status") {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    IosSegmented(
                        PreviewFilter.Status.entries.toList(), fst.status,
                        { "${it.label} ${agg.statusCount(it)}" },
                        { fst = PreviewFilter.selectStatus(fst, it) },
                        Modifier.fillMaxWidth(),
                    )
                }
            }
            item(key = "ops") {
                OpTiles(agg, fst) { fst = PreviewFilter.toggleOp(fst, it) }
            }
            agg.chipText?.let { chip ->
                item(key = "chip") { FilterChip(chip) { fst = PreviewFilter.clear() } }
            }
            if (execMsg != null) item {
                Text(execMsg!!, style = IosType.footnote, color = c.red, modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp))
            }
            // 扫描时因为文件太多被截断的文件夹：明确告诉用户
            val truncated = p.flags.filter { it.contains("只扫描了前") }
            if (truncated.isNotEmpty() && !fst.active) item(key = "truncated") {
                GroupedSection(footer = truncated.joinToString("\n")) {
                    IosRow("有文件夹没有扫描完", icon = Icons.Rounded.Warning, iconTint = c.orange, titleColor = c.orange, showDivider = false)
                }
            }
            // v0.3：执行成功的分组从预览里移除，只剩还没执行的
            val shownIds = agg.visibleGroupIds
            val byId = p.groups.associateBy { it.id }
            val doneGroups = agg.doneGroupCount
            val doneActs = p.actions.count { it.done }
            if (!fst.active && (doneActs > 0 || p.stats.history > 0)) item {
                Text(buildString {
                    if (doneActs > 0) append("已执行成功的 $doneActs 项" + (if (doneGroups > 0) "（$doneGroups 组全部完成）" else "") + "已从预览移除，下面只剩未执行的。")
                    if (p.stats.history > 0) append("历史记录里整理过的 ${p.stats.history} 个文件夹已跳过。")
                }, style = IosType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp))
            }
            if (shownIds.isEmpty()) item {
                Text(when {
                    fst.active -> "没有符合筛选的项"
                    p.groups.isNotEmpty() -> "全部执行完成，没有剩余操作"
                    else -> "没有分组"
                }, style = IosType.footnote, color = c.secondaryLabel,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp))
            }
            val open = expanded ?: setOfNotNull(shownIds.firstOrNull())
            shownIds.forEach { gid ->
                val g = byId[gid] ?: return@forEach
                section("g-${g.id}", header = when (g.kind) {
                    "tv" -> "剧集"
                    "movie" -> "电影"
                    "collection" -> "合集"
                    else -> "未匹配"
                }) {
                    val tri = com.tamu.pan123.data.scrape.PlanSelection.groupState(p, g.id)
                    val isOpen = g.id in open
                    GroupHeader(g, tri, isOpen, agg.groupSummary(g.id),
                        onToggle = {
                            val ids = com.tamu.pan123.data.scrape.PlanSelection.groupIds(p, g.id)
                            commit(com.tamu.pan123.data.scrape.PlanSelection.set(p, ids, tri != com.tamu.pan123.data.scrape.PlanSelection.Tri.ALL))
                        },
                        onExpand = { expanded = if (isOpen) open - g.id else open + g.id },
                    ) { nav.navigate("scrape/rematch/${g.id}") }
                    if (isOpen) {
                        val acts = p.actions.filter { it.groupId == g.id && PreviewFilter.matches(it, fst) }
                        acts.forEachIndexed { i, a ->
                            ActionRow(a, showDivider = i < acts.lastIndex) {
                                commit(com.tamu.pan123.data.scrape.PlanSelection.set(p, listOf(a.id), !a.selected))
                            }
                        }
                    }
                }
            }
            if (p.flags.isNotEmpty() && !fst.active) {
                section("flags", header = "需要留意") {
                    p.flags.take(30).forEachIndexed { i, f ->
                        IosRow(f, showDivider = i < minOf(29, p.flags.size - 1))
                    }
                }
            }
        }
    }
    val ids = confirmIds
    if (ids != null && p != null) {
        IosAlert(
            title = "执行 ${ids.size} 项操作？",
            message = com.tamu.pan123.data.scrape.PlanSelection.summary(p, ids) +
                "\n\n会直接修改网盘里的文件。进度在「任务」页，可暂停或结束。",
            confirm = "执行",
            onConfirm = {
                confirmIds = null
                // 以磁盘上最新的计划为准（避免用到重新匹配前的旧计划）
                val fresh = if (planOverride == null) runCatching { PlanStore.load() }.getOrNull() else p
                val run = if (fresh != null && PlanStore.key(fresh) == PlanStore.key(p) &&
                    com.tamu.pan123.data.scrape.PlanSelection.selectedIds(fresh) == ids) ids else emptyList()
                if (run.isEmpty()) {
                    execMsg = "没有可执行的操作（计划可能已变化，请重新勾选）"
                    plan = fresh ?: plan
                } else {
                    if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                    ExecForegroundService.start(ctx, run, plan = fresh)
                    switchTab(com.tamu.pan123.ui.Tab.TASKS, null)
                }
            },
            onDismiss = { confirmIds = null },
        )
    }
}

/** 勾选（方案 I 的圆角方块，见 [AccentCheck]）：全选 / 部分（横线）/ 未选；EMPTY 或 enabled=false 显示为灰框。 */
@Composable
internal fun CheckCircle(state: com.tamu.pan123.data.scrape.PlanSelection.Tri, enabled: Boolean = true, onClick: () -> Unit) {
    AccentCheck(
        checked = state == com.tamu.pan123.data.scrape.PlanSelection.Tri.ALL,
        partial = state == com.tamu.pan123.data.scrape.PlanSelection.Tri.SOME,
        enabled = enabled && state != com.tamu.pan123.data.scrape.PlanSelection.Tri.EMPTY,
        onClick = onClick,
    )
}

/** 下排操作格子：命名 / 新建 / 移动 / 海报 / 清理（有删除时再加删除），显示「已选/总数」；未匹配 / 跳过时变灰。 */
@Composable
private fun OpTiles(agg: PreviewFilter.Summary, st: PreviewFilter.State, onTap: (String) -> Unit) {
    val c = Ios.colors
    val enabled = PreviewFilter.opsEnabled(st.status)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        agg.tiles.forEach { (key, label) ->
            val sel = st.op == key
            val n = agg.opCount(key)
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier.weight(1f).heightIn(min = 64.dp).clip(shape)
                    .background(if (sel) c.accentTint else c.cell)
                    .border(if (sel) 1.5.dp else 1.dp, if (sel) c.accent else c.hairline, shape)
                    .semantics {
                        selected = sel; role = Role.Tab
                        if (!enabled) disabled()
                        contentDescription = "$label，已选 ${n.selected}，共 ${n.total}"
                    }
                    .pressable(enabled = enabled) { onTap(key) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (sel) Icon(Icons.Rounded.Check, null, tint = c.label, modifier = Modifier.size(14.dp).padding(end = 2.dp))
                    Text(label, style = IosType.footnote.copy(fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium), color = c.label, maxLines = 1)
                }
                Spacer(Modifier.height(4.dp))
                Text(n.label, style = IosType.footnote.copy(fontFamily = IosType.mono, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal),
                    color = if (sel) c.label else c.secondaryLabel, maxLines = 1)
            }
        }
    }
}

/** 「正在筛选：… · N 项 ✕」：点 ✕ 取消全部筛选（回到「全部」）。 */
@Composable
private fun FilterChip(text: String, onClear: () -> Unit) {
    val c = Ios.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(RoundedCornerShape(20.dp)).background(c.cell).border(1.dp, c.hairline, RoundedCornerShape(20.dp))
                .padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = IosType.footnote, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            Box(
                Modifier.size(44.dp).semantics { contentDescription = "取消筛选"; role = Role.Button }.pressable(onClick = onClear),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, null, tint = c.secondaryLabel, modifier = Modifier.size(18.dp)) }
        }
    }
}

/**
 * 分组卡片的标题行：左边勾选整组；点片名那一行展开 / 收起（箭头旋转）；「重新匹配」是单独的按钮。
 * 收起时片名下面显示「N 项 · 已选 M」这类摘要。
 */
@Composable
private fun GroupHeader(
    g: PlanGroup,
    tri: com.tamu.pan123.data.scrape.PlanSelection.Tri,
    expanded: Boolean,
    summary: String,
    onToggle: () -> Unit,
    onExpand: () -> Unit,
    onRematch: () -> Unit,
) {
    val c = Ios.colors
    val rot by animateFloatAsState(if (expanded) 0f else -90f, label = "chev")
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CheckCircle(tri, enabled = tri != com.tamu.pan123.data.scrape.PlanSelection.Tri.EMPTY, onClick = onToggle)
            Row(
                Modifier.weight(1f)
                    .semantics(mergeDescendants = true) { stateDescription = if (expanded) "已展开" else "已收起"; role = Role.Button }
                    .pressable(onClick = onExpand).padding(top = 12.dp, bottom = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val url = g.posterPath?.let { com.tamu.pan123.data.tmdb.TmdbNet.imageUrl("https://image.tmdb.org/t/p/w185$it") }
                Box(Modifier.size(44.dp, 66.dp).clip(RoundedCornerShape(8.dp)).background(c.fill)) {
                    com.tamu.pan123.ui.components.TmdbImage(url, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        g.title.ifBlank { g.folderName }.ifBlank { "未命名" },
                        style = IosType.headline, color = c.label, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    val sub = buildString {
                        if (g.year.isNotBlank()) append(g.year)
                        if (g.tmdbId != null) {
                            if (isNotEmpty()) append(" · ")
                            append(if (g.kind == "collection") "collection-${g.tmdbId}" else "tmdb-${g.tmdbId}")
                        }
                        if (g.confidence != null) {
                            if (isNotEmpty()) append(" · ")
                            append(if (g.confidence == "手动") "手动匹配" else "可信度 ${g.confidence}")
                        }
                        if (expanded) {
                            if (g.folderNewName != null) {
                                if (isNotEmpty()) append("\n")
                                append("→ ${g.folderNewName}")
                            } else if (g.note != null) {
                                if (isNotEmpty()) append("\n")
                                append(g.note)
                            }
                        }
                    }
                    if (sub.isNotBlank()) Text(sub, style = IosType.footnote, color = c.secondaryLabel, maxLines = 3)
                    g.warn?.let { Text("⚠ $it", style = IosType.caption, color = c.orange, maxLines = 2) }
                    if (!expanded) Text(summary, style = IosType.footnote.copy(fontWeight = FontWeight.Medium), color = c.label)
                }
                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = c.secondaryLabel, modifier = Modifier.size(24.dp).rotate(rot))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 48.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp))
                    .semantics { role = Role.Button }.pressable(onClick = onRematch).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Refresh, null, tint = c.link, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("重新匹配", style = IosType.footnote.copy(fontWeight = FontWeight.Medium), color = c.link)
            }
        }
    }
}

@Composable
private fun ActionRow(a: PlanAction, showDivider: Boolean, onToggle: () -> Unit) {
    val c = Ios.colors
    val cleanup = a.kind == "cleanup"
    val title = when {
        cleanup && a.op == "mkdir" -> "清理 · 新建 ${a.newName}"
        cleanup -> "清理 · 移动"
        a.op == "rename" -> if (a.kind == "folder") "命名（文件夹）" else "命名"
        a.op == "mkdir" -> "新建"
        a.op == "move" -> "移动"
        a.op == "artwork" -> "海报"
        a.op == "delete" -> "删除"
        a.op == "skip" -> "跳过"
        a.op == "unmatched" -> "未匹配"
        else -> a.op
    }
    val detail = buildString {
        append(when (a.op) {
            "rename" -> "${a.oldName}\n→ ${a.newName}"
            "mkdir" -> a.newName
            "move" -> "${a.oldName} → ${a.destLabel ?: a.newName}"
            "artwork" -> a.newName + (a.note?.let { "（$it）" } ?: "")
            else -> a.oldName.ifBlank { a.note.orEmpty() }
        })
        if (cleanup && a.op == "move" && a.note != null) append("\n").append(a.note.removePrefix("清理旧刮削文件："))
        if (a.done) append("\n已完成")
        a.error?.let { append("\n失败：").append(it) }
    }
    val exec = com.tamu.pan123.data.scrape.PlanSelection.executable(a)
    IosRow(
        title, subtitle = detail, showDivider = showDivider,
        titleColor = when {
            a.op == "unmatched" || a.error != null -> c.orange
            cleanup -> c.purple
            else -> null
        },
        leading = {
            if (exec) CheckCircle(if (a.selected) com.tamu.pan123.data.scrape.PlanSelection.Tri.ALL else com.tamu.pan123.data.scrape.PlanSelection.Tri.NONE, onClick = onToggle)
            else if (a.done) Icon(Icons.Rounded.CheckCircle, null, tint = c.green, modifier = Modifier.size(24.dp))
        },
        onClick = if (exec) onToggle else null,
    )
}

@Composable
fun RematchScreen(nav: NavHostController, groupId: String) {
    val c = Ios.colors
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf(runCatching { PlanStore.load() }.getOrNull()) }
    val group = plan?.groups?.firstOrNull { it.id == groupId }
    var query by remember { mutableStateOf(group?.title?.ifBlank { group.folderName }.orEmpty()) }
    var year by remember { mutableStateOf(group?.year.orEmpty()) }
    var kind by remember { mutableStateOf(if (group?.kind == "tv") "tv" else "movie") }
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<MatchInfo>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.blue, unfocusedBorderColor = c.separator,
        focusedTextColor = c.label, unfocusedTextColor = c.label,
        cursorColor = c.blue, focusedContainerColor = c.cell, unfocusedContainerColor = c.cell,
        focusedLabelColor = c.secondaryLabel, unfocusedLabelColor = c.secondaryLabel,
    )

    fun search() {
        busy = true; err = null
        scope.launch {
            try {
                results = if (kind == "tv") {
                    TmdbClient.searchTvRaw(query, year.ifBlank { null }).map { TmdbClient.tvInfo(it) }
                } else {
                    TmdbClient.searchMovieRaw(query, year.ifBlank { null }).map { TmdbClient.movieInfo(it) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { err = e.message }
            finally { busy = false }
        }
    }

    var applying by remember { mutableStateOf(false) }
    fun apply(info: MatchInfo) {
        if (applying) return
        applying = true; err = null
        scope.launch {
            try {
                val cur = PlanStore.load()
                val drive = com.tamu.pan123.data.drive.DriveRegistry.forAccount(cur.accountId)
                val next = Scanner.replanGroup(cur, groupId, info, drive)
                PlanStore.replace(cur, next)
                nav.popBackStack()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                err = "重新规划失败：${e.message}"
            } finally { applying = false }
        }
    }

    LargeTitlePage("重新匹配", onBack = { nav.popBackStack() }, backLabel = "预览", bottomInset = bottomInset()) {
        item {
            GroupedSection(header = "当前", footer = group?.folderName) {
                IosRow(group?.title?.ifBlank { "未匹配" } ?: "—", subtitle = listOfNotNull(group?.year, group?.tmdbId?.let { "tmdb-$it" }).joinToString(" · "), showDivider = false)
            }
        }
        item {
            GroupedSection(footer = "选好类型再搜索。应用后会按新类型从头重新规划这个文件夹（改名、季文件夹、移动、海报 / NFO），旧的操作全部丢弃。") {
                IosSegmented(listOf("movie", "tv"), kind, { if (it == "tv") "剧集" else "电影" }, { kind = it; results = emptyList() },
                    Modifier.fillMaxWidth().padding(12.dp))
                OutlinedTextField(query, { query = it }, label = { Text("搜索关键词") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), colors = fieldColors)
                OutlinedTextField(year, { year = it }, label = { Text("年份（可空）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(12.dp), colors = fieldColors)
            }
        }
        item {
            FilledButton(if (busy) "搜索中…" else if (applying) "正在重新规划…" else "搜索 TMDB", Modifier.padding(16.dp), enabled = !busy && !applying && query.isNotBlank()) { search() }
        }
        if (err != null) item { Text(err!!, style = IosType.footnote, color = c.red, modifier = Modifier.padding(horizontal = 32.dp)) }
        if (results.isNotEmpty()) {
            section("results", header = "结果（点选应用）") {
                results.forEachIndexed { i, r ->
                    Row(
                        Modifier.fillMaxWidth().pressable { apply(r) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(46.dp, 69.dp).clip(RoundedCornerShape(4.dp)).background(c.fill)) {
                            com.tamu.pan123.ui.components.TmdbImage(r.posterUrl, Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = IosType.body, color = c.label, maxLines = 2)
                            Text(listOf(r.year, "tmdb-${r.id}").filter { it.isNotBlank() }.joinToString(" · "),
                                style = IosType.footnote, color = c.secondaryLabel)
                        }
                    }
                    if (i < results.lastIndex) Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator).padding(start = 70.dp))
                }
            }
        }
    }
}


@Composable
private fun StatusNote(title: String, color: androidx.compose.ui.graphics.Color, hint: String?) {
    val c = Ios.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp)) {
        Text(title, style = IosType.footnote, color = color)
        if (hint != null) Text(hint, style = IosType.caption, color = c.tertiaryLabel, modifier = Modifier.padding(top = 2.dp))
    }
}

/** 扫描进度：当前步骤、耗时、请求数、正在等待的请求、最近响应 / 错误 / 重试倒计时。每秒刷新。 */
@Composable
internal fun ScanProgressPanel(progress: Scanner.Progress, nowOverride: Long? = null) {
    val c = Ios.colors
    val act by com.tamu.pan123.data.net.NetLog.activity.collectAsState()
    var now by remember { mutableStateOf(nowOverride ?: System.currentTimeMillis()) }
    if (nowOverride == null) LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
    }
    fun secs(ms: Long) = ((ms.coerceAtLeast(0)) / 1000)
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = c.blue, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Text(progress.message, style = IosType.footnote, color = c.secondaryLabel)
        if (progress.step.isNotBlank()) Text(progress.step, style = IosType.caption, color = c.secondaryLabel)
        Text("文件夹 ${progress.foldersDone}/${progress.foldersTotal} · 视频 ${progress.filesSeen} · 请求 ${act.requests} 次 · 已用 ${secs(now - progress.startedAt)} 秒",
            style = IosType.caption, color = c.tertiaryLabel)
        val idle = secs(now - act.lastActivityAt)
        if (act.inflight != null) {
            Text("等待响应：${act.inflight} · ${secs(now - act.inflightSince)} 秒",
                style = IosType.caption, color = if (now - act.inflightSince > 20_000) c.orange else c.tertiaryLabel)
        }
        if (act.retryNote != null) {
            Text("${act.retryNote} · ${secs(act.retryAt - now + 999)} 秒后", style = IosType.caption, color = c.orange)
        }
        act.last?.let { Text("最近：$it", style = IosType.caption, color = c.tertiaryLabel, maxLines = 1) }
        act.lastError?.let { Text("最近错误：$it", style = IosType.caption, color = c.red, maxLines = 2) }
        if (act.inflight == null && idle > 20 && act.retryNote == null) {
            Text("已 $idle 秒没有网络活动", style = IosType.caption, color = c.orange)
        }
        Spacer(Modifier.height(8.dp))
        Text("取消", style = IosType.body, color = c.red, modifier = Modifier.pressable { Scanner.cancel() }.padding(8.dp))
    }
}


/** 非文件夹分组（散文件）沿用旧逻辑：逐项改名，并重新计数、清掉相关提示。 */
