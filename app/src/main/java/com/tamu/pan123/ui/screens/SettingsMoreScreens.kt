package com.tamu.pan123.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import com.tamu.pan123.AppInfo
import com.tamu.pan123.BuildConfigInfo
import com.tamu.pan123.R
import com.tamu.pan123.data.Settings
import com.tamu.pan123.data.history.HistoryRecord
import com.tamu.pan123.data.history.HistoryStore
import com.tamu.pan123.data.naming.MediaParse
import com.tamu.pan123.data.naming.Naming
import com.tamu.pan123.data.update.ReleaseInfo
import com.tamu.pan123.data.update.UpdateChecker
import com.tamu.pan123.data.update.UpdateResult
import com.tamu.pan123.ui.components.*
import com.tamu.pan123.ui.theme.Ios
import com.tamu.pan123.ui.theme.IosType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 应用图标（自适应图标的背景 + 前景层，跟随浅色 / 深色资源）。 */
@Composable
fun AppIconTile(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.225f))) {
        Image(painterResource(R.drawable.ic_launcher_bg), null, Modifier.fillMaxSize())
        Image(painterResource(R.drawable.ic_launcher_fg), null, Modifier.fillMaxSize().graphicsScale(1.5f))
    }
}

private fun Modifier.graphicsScale(f: Float) = this.then(androidx.compose.ui.Modifier.graphicsLayer(scaleX = f, scaleY = f))

// ---------------- 简洁命名 ----------------

/** 命名的实时示例（剧集 + 电影），按两个开关的组合生成。 */
internal fun namingExample(simple: Boolean, tmdb: Boolean): String {
    val ps = Naming.simpleNaming; val pt = Naming.tmdbInFileName
    return try {
        Naming.simpleNaming = simple; Naming.tmdbInFileName = tmdb
        val tvStem = "Que.Gu.2026.S01E02.2160p.WEB-DL.HDR10.DDP5.1"
        val mvStem = "Inception.2010.2160p.UHD.BluRay.REMUX.HDR10.TrueHD.Atmos"
        val tv = Naming.buildEpisodeBase("雀骨", "2026", 1, 2, MediaParse.parseMedia(tvStem), 295558)
        val mv = Naming.buildMovieBase("盗梦空间", "2010", MediaParse.parseMedia(mvStem), mvStem, 27205)
        "$tv.mp4\n$tv.zh.srt\n$mv.mkv"
    } finally { Naming.simpleNaming = ps; Naming.tmdbInFileName = pt }
}

internal const val SIMPLE_NAMING_DESC = "文件名只保留基本信息：剧集为「名称.年份.SxxEyy」，电影为「名称.年份.分辨率」。字幕会跟着视频一起改名。"

internal const val TMDB_NAMING_DESC = "文件名末尾加上 TMDB ID，方便不能读取 NFO 的播放器准确识别。需要先打开「简洁命名」。"

/** 开关 + 一句说明。 */
@Composable
internal fun SwitchWithDesc(
    title: String, desc: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color,
    checked: Boolean, onChange: (Boolean) -> Unit,
    indent: androidx.compose.ui.unit.Dp = 0.dp, enabled: Boolean = true,
) {
    val c = Ios.colors
    Box(Modifier.padding(start = indent).graphicsLayer { alpha = if (enabled) 1f else 0.45f }) {
        IosRow(title, icon = icon, iconTint = tint, showDivider = false, trailing = { IosSwitch(checked, onChange, enabled = enabled) })
    }
    Text(desc, style = IosType.footnote, color = c.secondaryLabel,
        modifier = Modifier.fillMaxWidth().padding(start = 61.dp + indent, end = 16.dp, bottom = 10.dp))
}

@Composable
internal fun NamingRows(showDivider: Boolean = true) {
    val c = Ios.colors
    val simple by Settings.simpleNaming.collectAsState()
    val tmdb by Settings.tmdbInFileName.collectAsState()
    SwitchWithDesc("简洁命名", SIMPLE_NAMING_DESC, Icons.Rounded.TextFields, c.indigo, simple, { Settings.setSimpleNaming(it) })
    Box(Modifier.fillMaxWidth().padding(start = 61.dp).height(0.5.dp).background(c.separator))
    // 子开关：缩进在「简洁命名」下面；简洁命名关闭时置灰并强制关闭
    SwitchWithDesc("播放器兼容命名", TMDB_NAMING_DESC, Icons.Rounded.Tag, c.teal, tmdb && simple, { Settings.setTmdbInFileName(it) },
        indent = 28.dp, enabled = simple)
    Column(Modifier.fillMaxWidth().padding(start = 61.dp, end = 16.dp, bottom = 12.dp)) {
        Text("示例（随开关实时变化）", style = IosType.footnote, color = c.secondaryLabel)
        // 完整命名的例子很长：不折行，横向滑动查看
        Text(namingExample(simple, tmdb), style = IosType.footnote.copy(fontFamily = FontFamily.Monospace), color = c.label, softWrap = false,
            modifier = Modifier.padding(top = 2.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.fill)
                .horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(8.dp))
    }
    if (showDivider) Box(Modifier.fillMaxWidth().padding(start = 61.dp).height(0.5.dp).background(c.separator))
}

