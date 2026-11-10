package com.tamu.pan123.v03

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.takahirom.roborazzi.captureRoboImage
import com.tamu.pan123.data.AppDirs
import com.tamu.pan123.data.SecureStore
import com.tamu.pan123.data.Settings
import com.tamu.pan123.data.drive.CloudDrive
import com.tamu.pan123.data.drive.DrivePaths
import com.tamu.pan123.data.drive.ProviderKind
import com.tamu.pan123.data.drive.TrashSupport
import com.tamu.pan123.data.history.HistoryGroup
import com.tamu.pan123.data.history.HistoryItem
import com.tamu.pan123.data.history.HistoryRecord
import com.tamu.pan123.data.history.HistoryStore
import com.tamu.pan123.data.naming.Naming
import com.tamu.pan123.data.scrape.PlanSelection
import com.tamu.pan123.data.scrape.Scanner
import com.tamu.pan123.data.scrape.ScrapePlan
import com.tamu.pan123.data.update.ReleaseInfo
import com.tamu.pan123.data.update.UpdateResult
import com.tamu.pan123.ui.screens.AboutScreen
import com.tamu.pan123.ui.screens.HistoryDetailScreen
import com.tamu.pan123.ui.screens.HistoryScreen
import com.tamu.pan123.ui.screens.LicensesScreen
import com.tamu.pan123.ui.screens.NamingRows
import com.tamu.pan123.ui.screens.PreviewOpsScreen
import com.tamu.pan123.ui.screens.PreviewScreen
import com.tamu.pan123.ui.screens.UpdateCard
import com.tamu.pan123.ui.theme.Ios
import com.tamu.pan123.ui.theme.IosTheme
import com.tamu.pan123.ui.theme.IosType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** v0.3 新截图：全部假数据（内存网盘 + 假 TMDB），不联网。输出 build/outputs/roborazzi/v031_*.png */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1100dp-xhdpi")
class V031ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        SecureStore.init(app); AppDirs.init(app)
        com.tamu.pan123.data.drive.AccountStore.init()
        Scanner.tmdb = FakeTmdb
        HistoryStore.dirOverride = java.io.File(app.cacheDir, "hist-shot").also { it.deleteRecursively() }
        HistoryStore.reload()
    }
    @After fun tearDown() { Scanner.tmdb = Scanner.RealTmdb; Settings.setSimpleNaming(false); Naming.simpleNaming = false; Naming.tmdbInFileName = false }

    private fun out(name: String, dark: Boolean) = "build/outputs/roborazzi/v031_${name}_" + (if (dark) "dark" else "light") + ".png"
    private fun scan(d: CloudDrive, root: String) = runBlocking { Scanner.scan(listOf(root to DrivePaths.nameOf(root)), fullDrive = false, cloud = d) }

    private fun shotPreview(plan: ScrapePlan, name: String, dark: Boolean) {
        rule.setContent { IosTheme(dark) { val nav = rememberNavController(); NavHost(nav, "p") { composable("p") { PreviewScreen(nav, planOverride = plan) } } } }
        rule.onRoot().captureRoboImage(out(name, dark))
    }
    private fun shotOps(plan: ScrapePlan, type: String, name: String, dark: Boolean) {
        rule.setContent { IosTheme(dark) { val nav = rememberNavController(); NavHost(nav, "p") { composable("p") { PreviewOpsScreen(nav, type, plan) } } } }
        rule.onRoot().captureRoboImage(out(name, dark))
    }

    // 文件夹结构：深层分类目录 + 单个子文件夹 + 多层嵌套
    private fun structurePlan() = scan(MemDrive(listOf(
        "/资源/剧集/国产/2026/雀骨2026/第一季/新建文件夹/Que.Gu.S01E01.2160p.WEB-DL.mkv",
        "/资源/剧集/国产/2026/雀骨2026/第一季/新建文件夹/Que.Gu.S01E02.2160p.WEB-DL.mkv",
        "/资源/剧集/国产/2026/雀骨2026/第一季/新建文件夹/说明.txt",
        "/资源/剧集/国产/2026/雀骨2026/第一季/新建文件夹/Que.Gu.S01E01.chs.srt",
    )), "/资源")
    private fun collectionPlan() = scan(MemDrive(listOf(
        "/电影/漫威电影系列/钢铁侠系列/钢铁侠1.2160p.mkv", "/电影/漫威电影系列/钢铁侠系列/钢铁侠1.chs.srt",
        "/电影/漫威电影系列/钢铁侠系列/钢铁侠2.2160p.mkv", "/电影/Inception.2010.2160p.mkv", "/电影/The.Matrix.1999.1080p.mkv",
    )), "/电影")
    private fun specialsPlan() = MemDrive(listOf(
        "/综艺/奔跑吧/奔跑吧 第1期.mp4", "/综艺/奔跑吧/奔跑吧 第2期.mp4", "/综艺/奔跑吧/奔跑吧 第1期加更.mp4", "/综艺/奔跑吧/奔跑吧 花絮.mp4",
        "/动漫/鬼灭之刃/Season 1/Kimetsu.S01E01.mkv", "/动漫/鬼灭之刃/Season 1/Kimetsu.OVA.01.mkv", "/动漫/鬼灭之刃/鬼灭之刃 剧场版 无限列车篇.mkv",
    )).let { d -> runBlocking { Scanner.scan(listOf("/综艺" to "综艺", "/动漫" to "动漫"), fullDrive = false, cloud = d) } }

    @Config(qualifiers = "w411dp-h1500dp-xhdpi") @Test fun structurePreviewL() = shotPreview(structurePlan(), "structure_preview", false)
    @Config(qualifiers = "w411dp-h1500dp-xhdpi") @Test fun structurePreviewD() = shotPreview(structurePlan(), "structure_preview", true)
    @Test fun structureMoveL() = shotOps(structurePlan(), "move", "structure_move", false)
    @Test fun structureMoveD() = shotOps(structurePlan(), "move", "structure_move", true)
    @Test fun structureRenameL() = shotOps(structurePlan(), "rename", "structure_rename", false)
    @Test fun structureRenameD() = shotOps(structurePlan(), "rename", "structure_rename", true)
    @Config(qualifiers = "w411dp-h1500dp-xhdpi") @Test fun collectionPreviewL() = shotPreview(collectionPlan(), "collection_preview", false)
    @Config(qualifiers = "w411dp-h1500dp-xhdpi") @Test fun collectionPreviewD() = shotPreview(collectionPlan(), "collection_preview", true)
    @Config(qualifiers = "w411dp-h1500dp-xhdpi") @Test fun collectionMkdirL() = shotOps(collectionPlan(), "mkdir", "collection_mkdir", false)
    @Config(qualifiers = "w411dp-h1500dp-xhdpi") @Test fun collectionMkdirD() = shotOps(collectionPlan(), "mkdir", "collection_mkdir", true)
    @Config(qualifiers = "w411dp-h1500dp-xhdpi") @Test fun collectionMoveL() = shotOps(collectionPlan(), "move", "collection_move", false)
    @Config(qualifiers = "w411dp-h1500dp-xhdpi") @Test fun collectionMoveD() = shotOps(collectionPlan(), "move", "collection_move", true)
    @Config(qualifiers = "w411dp-h1500dp-xhdpi") @Test fun specialsRenameL() = shotOps(specialsPlan(), "rename", "specials_rename", false)
    @Config(qualifiers = "w411dp-h1500dp-xhdpi") @Test fun specialsRenameD() = shotOps(specialsPlan(), "rename", "specials_rename", true)
    @Config(qualifiers = "w411dp-h1500dp-xhdpi") @Test fun specialsPreviewL() = shotPreview(specialsPlan(), "specials_preview", false)
    @Config(qualifiers = "w411dp-h1500dp-xhdpi") @Test fun specialsPreviewD() = shotPreview(specialsPlan(), "specials_preview", true)

    // 删除多余文件夹：一个含其它文件（已勾选演示），一个空的；以及不支持删除的网盘
    private fun deletePlan(): ScrapePlan {
        val p = scan(MemDrive(listOf(
            "/剧集/雀骨2026/第一季/新建文件夹/Que.Gu.S01E01.mkv", "/剧集/雀骨2026/第一季/新建文件夹/说明.txt", "/剧集/雀骨2026/第一季/新建文件夹/封面.jpg",
            "/剧集/繁花/A/B/Fan.Hua.S01E01.mkv",
        )), "/剧集")
        return PlanSelection.set(p, p.actions.filter { it.op == "delete" }.take(1).map { it.id }, true)
    }
    private fun deleteBlockedPlan() = scan(MemDrive(listOf("/剧集/雀骨2026/第一季/新建文件夹/Que.Gu.S01E01.mkv", "/剧集/雀骨2026/第一季/新建文件夹/说明.txt"),
        support = TrashSupport.NONE, kind = ProviderKind.GUANGYA), "/剧集")
    @Test fun deleteL() = shotOps(deletePlan(), "delete", "delete_leftover", false)
    @Test fun deleteD() = shotOps(deletePlan(), "delete", "delete_leftover", true)
    @Test fun deleteBlockedL() = shotOps(deleteBlockedPlan(), "delete", "delete_no_permission", false)
    @Test fun deleteBlockedD() = shotOps(deleteBlockedPlan(), "delete", "delete_no_permission", true)

    // 历史
    private fun seedHistory(): HistoryRecord {
        val now = 1794268800000L
        val r = HistoryRecord("r1", now, "123-main", "pan123", "剧集", "雀骨",
            items = listOf(
                HistoryItem("rename", "雀骨2026", "雀骨2026", "雀骨(2026){tmdb-295558}", 295558, now),
                HistoryItem("rename", "雀骨(2026){tmdb-295558}", "新建文件夹", "Season 1", 295558, now),
                HistoryItem("rename", "Season 1", "Que.Gu.S01E01.2160p.WEB-DL.mkv", "雀骨.2026.S01E01.2160p.mkv", 295558, now),
                HistoryItem("rename", "Season 1", "Que.Gu.S01E02.2160p.WEB-DL.mkv", "雀骨.2026.S01E02.2160p.mkv", 295558, now),
                HistoryItem("artwork", "雀骨(2026){tmdb-295558}", "", "tvshow.nfo", 295558, now),
                HistoryItem("artwork", "雀骨(2026){tmdb-295558}", "", "poster.jpg", 295558, now),
            ),
            groups = listOf(HistoryGroup("f1", "雀骨(2026){tmdb-295558}", "tv", 295558, "雀骨", "2026",
                files = listOf("雀骨.2026.S01E01.2160p.mkv", "雀骨.2026.S01E02.2160p.mkv"))))
        HistoryStore.add(HistoryRecord("r2", now - 86400000L * 2, "dav", "webdav", "电影", "盗梦空间",
            items = listOf(HistoryItem("mkdir", "电影", "", "盗梦空间(2010){tmdb-27205}", 27205, now), HistoryItem("move", "电影", "Inception.2010.2160p.mkv", "盗梦空间(2010){tmdb-27205}", 27205, now)),
            groups = listOf(HistoryGroup("f2", "盗梦空间(2010){tmdb-27205}", "movie", 27205, "盗梦空间", "2010"))))
        HistoryStore.add(r)
        return r
    }
    private fun shotHistory(dark: Boolean) {
        seedHistory()
        rule.setContent { IosTheme(dark) { val nav = rememberNavController(); NavHost(nav, "h") { composable("h") { HistoryScreen(nav) } } } }
        rule.onRoot().captureRoboImage(out("history", dark))
    }
    private fun shotHistoryDetail(dark: Boolean) {
        val r = seedHistory()
        rule.setContent { IosTheme(dark) { val nav = rememberNavController(); NavHost(nav, "h") { composable("h") { HistoryDetailScreen(nav, r.id, r) } } } }
        rule.onRoot().captureRoboImage(out("history_detail", dark))
    }
    @Test fun historyL() = shotHistory(false)
    @Test fun historyD() = shotHistory(true)
    @Test fun historyDetailL() = shotHistoryDetail(false)
    @Test fun historyDetailD() = shotHistoryDetail(true)

    // 关于 / 开源许可 / 更新对话框
    private fun shotAbout(dark: Boolean) {
        rule.setContent { IosTheme(dark) { val nav = rememberNavController(); NavHost(nav, "a") { composable("a") { AboutScreen(nav) } } } }
        rule.onRoot().captureRoboImage(out("about", dark))
    }
    private fun shotAboutDialog(dark: Boolean) {
        rule.setContent { IosTheme(dark) { val nav = rememberNavController(); NavHost(nav, "a") { composable("a") { AboutScreen(nav, autoCheck = release) } } } }
        rule.waitForIdle()
        com.github.takahirom.roborazzi.captureScreenRoboImage(out("about_update_dialog", dark))
    }
    @Config(qualifiers = "w411dp-h891dp-xhdpi") @Test fun aboutDialogL() = shotAboutDialog(false)
    @Config(qualifiers = "w411dp-h891dp-xhdpi") @Test fun aboutDialogD() = shotAboutDialog(true)
    private fun shotLicenses(dark: Boolean) {
        rule.setContent { IosTheme(dark) { val nav = rememberNavController(); NavHost(nav, "a") { composable("a") { LicensesScreen(nav) } } } }
        rule.onRoot().captureRoboImage(out("licenses", dark))
    }
    private val release = UpdateResult.Available(ReleaseInfo("0.3.2", "PanScraper v0.3.2", "- 新增：电影合集整理\n- 修复：光鸭上传偶发失败\n- 优化：预览页动画",
        "https://github.com/example/releases/tag/v0.3.2", "https://example.invalid/app.apk", "PanScraper-v0.3.2.apk", 12_582_912))
    private fun shotUpdate(dark: Boolean, progress: Float?, name: String) {
        rule.setContent {
            IosTheme(dark) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = androidx.compose.ui.Alignment.Center) {
                    UpdateCard(release, {}, {}, progressOverride = progress)
                }
            }
        }
        rule.onRoot().captureRoboImage(out(name, dark))
    }
    @Test fun aboutL() = shotAbout(false)
    @Test fun aboutD() = shotAbout(true)
    @Test fun licensesL() = shotLicenses(false)
    @Test fun licensesD() = shotLicenses(true)
    @Config(qualifiers = "w411dp-h891dp-xhdpi") @Test fun updateL() = shotUpdate(false, null, "update_dialog")
    @Config(qualifiers = "w411dp-h891dp-xhdpi") @Test fun updateD() = shotUpdate(true, null, "update_dialog")
    @Config(qualifiers = "w411dp-h891dp-xhdpi") @Test fun updateProgL() = shotUpdate(false, 0.42f, "update_downloading")
    @Config(qualifiers = "w411dp-h891dp-xhdpi") @Test fun updateProgD() = shotUpdate(true, 0.42f, "update_downloading")

    // 命名设置：简洁命名关（子开关置灰 + 提示）/ 两个都开
    private fun shotNaming(dark: Boolean, simple: Boolean, tmdb: Boolean, name: String) {
        Thread.sleep(600) // 等 App.onCreate 里 DataStore 的初始读取先完成，避免覆盖下面的设置
        Settings.setSimpleNaming(simple); Settings.setTmdbInFileName(tmdb)
        rule.setContent {
            IosTheme(dark) {
                val c = Ios.colors
                Column(Modifier.fillMaxSize().background(c.groupedBg).padding(16.dp)) {
                    Text("刮削", style = IosType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp, top = 24.dp))
                    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(c.cell)) { NamingRows(showDivider = false) }
                }
            }
        }
        rule.onRoot().captureRoboImage(out(name, dark))
    }
    @Config(qualifiers = "w411dp-h1300dp-xhdpi") @Test fun namingOffL() = shotNaming(false, false, true, "naming_off")
    @Config(qualifiers = "w411dp-h1300dp-xhdpi") @Test fun namingOffD() = shotNaming(true, false, true, "naming_off")
    @Config(qualifiers = "w411dp-h1300dp-xhdpi") @Test fun namingOnL() = shotNaming(false, true, true, "naming_on")
    @Config(qualifiers = "w411dp-h1300dp-xhdpi") @Test fun namingOnD() = shotNaming(true, true, true, "naming_on")
}
