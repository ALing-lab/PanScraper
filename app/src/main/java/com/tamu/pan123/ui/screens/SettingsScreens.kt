package com.tamu.pan123.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.tamu.pan123.BuildConfigInfo
import com.tamu.pan123.data.AppDirs
import com.tamu.pan123.data.SecureStore
import com.tamu.pan123.data.Settings
import com.tamu.pan123.data.ThemeMode
import com.tamu.pan123.ui.bottomInset
import com.tamu.pan123.ui.components.*
import com.tamu.pan123.data.exec.TaskStore
import com.tamu.pan123.data.scrape.PlanStore
import com.tamu.pan123.ui.theme.Ios
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun SettingsScreen(nav: NavHostController) {
    val c = Ios.colors
    val mode by Settings.theme.collectAsState()
    val session by SecureStore.session.collectAsState()
    val accounts by com.tamu.pan123.data.drive.AccountStore.accounts.collectAsState()
    val accountValue = remember(accounts, session) {
        val n = accounts.count { com.tamu.pan123.data.drive.AccountStore.isLoggedIn(it) }
        if (n == 0) "未登录" else "$n 个已登录"
    }
    LargeTitlePage("设置", bottomInset = bottomInset()) {
        section("account", header = "账号") {
            IosRow(
                "资源中心",
                subtitle = "网盘账号（123 / 115 / 光鸭 / WebDAV）· TMDB 密钥",
                icon = Icons.Rounded.AccountCircle,
                iconTint = c.blue,
                value = accountValue,
                chevron = true,
                showDivider = false,
            ) { nav.navigate("settings/account") }
        }
        section("appearance", header = "外观", footer = "选择「跟随系统」时，会随系统的浅色/深色模式自动切换。") {
            IosSegmented(
                ThemeMode.entries.toList(), mode, { it.label }, { Settings.setTheme(it) },
                Modifier.fillMaxWidth().padding(12.dp),
            )
        }
        section("data", header = "数据") {
            IosRow(
                "数据与缓存",
                subtitle = "海报、预览、预刮削计划、日志",
                icon = Icons.Rounded.Folder,
                iconTint = c.orange,
                chevron = true,
                showDivider = false,
            ) { nav.navigate("settings/data") }
        }
        section("scrape", header = "刮削", footer = "两个命名开关只影响之后的扫描 / 重新匹配；已经生成的预览需要重新扫描才会变。文件夹命名不受影响；字幕等同名文件跟随视频的新名字（保留 .zh 等语言后缀），播放器仍能自动配对。") {
            NamingRows()
            val histOn by com.tamu.pan123.data.history.HistoryStore.enabled.collectAsState()
            IosRow("历史记录", subtitle = if (histOn) "已开启：整理过的文件夹下次扫描时跳过" else "已关闭",
                icon = Icons.Rounded.History, iconTint = c.blue, chevron = true, showDivider = false) { nav.navigate("settings/history") }
        }
        section("tmdbnet", header = "TMDB", footer = "大陆网络连不上 TMDB 时，在这里换备用地址、开 DoH 或设置 TMDB 专用代理。") {
            val net by com.tamu.pan123.data.tmdb.TmdbNet.config.collectAsState()
            IosRow(
                "TMDB 网络",
                subtitle = com.tamu.pan123.data.tmdb.TmdbNet.describe(net),
                icon = Icons.Rounded.Public,
                iconTint = c.teal,
                chevron = true,
                showDivider = false,
            ) { nav.navigate("settings/tmdb-net") }
        }
        section(
            "diag", header = "诊断",
            footer = "调试日志只记录请求路径、状态码、条目数和耗时，不含 token、Cookie、密码或密钥。遇到卡住 / 报错时打开，复现一次后点「导出日志」发给开发者。",
        ) {
            DiagnosticsRows()
        }
        section("about") {
            IosRow(
                "关于${com.tamu.pan123.AppInfo.NAME}",
                icon = Icons.Rounded.Info,
                iconTint = c.gray,
                value = "v${BuildConfigInfo.VERSION}",
                chevron = true,
            ) { nav.navigate("settings/about") }
            IosRow("开源许可", icon = Icons.Rounded.Gavel, iconTint = c.gray, chevron = true, showDivider = false) { nav.navigate("settings/licenses") }
        }
    }
}