// ---------------- 历史记录 ----------------

private val histFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

private fun providerLabel(key: String) = com.tamu.pan123.data.drive.ProviderKind.of(key).label

@Composable
fun HistoryScreen(nav: NavHostController) {
    val c = Ios.colors
    LaunchedEffect(Unit) { HistoryStore.load() }
    val enabled by HistoryStore.enabled.collectAsState()
    val records by HistoryStore.records.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    LargeTitlePage("历史记录", onBack = { nav.popBackStack() }, backLabel = "设置") {
        section("toggle", footer = "开启后，执行成功的项目会被记住：之后扫描时直接跳过整理过的文件夹，不再重复匹配 TMDB（有新视频时沿用记住的 TMDB 条目，只处理新文件）。只保存网盘、目录、文件夹、文件名、TMDB ID、操作类型和时间，不保存海报或 NFO 内容。") {
            IosRow("记住已整理的项目", icon = Icons.Rounded.History, iconTint = c.blue, showDivider = false,
                trailing = { IosSwitch(enabled, { HistoryStore.setEnabled(it) }) })
        }
        if (records.isEmpty()) {
            item(key = "empty") { EmptyState(Icons.Rounded.History, "还没有历史记录", "执行成功后，每次任务会在这里留下一条记录。") }
        } else {
            section("list", header = "任务记录（${records.size}）", footer = "按时间倒序。点一条查看详情，可在详情里删除单条。") {
                records.forEachIndexed { i, r ->
                    IosRow(r.title.ifBlank { "任务" },
                        subtitle = "${histFmt.format(Date(r.at))} · ${providerLabel(r.provider)}" + (if (r.dir.isNotBlank()) " · ${r.dir}" else ""),
                        value = "${r.items.size} 项", chevron = true, showDivider = i < records.lastIndex) {
                        nav.navigate("settings/history/${r.id}")
                    }
                }
            }
            section("clear") {
                IosRow("清空全部历史记录", titleColor = c.red, showDivider = false) { confirmClear = true }
            }
        }
    }
    if (confirmClear) IosAlert(
        title = "清空全部历史记录？",
        message = "之后扫描会重新匹配所有文件夹（网盘里的文件不受影响）。",
        confirm = "清空", destructive = true,
        onConfirm = { HistoryStore.clear(); confirmClear = false },
        onDismiss = { confirmClear = false },
    )
}

@Composable
fun HistoryDetailScreen(nav: NavHostController, id: String, recordOverride: HistoryRecord? = null) {
    val c = Ios.colors
    val records by HistoryStore.records.collectAsState()
    val r = recordOverride ?: records.firstOrNull { it.id == id }
    var confirm by remember { mutableStateOf(false) }
    LargeTitlePage("记录详情", onBack = { nav.popBackStack() }, backLabel = "历史记录") {
        if (r == null) {
            item(key = "none") { EmptyState(Icons.Rounded.History, "记录不存在", "可能已被删除。") }
            return@LargeTitlePage
        }
        section("info", header = r.title) {
            IosRow("时间", value = histFmt.format(Date(r.at)))
            IosRow("网盘", value = providerLabel(r.provider))
            IosRow("目录", value = r.dir.ifBlank { "—" })
            IosRow("成功项目", value = "${r.items.size}", showDivider = false)
        }
        if (r.groups.isNotEmpty()) section("groups", header = "记住的文件夹（下次扫描跳过）") {
            r.groups.forEachIndexed { i, g ->
                IosRow(g.folderName, subtitle = "${if (g.kind == "tv") "剧集" else "电影"} · ${g.title} ${g.year} · tmdb-${g.tmdbId} · ${g.files.size} 个视频",
                    showDivider = i < r.groups.lastIndex)
            }
        }
        val byFolder = r.items.groupBy { it.folder.ifBlank { "其他" } }
        for ((folder, list) in byFolder) {
            section("f-$folder", header = folder) {
                list.forEachIndexed { i, it ->
                    val op = when (it.op) { "rename" -> "改名"; "mkdir" -> "新建"; "move" -> "移动"; "artwork" -> "上传"; "delete" -> "删除"; else -> it.op }
                    IosRow(if (it.from.isNotBlank()) "${it.from} → ${it.to}" else it.to,
                        subtitle = op + (it.tmdbId?.let { t -> " · tmdb-$t" } ?: ""), showDivider = i < list.lastIndex)
                }
            }
        }
        section("del") { IosRow("删除这条记录", titleColor = c.red, showDivider = false) { confirm = true } }
    }
    if (confirm && r != null) IosAlert(
        title = "删除这条记录？", message = "删除后，下次扫描会重新匹配这些文件夹。", confirm = "删除", destructive = true,
        onConfirm = { HistoryStore.delete(r.id); confirm = false; nav.popBackStack() },
        onDismiss = { confirm = false },
    )
}

