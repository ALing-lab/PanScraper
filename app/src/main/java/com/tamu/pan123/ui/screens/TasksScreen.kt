package com.tamu.pan123.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.tamu.pan123.data.exec.ExecutionEngine
import com.tamu.pan123.data.exec.TaskRun
import com.tamu.pan123.data.exec.TaskStore
import com.tamu.pan123.data.scrape.PlanStore
import com.tamu.pan123.service.ExecForegroundService
import com.tamu.pan123.ui.bottomInset
import com.tamu.pan123.ui.components.*
import com.tamu.pan123.ui.theme.Ios
import com.tamu.pan123.ui.theme.IosType

@Composable
fun TasksScreen(nav: NavHostController) {
    val c = Ios.colors
    val ctx = LocalContext.current
    val switchTab = com.tamu.pan123.ui.LocalTabSwitch.current
    val run by ExecutionEngine.live.collectAsState()
    val summary by PlanStore.summary.collectAsState()
    var localRun by remember { mutableStateOf(TaskStore.load()) }
    LaunchedEffect(run) { if (run != null) localRun = run }
    LaunchedEffect(Unit) {
        if (ExecutionEngine.live.value == null) localRun = TaskStore.load()
    }
    val r = run ?: localRun

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun ensureNotif() {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun startAll() {
        ensureNotif()
        val plan = PlanStore.loadOrNull() ?: return
        val ids = com.tamu.pan123.data.scrape.PlanSelection.selectedIds(plan)
        ExecForegroundService.start(ctx, ids, plan = plan)
    }

    fun startFailed() {
        ensureNotif()
        val ids = r?.failedIds?.takeIf { it.isNotEmpty() } ?: return
        val plan = runCatching { PlanStore.forTask(r ?: return) }.getOrNull() ?: return
        ExecForegroundService.start(ctx, com.tamu.pan123.data.exec.FailedRetry.retryIds(ids, plan), plan = plan)
    }

    LargeTitlePage("任务", bottomInset = bottomInset()) {
        if (r == null || (r.status == TaskRun.STATUS_IDLE && r.ok + r.fail == 0 && summary == null)) {
            item {
                EmptyState(
                    Icons.Rounded.Bolt,
                    "没有正在进行的任务",
                    "在「刮削 › 预览」确认计划后，可以开始执行。进度会显示在这里，也可以从通知栏暂停、继续或结束。",
                ) {
                    if (summary != null) {
                        FilledButton("打开预览", Modifier.padding(horizontal = 24.dp)) {
                            switchTab(com.tamu.pan123.ui.Tab.SCRAPE, "scrape/preview")
                        }
                    }
                }
            }
            if (summary != null) {
                section("hint", footer = "在预览里勾选要执行的操作，再点右上角「执行」。") {
                    IosRow("最近计划", subtitle = summary!!.createdAt, value = "${summary!!.actions} 项", chevron = true, showDivider = false) {
                        switchTab(com.tamu.pan123.ui.Tab.SCRAPE, "scrape/preview")
                    }
                }
            }
            return@LargeTitlePage
        }

        val statusLabel = when (r.status) {
            TaskRun.STATUS_RUNNING -> "执行中"
            TaskRun.STATUS_PAUSED -> "已暂停"
            TaskRun.STATUS_STOPPING -> "正在结束"
            TaskRun.STATUS_DONE -> "已完成"
            else -> r.status
        }
        val statusColor = when (r.status) {
            TaskRun.STATUS_RUNNING -> c.link
            TaskRun.STATUS_PAUSED -> c.orange
            TaskRun.STATUS_DONE -> if (r.fullySucceeded) c.green else c.orange
            else -> c.secondaryLabel
        }

        section("status", header = "状态") {
            IosRow("当前", value = statusLabel, titleColor = statusColor)
            if (r.phase in 1..com.tamu.pan123.data.exec.ExecPhases.COUNT && r.status != TaskRun.STATUS_DONE) {
                IosRow("阶段", value = com.tamu.pan123.data.exec.ExecPhases.label(r.phase), titleColor = c.label)
            }
            IosRow("进度", value = "${r.ok + r.fail + r.skipped}/${r.actionIds.size}", chevron = true) { nav.navigate("tasks/items/progress") }
            IosRow("成功", value = "${r.ok}", chevron = true) { nav.navigate("tasks/items/ok") }
            IosRow("失败", value = "${r.fail}", chevron = true, titleColor = if (r.fail > 0) c.red else null) { nav.navigate("tasks/items/fail") }
            IosRow("跳过", value = "${r.skipped}", chevron = true) { nav.navigate("tasks/items/skip") }
            IosRow("用时", value = formatElapsed(r.elapsedMs), showDivider = false)
        }
        if (r.message.isNotBlank() && r.status != TaskRun.STATUS_DONE) {
            item {
                Text(r.message, style = IosType.footnote, color = c.secondaryLabel,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
            }
        }

        when (r.status) {
            TaskRun.STATUS_RUNNING -> section("ctrl") {
                IosRow("暂停", icon = Icons.Rounded.PauseCircle, iconTint = c.orange, showDivider = true) {
                    ExecForegroundService.pause(ctx); ExecutionEngine.pause()
                }
                IosRow("提前结束", titleColor = c.red, showDivider = false) {
                    ExecForegroundService.stop(ctx); ExecutionEngine.stop()
                }
            }
            TaskRun.STATUS_PAUSED -> section("ctrl") {
                IosRow("继续", icon = Icons.Rounded.PlayCircle, iconTint = c.green, showDivider = true) {
                    // 若服务已停，重新拉起并 resumeExisting
                    ExecForegroundService.start(ctx, r.actionIds, resume = true)
                    ExecutionEngine.resume()
                }
                IosRow("提前结束", titleColor = c.red, showDivider = false) {
                    ExecutionEngine.stop()
                    ExecForegroundService.stop(ctx)
                }
            }
            TaskRun.STATUS_DONE -> {
                section("done", header = "结果") {
                    IosRow(
                        if (r.fullySucceeded) "全部成功" else if (r.terminalError != null) "任务执行失败" else if (r.userStopped) "任务已结束，部分操作未完成" else "有 ${r.fail} 项失败",
                        icon = if (r.fullySucceeded) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                        iconTint = if (r.fullySucceeded) c.green else c.red,
                        subtitle = r.message,
                        chevron = r.fail > 0,
                        showDivider = false,
                    ) { if (r.fail > 0) nav.navigate("tasks/items/fail") }
                }
            }
            else -> {}
        }

        item(key = "order") {
            Text("执行顺序：① 全部重命名 → ② 新建文件夹 → ③ 移动文件 → ④ 上传海报和 NFO，一次只做一项。点「进度」「成功」「失败」「跳过」查看每一项。",
                style = IosType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp))
        }
    }
}

private fun formatElapsed(ms: Long): String {
    if (ms <= 0) return "—"
    val s = ms / 1000
    val m = s / 60
    val sec = s % 60
    return if (m > 0) "${m} 分 ${sec} 秒" else "${sec} 秒"
}


private fun doneTitle(a: com.tamu.pan123.data.scrape.PlanAction) = when {
    a.kind == "cleanup" -> "清理旧文件"
    a.op == "rename" -> if (a.kind == "folder") "改名（文件夹）" else "改名"
    a.op == "mkdir" -> "新建文件夹"
    a.op == "move" -> "移动"
    a.op == "artwork" -> "上传"
    else -> a.op
}

private fun doneDetail(a: com.tamu.pan123.data.scrape.PlanAction) = when (a.op) {
    "rename" -> "${a.oldName}\n→ ${a.newName}"
    "mkdir" -> a.newName
    "move" -> "${a.oldName} → ${a.destLabel ?: a.newName}"
    "artwork" -> a.newName
    else -> a.oldName
}