internal fun formatExpiry(expSec: Long): String {
    if (expSec <= 0) return "—"
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    sdf.timeZone = TimeZone.getTimeZone("America/Los_Angeles")
    val left = expSec - System.currentTimeMillis() / 1000
    val date = sdf.format(Date(expSec * 1000)) + "（洛杉矶）"
    return when {
        left < 0 -> "$date · 已过期"
        left < 86400 -> "$date · 不到 1 天"
        else -> "$date · 还剩 ${left / 86400} 天"
    }
}

@Composable
fun DataScreen(nav: NavHostController) {
    val c = Ios.colors
    var tick by remember { mutableStateOf(0) }
    val usage = remember(tick) { AppDirs.usage() }
    var confirm by remember { mutableStateOf(false) }
    var clearedMsg by remember { mutableStateOf<String?>(null) }
    LargeTitlePage("数据与缓存", onBack = { nav.popBackStack() }, backLabel = "设置") {
        section(
            "dirs",
            header = "存储位置",
            footer = "应用产生的所有数据都保存在应用专属文件夹里，卸载应用时会一起删除。当前合计 ${AppDirs.formatSize(usage.total)}。不会清除登录和 TMDB 密钥。",
        ) {
            IosRow("海报与图片", icon = Icons.Rounded.Image, iconTint = c.pink, value = AppDirs.formatSize(usage.posters + usage.imgCache))
            IosRow("预刮削计划", icon = Icons.Rounded.Description, iconTint = c.blue, value = AppDirs.formatSize(usage.plans))
            IosRow("TMDB 缓存", icon = Icons.Rounded.Storage, iconTint = c.teal, value = AppDirs.formatSize(usage.tmdbCache))
            IosRow("预览导出", icon = Icons.Rounded.Visibility, iconTint = c.indigo, value = AppDirs.formatSize(usage.preview))
            IosRow("日志", icon = Icons.Rounded.Notes, iconTint = c.gray, value = AppDirs.formatSize(usage.logs), showDivider = false)
        }
        section("clean", footer = clearedMsg ?: "清理后需要重新扫描才能生成预刮削计划。") {
            IosRow(
                "清理全部数据",
                titleColor = c.red,
                value = AppDirs.formatSize(usage.total),
                showDivider = false,
            ) { confirm = true }
        }
    }
    if (confirm) {
        IosAlert(
            title = "清理全部数据？",
            message = "将删除海报、预刮削计划、TMDB/图片缓存、预览和日志（约 ${AppDirs.formatSize(usage.total)}）。登录状态和 TMDB 密钥会保留。",
            confirm = "清理",
            cancel = "取消",
            destructive = true,
            onConfirm = {
                val n = AppDirs.clearAll()
                TaskStore.clear()
                PlanStore.reset()
                confirm = false
                tick++
                clearedMsg = "已清理 ${AppDirs.formatSize(n)}"
            },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
internal fun DiagnosticsRows() {
    val c = Ios.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val on by Settings.debugLog.collectAsState()
    var msg by remember { mutableStateOf<String?>(null) }
    IosRow(
        "调试日志",
        subtitle = msg ?: if (on) "正在记录网络请求（已脱敏）" else "关闭",
        icon = Icons.Rounded.Notes,
        iconTint = c.gray,
        trailing = { com.tamu.pan123.ui.components.IosSwitch(on, { Settings.setDebugLog(it) }) },
    )
    IosRow("导出日志", icon = Icons.Rounded.Share, iconTint = c.blue, chevron = true) {
        msg = runCatching { shareNetLog(ctx); null }.getOrElse { "导出失败：${it.message}" }
    }
    IosRow("清除调试日志", titleColor = c.red, showDivider = false) {
        com.tamu.pan123.data.net.NetLog.clear()
        msg = "已清除"
    }
}

/** 生成脱敏日志文件并调起系统分享。 */
internal fun shareNetLog(ctx: android.content.Context) {
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
    val header = buildString {
        append(com.tamu.pan123.AppInfo.NAME + " ").append(BuildConfigInfo.VERSION).append(" 网络日志（已脱敏）\n")
        append("设备：").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
        append(" · Android ").append(android.os.Build.VERSION.RELEASE).append('\n')
        append("导出时间：").append(stamp)
    }
    val out = com.tamu.pan123.data.net.NetLog.exportTo(
        java.io.File(com.tamu.pan123.data.AppDirs.logs, "export-$stamp.txt"), header)
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, "${com.tamu.pan123.AppInfo.NAME} 日志 $stamp")
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(android.content.Intent.createChooser(send, "分享日志").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}