// ---------------- 关于 / 开源许可 / 检查更新 ----------------

@Composable
fun AboutScreen(nav: NavHostController, autoCheck: UpdateResult? = null) {
    val c = Ios.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf(autoCheck) }
    fun open(url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    LargeTitlePage("关于", onBack = { nav.popBackStack() }, backLabel = "设置") {
        item(key = "hero") {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AppIconTile(88.dp)
                Spacer(Modifier.height(10.dp))
                Text(AppInfo.NAME, style = IosType.title2, color = c.label)
                Text("版本 ${BuildConfigInfo.VERSION}", style = IosType.footnote, color = c.secondaryLabel)
            }
        }
        section("info", footer = "用 TMDB 数据整理网盘里的电影和剧集：改名、整理季文件夹、移动文件、补海报和 NFO。所有写操作都要你在预览页确认后才执行，不会覆盖已有文件。本产品使用 TMDB API，但未获得 TMDB 认可或认证。") {
            IosRow("开发者", value = AppInfo.AUTHOR)
            IosRow("联系方式", value = "QQ ${AppInfo.QQ}") {
                // 只复制数字
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("QQ", AppInfo.QQ.filter(Char::isDigit)))
                android.widget.Toast.makeText(ctx, "已复制 QQ 号", android.widget.Toast.LENGTH_SHORT).show()
            }
            IosRow("GitHub", subtitle = AppInfo.GITHUB_URL.removePrefix("https://"), chevron = true) { open(AppInfo.GITHUB_URL) }
            IosRow("支持网盘", value = "123 · 115 · 光鸭 · WebDAV")
            IosRow("数据来源", value = "TMDB")
            IosRow("开源协议", value = "GPL-3.0", chevron = true, showDivider = false) { nav.navigate("settings/licenses/0") }
        }
        section("more") {
            IosRow("检查更新", icon = Icons.Rounded.SystemUpdate, iconTint = c.green,
                value = if (checking) "检查中…" else null, chevron = !checking) {
                if (checking) return@IosRow
                checking = true
                scope.launch { result = UpdateChecker.check(); checking = false }
            }
            IosRow("开源许可", icon = Icons.Rounded.Gavel, iconTint = c.gray, chevron = true, showDivider = false) { nav.navigate("settings/licenses") }
        }
    }
    result?.let { r -> UpdateDialog(r, onDismiss = { result = null }, onManual = { open(AppInfo.RELEASES_URL); result = null }) }
}

/** 更新对话框：新版本 + 更新说明；立即更新（下载 APK、显示进度、拉起安装）/ 稍后再说 / 手动更新。 */
@Composable
fun UpdateDialog(result: UpdateResult, onDismiss: () -> Unit, onManual: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
            UpdateCard(result, onDismiss, onManual)
        }
    }
}

@Composable
fun UpdateCard(result: UpdateResult, onDismiss: () -> Unit, onManual: () -> Unit, progressOverride: Float? = null) {
    val c = Ios.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf(progressOverride) }
    var err by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val (title, body) = when (result) {
        is UpdateResult.Available -> "发现新版本 ${result.release.version}" to result.release.notes.ifBlank { "（这个版本没有写更新说明）" }
        is UpdateResult.UpToDate -> "已是最新版本" to "当前 ${BuildConfigInfo.VERSION}，GitHub 上最新是 ${result.latest}。"
        is UpdateResult.Error -> "无法检查更新" to result.message
    }
    Column(
        Modifier.padding(horizontal = 40.dp).widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (c.dark) Color(0xFF2C2C2E) else Color(0xF2F2F2F7)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = IosType.headline, color = c.label, textAlign = TextAlign.Center, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp))
        if (result is UpdateResult.Available) Text("当前版本 ${BuildConfigInfo.VERSION}" + (result.release.apkSize.takeIf { it > 0 }?.let { " · 安装包 ${com.tamu.pan123.data.AppDirs.formatSize(it)}" } ?: ""),
            style = IosType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(top = 2.dp))
        Text(body, style = IosType.footnote, color = c.label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
        progress?.let { p ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                if (p >= 0f) LinearProgressIndicator(progress = { p }, color = c.blue, trackColor = c.fill, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(color = c.blue, trackColor = c.fill, modifier = Modifier.fillMaxWidth())
                Text(if (p >= 1f) "下载完成，正在打开安装程序…" else if (p >= 0f) "正在下载 ${(p * 100).toInt()}%" else "正在下载…",
                    style = IosType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(top = 4.dp))
            }
        }
        err?.let { Text(it, style = IosType.footnote, color = c.red, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        Spacer(Modifier.height(8.dp))
        @Composable fun btn(text: String, bold: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
            Box(Modifier.fillMaxWidth().height(44.dp).pressable(onClick = { if (enabled) onClick() }), contentAlignment = Alignment.Center) {
                Text(text, style = if (bold) IosType.headline else IosType.body, color = if (enabled) c.link else c.tertiaryLabel)
            }
        }
        if (result is UpdateResult.Available) {
            val downloading = progress != null && err == null
            btn(if (downloading) "下载中…" else "立即更新", bold = true, enabled = !downloading) {
                err = null; progress = 0f
                job = scope.launch {
                    runCatching {
                        val f = UpdateChecker.download(result.release, java.io.File(ctx.cacheDir, "updates")) { progress = it }
                        UpdateChecker.verifyBeforeInstall(ctx, f)
                        UpdateChecker.verifyBeforeInstall(ctx, f)
                        progress = 1f
                        installApk(ctx, f)
                    }.onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) { err = e.message ?: "下载失败"; progress = null } }
                }
            }
            btn("稍后再说") { job?.cancel(); onDismiss() }
            btn("手动更新") { job?.cancel(); onManual() }
        } else if (result is UpdateResult.Error) {
            btn("手动更新", bold = true) { onManual() }
            btn("好") { onDismiss() }
        } else btn("好", bold = true) { onDismiss() }
    }
}

/** 拉起系统安装程序（Android 8+ 第一次需要允许「安装未知应用」）。 */
internal fun installApk(ctx: android.content.Context, file: java.io.File) {
    if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
        ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        throw IllegalStateException("请先允许「${AppInfo.NAME}」安装未知应用，然后再点一次「立即更新」")
    }
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** 打包进应用的依赖及其许可证（许可证全文在 assets/licenses）。 */
data class OssLib(val name: String, val artifact: String, val license: String, val file: String, val url: String)

/** 本应用自身的许可（GPL-3.0），列在最上面；依赖保留各自的许可。 */
val APP_LICENSE = OssLib("${AppInfo.NAME}（本应用）", AppInfo.GITHUB_REPO, "GNU GPL v3.0", "gpl-3.0-app.txt", AppInfo.GITHUB_URL)

val OSS_LIBS = listOf(
    APP_LICENSE,
    OssLib("Kotlin 标准库 / kotlinx.coroutines / kotlinx.serialization", "org.jetbrains.kotlin(x)", "Apache License 2.0", "apache-2.0.txt", "https://github.com/Kotlin"),
    OssLib("AndroidX Core / Activity / Lifecycle", "androidx.core, androidx.activity, androidx.lifecycle", "Apache License 2.0", "apache-2.0.txt", "https://developer.android.com/jetpack/androidx"),
    OssLib("Jetpack Compose（UI / Foundation / Animation / Material 3）", "androidx.compose.*", "Apache License 2.0", "apache-2.0.txt", "https://developer.android.com/jetpack/compose"),
    OssLib("Material Icons Extended", "androidx.compose.material:material-icons-extended", "Apache License 2.0", "apache-2.0.txt", "https://fonts.google.com/icons"),
    OssLib("Navigation Compose", "androidx.navigation:navigation-compose", "Apache License 2.0", "apache-2.0.txt", "https://developer.android.com/jetpack/androidx/releases/navigation"),
    OssLib("DataStore Preferences", "androidx.datastore:datastore-preferences", "Apache License 2.0", "apache-2.0.txt", "https://developer.android.com/jetpack/androidx/releases/datastore"),
    OssLib("Security Crypto", "androidx.security:security-crypto", "Apache License 2.0", "apache-2.0.txt", "https://developer.android.com/jetpack/androidx/releases/security"),
    OssLib("AndroidX WebKit", "androidx.webkit:webkit", "Apache License 2.0", "apache-2.0.txt", "https://developer.android.com/jetpack/androidx/releases/webkit"),
    OssLib("OkHttp / OkHttp DNS-over-HTTPS / Okio", "com.squareup.okhttp3, com.squareup.okio", "Apache License 2.0", "apache-2.0.txt", "https://square.github.io/okhttp/"),
    OssLib("Coil", "io.coil-kt:coil-compose", "Apache License 2.0", "apache-2.0.txt", "https://coil-kt.github.io/coil/"),
    OssLib("LZ4 Java", "org.lz4:lz4-java", "Apache License 2.0", "apache-2.0.txt", "https://github.com/lz4/lz4-java"),
    OssLib("Bouncy Castle", "org.bouncycastle:bcprov-jdk18on", "MIT（Bouncy Castle Licence）", "mit-bouncycastle.txt", "https://www.bouncycastle.org/licence.html"),
    OssLib("Gson", "com.google.code.gson:gson（Tink 依赖）", "Apache License 2.0", "apache-2.0.txt", "https://github.com/google/gson"),
    OssLib("Tink", "com.google.crypto.tink:tink-android（Security Crypto 依赖）", "Apache License 2.0", "apache-2.0.txt", "https://github.com/tink-crypto/tink-java"),
    OssLib("Guava ListenableFuture", "com.google.guava:listenablefuture", "Apache License 2.0", "apache-2.0.txt", "https://github.com/google/guava"),
    OssLib("Accompanist Drawable Painter", "com.google.accompanist（Coil 依赖）", "Apache License 2.0", "apache-2.0.txt", "https://github.com/google/accompanist"),
    OssLib("JetBrains Annotations", "org.jetbrains:annotations", "Apache License 2.0", "apache-2.0.txt", "https://github.com/JetBrains/java-annotations"),
    OssLib("Mona Sans（字体）", "res/font/mona_sans.ttf", "SIL Open Font License 1.1", "ofl-mona-sans.txt", "https://github.com/github/mona-sans"),
    OssLib("Geist Mono（字体）", "res/font/geist_mono.ttf", "SIL Open Font License 1.1", "ofl-geist-mono.txt", "https://github.com/vercel/geist-font"),
    OssLib("Noto Sans CJK SC（字体，常用字子集）", "res/font/noto_sans_sc_*.otf", "SIL Open Font License 1.1", "ofl-noto-sans-sc.txt", "https://github.com/notofonts/noto-cjk"),
)


@Composable
fun LicensesScreen(nav: NavHostController) {
    LargeTitlePage("开源许可", onBack = { nav.popBackStack() }, backLabel = "关于") {
        section("libs", footer = "${AppInfo.NAME} 以 GNU GPL v3.0 开源（Copyright (C) 2026 ${AppInfo.AUTHOR}）；所用依赖均为 Apache-2.0 / MIT 等与 GPL-3.0 兼容的许可；内置字体为 SIL OFL 1.1。点一项查看许可证全文。TMDB 数据与图片版权归 TMDB 及其贡献者所有。") {
            OSS_LIBS.forEachIndexed { i, l ->
                IosRow(l.name, subtitle = "${l.license} · ${l.artifact}", chevron = true, showDivider = i < OSS_LIBS.lastIndex) {
                    nav.navigate("settings/licenses/$i")
                }
            }
        }
    }
}

@Composable
fun LicenseDetailScreen(nav: NavHostController, index: Int) {
    val c = Ios.colors
    val ctx = LocalContext.current
    val lib = OSS_LIBS.getOrNull(index)
    val text = remember(lib) { lib?.let { runCatching { ctx.assets.open("licenses/${it.file}").bufferedReader().readText() }.getOrNull() } ?: "" }
    LargeTitlePage(lib?.license ?: "许可证", onBack = { nav.popBackStack() }, backLabel = "开源许可") {
        if (lib == null) return@LargeTitlePage
        section("lib", header = lib.name, footer = lib.url) {
            Text(text.ifBlank { "（许可证全文缺失）" }, style = IosType.footnote.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
                color = c.label, modifier = Modifier.padding(12.dp).horizontalScroll(rememberScrollState()))
        }
    }
}
