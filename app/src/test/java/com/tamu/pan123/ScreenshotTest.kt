package com.tamu.pan123

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.takahirom.roborazzi.captureRoboImage
import com.tamu.pan123.data.SecureStore
import com.tamu.pan123.ui.AppRoot
import com.tamu.pan123.ui.Tab
import com.tamu.pan123.ui.screens.AccountCenterScreen
import com.tamu.pan123.ui.screens.CloudDemo
import com.tamu.pan123.ui.screens.PasswordLoginScreen
import com.tamu.pan123.data.drive.CloudEntry
import com.tamu.pan123.ui.theme.IosTheme
import com.tamu.pan123.data.exec.TaskRun
import com.tamu.pan123.data.exec.TaskStore
import com.tamu.pan123.data.AppDirs
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Before
    fun setup() {
        SecureStore.init(RuntimeEnvironment.getApplication())
        com.tamu.pan123.data.drive.AccountStore.init()
    }

    /** 多账号演示：主 123 + 第二个 123 + 115 + 光鸭 + WebDAV（全是假凭据，不联网）。 */
    private fun seedMultiAccounts() {
        val S = com.tamu.pan123.data.drive.AccountStore
        if (S.accounts.value.any { it.kind == "115" }) return
        val second = S.add(com.tamu.pan123.data.drive.ProviderKind.P123, "123 · 139****0000")
        S.saveSecret(second.id, """{"token":"demo","exp":9999999999}""")
        S.add(com.tamu.pan123.data.drive.ProviderKind.P115, "115 · 测试",
            secret = com.tamu.pan123.data.p115.Drive115.encodeCreds(
                com.tamu.pan123.data.p115.P115Creds("UID","CID","SEID","KID","测试",1,"")))
        S.add(com.tamu.pan123.data.drive.ProviderKind.GUANGYA, "光鸭云盘")
        S.add(com.tamu.pan123.data.drive.ProviderKind.WEBDAV, "WebDAV · nas.local", extra = mapOf("url" to "https://nas.local/dav", "user" to "me"),
            secret = """{"password":"x"}""")
    }

    @Test fun accountsMultiLight() = shotAccounts("v2_accounts_light", false)
    @Test fun accountsMultiDark() = shotAccounts("v2_accounts_dark", true)

    private fun shotAccounts(name: String, dark: Boolean) {
        seedLoggedIn(); seedMultiAccounts()
        com.tamu.pan123.data.drive.AccountStore.setActive(com.tamu.pan123.data.drive.AccountStore.MAIN_123)
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "account") {
                    composable("account") { AccountCenterScreen(nav) }
                }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun addAccountLight() = shotAdd("v2_add_account_light", false)
    @Test fun addAccountDark() = shotAdd("v2_add_account_dark", true)

    private fun shotAdd(name: String, dark: Boolean) {
        seedLoggedIn()
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "add") {
                    composable("add") { com.tamu.pan123.ui.screens.AddAccountScreen(nav) }
                }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun scanProgressLight() = shotScanProgress("v21_scan_progress_light", false)
    @Test fun scanProgressDark() = shotScanProgress("v21_scan_progress_dark", true)

    private fun shotScanProgress(name: String, dark: Boolean) {
        val now = 1_800_000_000_000L
        com.tamu.pan123.data.net.NetLog.previewActivity(com.tamu.pan123.data.net.NetLog.Activity(
            requests = 7,
            inflight = "光鸭 POST api.guangyapan.com/userres/v1/file/get_file_list",
            inflightSince = now - 24_000,
            last = "光鸭 list parent=1967025791566299206 page=0 items=2 new=2 total=2",
            lastError = "光鸭 /userres/v1/file/get_file_list 请求超时（60 秒内无完整响应）",
            retryNote = "请求超时，第 1/2 次重试",
            retryAt = now + 3_000,
            lastActivityAt = now - 24_000,
        ))
        val p = com.tamu.pan123.data.scrape.Scanner.Progress(
            phase = "scan", message = "扫描：刮削测试/盗梦空间2010", foldersDone = 0, foldersTotal = 2,
            step = "读取目录：刮削测试/盗梦空间2010", startedAt = now - 41_000,
        )
        rule.setContent {
            IosTheme(dark) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(com.tamu.pan123.ui.theme.Ios.colors.groupedBg)
                        .padding(top = 48.dp),
                ) {
                    com.tamu.pan123.ui.screens.ScanProgressPanel(p, nowOverride = now)
                }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun tmdbNetLight() = shotTmdbNet("v21_tmdb_net_light", false)
    @Test fun tmdbNetDark() = shotTmdbNet("v21_tmdb_net_dark", true)

    private fun shotTmdbNet(name: String, dark: Boolean) {
        com.tamu.pan123.data.tmdb.TmdbNet.apply(com.tamu.pan123.data.tmdb.TmdbNetConfig(
            apiBase = com.tamu.pan123.data.tmdb.TmdbNetConfig.ALT_API, doh = true, proxyType = "http", proxyHost = "192.168.1.10", proxyPort = 7890))
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "t") {
                    composable("t") { com.tamu.pan123.ui.screens.TmdbNetScreen(nav) }
                }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
        com.tamu.pan123.data.tmdb.TmdbNet.apply(com.tamu.pan123.data.tmdb.TmdbNetConfig())
    }

    /** 真机闪退：任务页「去预览」在任务标签的导航图里找不到 scrape/preview。现在切到刮削标签再打开预览。 */
    @Test fun tasksOpenPreviewDoesNotCrash() {
        seedLoggedIn(); seedPlan()
        com.tamu.pan123.data.exec.ExecutionEngine.reportLive(null); com.tamu.pan123.data.exec.TaskStore.clear()
        rule.setContent { IosTheme(false) { AppRoot(Tab.TASKS) } }
        rule.onNodeWithText("打开预览").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("预刮削预览").fetchSemanticsNodes().isNotEmpty().let { assertTrue("preview not shown", it) }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/v22_tasks_to_preview_light.png")
    }

    @Test fun tasksEmptyLight() = shotTasksEmpty("v22_tasks_light", false)
    @Test fun tasksEmptyDark() = shotTasksEmpty("v22_tasks_dark", true)
    private fun shotTasksEmpty(name: String, dark: Boolean) {
        seedLoggedIn(); seedPlan()
        com.tamu.pan123.data.exec.ExecutionEngine.reportLive(null)
        com.tamu.pan123.data.exec.TaskStore.clear()
        rule.setContent { IosTheme(dark) { AppRoot(Tab.TASKS) } }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Config(qualifiers = "w411dp-h1500dp-xhdpi")
    @Test fun rematchedPreviewLight() = shotRematched("v22_preview_rematched_light", false)
    @Config(qualifiers = "w411dp-h1700dp-xhdpi")
    @Test fun rematchedMovieCleanupLight() {
        seedLoggedIn()
        val E = { id: String, n: String, dir: Boolean, p: String -> com.tamu.pan123.data.drive.CloudEntry(id, n, dir, if (dir) 0 else 10, p) }
        val drive = com.tamu.pan123.scrape.TreeDrive(mapOf(
            "F" to listOf(E("S1", "Season 1", true, "F"), E("nfo", "tvshow.nfo", false, "F"), E("pos", "poster.jpg", false, "F"),
                E("fan", "fanart.jpg", false, "F"), E("sp", "season01-poster.jpg", false, "F")),
            "S1" to listOf(E("v", "Walang.Hanggang.Paalam.2020.S01E20.2160p.WEB-DL.DV.H265.mkv", false, "S1")),
        ))
        val base = com.tamu.pan123.data.scrape.ScrapePlan(createdAt = "11-09 15:40",
            groups = listOf(com.tamu.pan123.data.scrape.PlanGroup("tv-105640-F", "tv", 105640, "Walang Hanggang Paalam", "2020",
                folderId = "F", folderName = "WalangHanggangPaalam(2020){tmdb-105640}")))
        val plan = kotlinx.coroutines.runBlocking {
            com.tamu.pan123.data.scrape.Scanner.replanGroup(base, "tv-105640-F",
                com.tamu.pan123.data.tmdb.MatchInfo("movie", 27205, "盗梦空间", "2010", null, ""), drive)
        }
        rule.setContent {
            IosTheme(false) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "p") {
                    composable("p") { com.tamu.pan123.ui.screens.PreviewScreen(nav, planOverride = plan) }
                }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/v22_preview_movie_cleanup_light.png")
    }
    @Test fun rematchedPreviewDark() = shotRematched("v22_preview_rematched_dark", true)
    private fun shotRematched(name: String, dark: Boolean) {
        seedLoggedIn()
        val drive = com.tamu.pan123.scrape.TreeDrive(mapOf("F" to listOf(
            com.tamu.pan123.data.drive.CloudEntry("e1", "Que.Gu.2026.S01E01.2160p.WEB-DL.mkv", false, 10, "F"),
            com.tamu.pan123.data.drive.CloudEntry("e2", "Que.Gu.2026.S01E02.2160p.WEB-DL.mkv", false, 10, "F"),
        )))
        val base = com.tamu.pan123.data.scrape.ScrapePlan(
            createdAt = "11-09 15:40",
            groups = listOf(com.tamu.pan123.data.scrape.PlanGroup("unmatched-F", "unmatched", title = "雀骨2026", folderId = "F", folderName = "雀骨2026")),
            flags = listOf("剧集「雀骨2026」TMDB 未匹配，整个文件夹未改名"),
        )
        val plan = kotlinx.coroutines.runBlocking {
            com.tamu.pan123.data.scrape.Scanner.replanGroup(base, "unmatched-F",
                com.tamu.pan123.data.tmdb.MatchInfo("tv", 295558, "雀骨", "2026", null, ""), drive)
        }.let { p -> com.tamu.pan123.data.scrape.PlanSelection.set(p, p.actions.filter { it.op == "artwork" }.map { it.id }, false) }
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "p") {
                    composable("p") { com.tamu.pan123.ui.screens.PreviewScreen(nav, planOverride = plan) }
                }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun demoRun(running: Boolean): com.tamu.pan123.data.exec.TaskRun {
        val T = com.tamu.pan123.data.exec.TaskItem
        val t0 = 1_762_731_000_000L
        fun it(id: String, op: String, kind: String, from: String, to: String, g: String, st: String, err: String? = null, note: String? = null, n: Int = 1) =
            com.tamu.pan123.data.exec.TaskItem(id, op, kind, com.tamu.pan123.data.exec.ExecPhases.of(op), from, to, g,
                folder = if (g == "雀骨") "雀骨(2026){tmdb-295558}" else "盗梦空间(2010){tmdb-27205}", status = st,
                at = if (st == T.WAIT) null else t0 + id.hashCode().mod(90_000), error = err, note = note, attempts = if (st == T.WAIT) 0 else n)
        val items = listOf(
            it("1", "rename", "folder", "雀骨2026", "雀骨(2026){tmdb-295558}", "雀骨", T.OK),
            it("2", "rename", "folder", "盗梦空间2010", "盗梦空间(2010){tmdb-27205}", "盗梦空间", T.OK),
            it("3", "rename", "file", "Que.Gu.2026.S01E01.2160p.WEB-DL.mkv", "雀骨.2026.S01E01.2160p.mkv", "雀骨", T.OK),
            it("4", "rename", "file", "Que.Gu.2026.S01E02.2160p.WEB-DL.mkv", "雀骨.2026.S01E02.2160p.mkv", "雀骨", T.OK),
            it("5", "mkdir", "folder", "", "Season 1", "雀骨", T.OK),
            it("6", "move", "file", "雀骨.2026.S01E01.2160p.mkv", "Season 1", "雀骨", T.OK),
            it("7", "move", "file", "雀骨.2026.S01E02.2160p.mkv", "Season 1", "雀骨", if (running) T.RUNNING else T.OK),
            it("8", "artwork", "image", "", "poster.jpg", "雀骨", if (running) T.WAIT else T.FAIL,
                err = "光鸭存储上传失败：NoSuchUpload（已自动重试 3 次）", n = 4),
            it("9", "artwork", "tvshow_nfo", "", "tvshow.nfo", "雀骨", if (running) T.WAIT else T.FAIL,
                err = "光鸭存储上传失败：NoSuchUpload（已自动重试 3 次）", n = 4),
            it("10", "artwork", "image", "", "fanart.jpg", "盗梦空间", if (running) T.WAIT else T.SKIP, note = if (running) null else "网盘里已有同名文件，未覆盖"),
            it("11", "artwork", "image", "", "poster.jpg", "盗梦空间", if (running) T.WAIT else T.FAIL,
                err = "下载海报失败：image.tmdb.org 连接超时（已换 media.themoviedb.org 仍失败）", n = 4),
        )
        return com.tamu.pan123.data.exec.TaskRun(
            id = "demo", planName = "scrape_demo.json", actionIds = items.map { it.id },
            status = if (running) com.tamu.pan123.data.exec.TaskRun.STATUS_RUNNING else com.tamu.pan123.data.exec.TaskRun.STATUS_DONE,
            startedAt = t0, finishedAt = if (running) null else t0 + 95_000, elapsedMs = if (running) 41_000 else 95_000,
            ok = items.count { x -> x.status == T.OK }, fail = items.count { x -> x.status == T.FAIL }, skipped = items.count { x -> x.status == T.SKIP },
            items = items, phase = if (running) 3 else 0, currentActionId = if (running) "7" else null,
            failedIds = items.filter { x -> x.status == T.FAIL }.map { x -> x.id },
            message = if (running) "第 3/4 步：移动文件 · 移动 雀骨.2026.S01E02.2160p.mkv → Season 1"
                else "完成：成功 7 · 失败 3 · 跳过 1\n失败原因：光鸭存储上传失败：NoSuchUpload（已自动重试 3 次）（2 项）；下载海报失败：image.tmdb.org 连接超时",
        )
    }

    private fun shotTaskPage(name: String, dark: Boolean, running: Boolean) {
        seedLoggedIn(); seedPlan()
        com.tamu.pan123.data.exec.ExecutionEngine.reportLive(demoRun(running))
        rule.setContent { IosTheme(dark) { AppRoot(Tab.TASKS) } }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
        com.tamu.pan123.data.exec.ExecutionEngine.reportLive(null)
    }

    @Test fun v23TaskDoneLight() = shotTaskPage("v23_task_done_light", false, false)
    @Test fun v23TaskDoneDark() = shotTaskPage("v23_task_done_dark", true, false)
    @Test fun v23TaskRunningLight() = shotTaskPage("v23_task_running_light", false, true)
    @Test fun v23TaskRunningDark() = shotTaskPage("v23_task_running_dark", true, true)

    private fun stoppedRun(): com.tamu.pan123.data.exec.TaskRun {
        val r = demoRun(true)
        val T = com.tamu.pan123.data.exec.TaskItem
        val items = r.items.map { if (it.status == T.RUNNING) it.copy(status = T.FAIL, error = "光鸭移动任务超时（连续失败 3 次，已暂停后结束）") else it }
        return r.copy(status = com.tamu.pan123.data.exec.TaskRun.STATUS_DONE, items = items, phase = 0, fail = 1, currentActionId = null,
            failedIds = listOf("7"), message = "完成：成功 6 · 失败 1（提前结束）")
    }

    @Test fun v30FailStoppedLight() = shotItems("fail", false, suffix = "_stopped", runOverride = stoppedRun())
    @Test fun v30FailStoppedDark() = shotItems("fail", true, suffix = "_stopped", runOverride = stoppedRun())

    private fun shotItems(filter: String, dark: Boolean, running: Boolean = false, sel: Set<String>? = null, collapsed: Set<String> = emptySet(), suffix: String = "", phase: Int = 0,
                          runOverride: com.tamu.pan123.data.exec.TaskRun? = null) {
        seedLoggedIn()
        val run = runOverride ?: demoRun(running)
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "p") {
                    composable("p") { com.tamu.pan123.ui.screens.TaskItemsScreen(nav, filter, runOverride = run, initialSelected = sel, initialCollapsed = collapsed, initialPhase = phase) }
                }
            }
        }
        val tag = (if (running) "running_" else "") + filter + suffix + if (dark) "_dark" else "_light"
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/v30_items_$tag.png")
    }

    @Config(qualifiers = "w411dp-h1500dp-xhdpi") @Test fun v23ProgressLight() = shotItems("progress", false)
    @Config(qualifiers = "w411dp-h1500dp-xhdpi") @Test fun v23ProgressDark() = shotItems("progress", true)
    @Config(qualifiers = "w411dp-h1500dp-xhdpi") @Test fun v23ProgressRunningLight() = shotItems("progress", false, true)
    @Config(qualifiers = "w411dp-h1500dp-xhdpi") @Test fun v23ProgressRunningDark() = shotItems("progress", true, true)
    private val QG = setOf("盗梦空间(2010){tmdb-27205}")
    @Config(qualifiers = "w411dp-h1100dp-xhdpi") @Test fun v24OkCollapsedLight() = shotItems("ok", false, collapsed = QG, suffix = "_collapsed")
    @Config(qualifiers = "w411dp-h1100dp-xhdpi") @Test fun v24OkCollapsedDark() = shotItems("ok", true, collapsed = QG, suffix = "_collapsed")
    @Config(qualifiers = "w411dp-h1500dp-xhdpi") @Test fun v24ProgressCollapsedLight() = shotItems("progress", false, true, collapsed = setOf("雀骨(2026){tmdb-295558}"), suffix = "_collapsed")
    @Config(qualifiers = "w411dp-h1500dp-xhdpi") @Test fun v24ProgressCollapsedDark() = shotItems("progress", true, true, collapsed = setOf("雀骨(2026){tmdb-295558}"), suffix = "_collapsed")
    @Config(qualifiers = "w411dp-h1100dp-xhdpi") @Test fun v24ProgressPhaseLight() = shotItems("progress", false, true, phase = 1, suffix = "_phase1")
    @Config(qualifiers = "w411dp-h1100dp-xhdpi") @Test fun v24ProgressPhaseDark() = shotItems("progress", true, true, phase = 1, suffix = "_phase1")
    @Test fun v24SkipCollapsedLight() = shotItems("skip", false, collapsed = QG, suffix = "_collapsed")
    @Test fun v24SkipCollapsedDark() = shotItems("skip", true, collapsed = QG, suffix = "_collapsed")
    @Config(qualifiers = "w411dp-h1300dp-xhdpi") @Test fun v23OkLight() = shotItems("ok", false)
    @Config(qualifiers = "w411dp-h1300dp-xhdpi") @Test fun v23OkDark() = shotItems("ok", true)
    @Config(qualifiers = "w411dp-h1100dp-xhdpi") @Test fun v23FailLight() = shotItems("fail", false)
    @Config(qualifiers = "w411dp-h1100dp-xhdpi") @Test fun v23FailDark() = shotItems("fail", true)
    @Config(qualifiers = "w411dp-h1100dp-xhdpi") @Test fun v23FailPartialLight() = shotItems("fail", false, sel = setOf("8", "11"), suffix = "_partial")
    @Config(qualifiers = "w411dp-h1100dp-xhdpi") @Test fun v23FailPartialDark() = shotItems("fail", true, sel = setOf("8", "11"), suffix = "_partial")
    @Test fun v23FailCollapsedLight() = shotItems("fail", false, sel = setOf("8"), collapsed = setOf("盗梦空间(2010){tmdb-27205}"), suffix = "_collapsed")
    @Test fun v23FailCollapsedDark() = shotItems("fail", true, sel = setOf("8"), collapsed = setOf("盗梦空间(2010){tmdb-27205}"), suffix = "_collapsed")
    @Test fun v23SkipLight() = shotItems("skip", false)
    @Test fun v23SkipDark() = shotItems("skip", true)

    /** 任务页「失败」行可点，进入二级页并显示原因和「全部重试」。 */
    @Test fun v23TapFailedRowOpensList() {
        seedLoggedIn(); seedPlan()
        com.tamu.pan123.data.exec.ExecutionEngine.reportLive(demoRun(false))
        rule.setContent { IosTheme(false) { AppRoot(Tab.TASKS) } }
        rule.onNodeWithText("失败").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("再次执行未完成项 (3)").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("原因：光鸭存储上传失败：NoSuchUpload（已自动重试 3 次）").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("全不选").fetchSemanticsNodes().isNotEmpty())
        // 收起文件夹 → 该文件夹的条目隐藏
        rule.onNodeWithText("盗梦空间(2010){tmdb-27205}").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("原因：下载海报失败：image.tmdb.org 连接超时（已换 media.themoviedb.org 仍失败）").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("全不选").performClick()
        assertTrue(rule.onAllNodesWithText("再次执行未完成项 (0)").fetchSemanticsNodes().isNotEmpty())
        com.tamu.pan123.data.exec.ExecutionEngine.reportLive(null)
    }

    // ---------------- v0.3.0 ----------------

    private fun demoPreviewPlan(): com.tamu.pan123.data.scrape.ScrapePlan {
        val E = { id: String, n: String, dir: Boolean, p: String -> com.tamu.pan123.data.drive.CloudEntry(id, n, dir, if (dir) 0 else 10, p) }
        val drive = com.tamu.pan123.scrape.TreeDrive(mapOf(
            "Q" to listOf(E("e1", "Que.Gu.2026.S01E01.2160p.WEB-DL.mkv", false, "Q"), E("e2", "Que.Gu.2026.S01E02.2160p.WEB-DL.mkv", false, "Q"),
                E("s1", "Que.Gu.2026.S01E01.2160p.WEB-DL.chs.srt", false, "Q")),
            "F" to listOf(E("S1", "Season 1", true, "F"), E("nfo", "tvshow.nfo", false, "F"), E("pos", "poster.jpg", false, "F")),
            "S1" to listOf(E("v", "Walang.Hanggang.Paalam.2020.S01E20.2160p.WEB-DL.DV.H265.mkv", false, "S1")),
        ))
        val base = com.tamu.pan123.data.scrape.ScrapePlan(createdAt = "11-09 17:40",
            groups = listOf(
                com.tamu.pan123.data.scrape.PlanGroup("unmatched-Q", "unmatched", title = "雀骨2026", folderId = "Q", folderName = "雀骨2026"),
                com.tamu.pan123.data.scrape.PlanGroup("tv-105640-F", "tv", 105640, "Walang Hanggang Paalam", "2020", folderId = "F", folderName = "WalangHanggangPaalam(2020){tmdb-105640}"),
                com.tamu.pan123.data.scrape.PlanGroup("unmatched-X", "unmatched", title = "某部老电影", folderId = "X", folderName = "某部老电影"),
            ),
            actions = listOf(
                com.tamu.pan123.data.scrape.PlanAction("ux1", "unmatched", "unmatched-X", "x1", "X", "Lao.Dian.Ying.1998.mkv", kind = "file", note = "TMDB 没搜到「某部老电影」"),
                com.tamu.pan123.data.scrape.PlanAction("ux2", "unmatched", "unmatched-X", "x2", "X", "Lao.Dian.Ying.1998.CD2.mkv", kind = "file", note = "TMDB 没搜到「某部老电影」"),
                com.tamu.pan123.data.scrape.PlanAction("sk1", "skip", "unmatched-X", "x3", "X", "sample.mkv", kind = "file", note = "样片，不处理"),
            ))
        return kotlinx.coroutines.runBlocking {
            val a = com.tamu.pan123.data.scrape.Scanner.replanGroup(base, "unmatched-Q",
                com.tamu.pan123.data.tmdb.MatchInfo("tv", 295558, "雀骨", "2026", null, ""), drive)
            com.tamu.pan123.data.scrape.Scanner.replanGroup(a, "tv-105640-F",
                com.tamu.pan123.data.tmdb.MatchInfo("movie", 27205, "盗梦空间", "2010", null, ""), drive)
        }
    }

    private fun shotPreview30(name: String, dark: Boolean) {
        seedLoggedIn()
        val plan = demoPreviewPlan()
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "p") { composable("p") { com.tamu.pan123.ui.screens.PreviewScreen(nav, planOverride = plan) } }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
    @Config(qualifiers = "w411dp-h1300dp-xhdpi") @Test fun v30PreviewLight() = shotPreview30("v30_preview_light", false)
    @Config(qualifiers = "w411dp-h1300dp-xhdpi") @Test fun v30PreviewDark() = shotPreview30("v30_preview_dark", true)

    private fun shotOps(type: String, dark: Boolean, collapsed: Set<String> = emptySet(), suffix: String = "") {
        seedLoggedIn()
        val plan = demoPreviewPlan()
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "p") { composable("p") { com.tamu.pan123.ui.screens.PreviewOpsScreen(nav, type, plan, collapsed) } }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/v30_ops_$type$suffix" + (if (dark) "_dark" else "_light") + ".png")
    }
    private val WG = setOf("WalangHanggangPaalam(2020){tmdb-105640}")
    @Config(qualifiers = "w411dp-h1100dp-xhdpi") @Test fun v30OpsRenameLight() = shotOps("rename", false)
    @Config(qualifiers = "w411dp-h1100dp-xhdpi") @Test fun v30OpsRenameDark() = shotOps("rename", true)
    @Config(qualifiers = "w411dp-h1100dp-xhdpi") @Test fun v30OpsRenameCollapsedLight() = shotOps("rename", false, WG, "_collapsed")
    @Config(qualifiers = "w411dp-h1100dp-xhdpi") @Test fun v30OpsRenameCollapsedDark() = shotOps("rename", true, WG, "_collapsed")
    @Test fun v30OpsMkdirLight() = shotOps("mkdir", false)
    @Test fun v30OpsMkdirDark() = shotOps("mkdir", true)
    @Test fun v30OpsMoveLight() = shotOps("move", false)
    @Test fun v30OpsMoveDark() = shotOps("move", true)
    @Config(qualifiers = "w411dp-h1100dp-xhdpi") @Test fun v30OpsArtworkLight() = shotOps("artwork", false)
    @Config(qualifiers = "w411dp-h1100dp-xhdpi") @Test fun v30OpsArtworkDark() = shotOps("artwork", true)
    @Config(qualifiers = "w411dp-h1100dp-xhdpi") @Test fun v30OpsCleanupLight() = shotOps("cleanup", false)
    @Config(qualifiers = "w411dp-h1100dp-xhdpi") @Test fun v30OpsCleanupDark() = shotOps("cleanup", true)
    @Test fun v30OpsUnmatchedLight() = shotOps("unmatched", false)
    @Test fun v30OpsUnmatchedDark() = shotOps("unmatched", true)
    @Test fun v30OpsSkipLight() = shotOps("skip", false)
    @Test fun v30OpsSkipDark() = shotOps("skip", true)

    /** 预览汇总行可点 → 二级页；在二级页勾选会更新汇总。 */
    /** v0.3.5：「移动」格子改为筛选（不再跳子页）；再点一次取消；筛选不改勾选。子页路由仍可直接打开。 */
    @Test fun v35PreviewMoveTileFilters() {
        seedLoggedIn()
        val plan = demoPreviewPlan()
        val before = com.tamu.pan123.data.scrape.PlanSelection.selectedIds(plan)
        rule.setContent {
            IosTheme(false) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "p") {
                    composable("p") { com.tamu.pan123.ui.screens.PreviewScreen(nav, planOverride = plan) }
                }
            }
        }
        val moves = plan.actions.count { com.tamu.pan123.data.scrape.PreviewFilter.opMatches(it, "move") }
        rule.onNode(hasContentDescription("移动，", substring = true)).performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("正在筛选：移动 · $moves 项").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("Season 1", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("执行 (${before.size})").fetchSemanticsNodes().isNotEmpty())
        rule.onNode(hasContentDescription("移动，", substring = true)).performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("正在筛选：", substring = true).fetchSemanticsNodes().isEmpty())
    }

    /** v0.3.5：点片名那一行展开 / 收起（默认展开第一组）；未匹配 / 跳过时操作格子不可点。 */
    @Test fun v35PreviewCollapseAndStatus() {
        seedLoggedIn()
        val plan = demoPreviewPlan()
        rule.setContent {
            IosTheme(false) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "p") { composable("p") { com.tamu.pan123.ui.screens.PreviewScreen(nav, planOverride = plan) } }
            }
        }
        val sd = androidx.compose.ui.semantics.SemanticsProperties.StateDescription
        val open = androidx.compose.ui.test.SemanticsMatcher.expectValue(sd, "已展开")
        val closed = androidx.compose.ui.test.SemanticsMatcher.expectValue(sd, "已收起")
        assertTrue(rule.onAllNodes(open).fetchSemanticsNodes().size == 1)
        val nClosed = rule.onAllNodes(closed).fetchSemanticsNodes().size
        rule.onAllNodes(open)[0].performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(open).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(closed).fetchSemanticsNodes().size >= maxOf(1, nClosed))  // 懒加载：收起后可能露出更多分组
        // 选「未匹配」：下排变灰（不可点），提示条出现
        val un = com.tamu.pan123.data.scrape.PreviewFilter.statusCount(plan, com.tamu.pan123.data.scrape.PreviewFilter.Status.UNMATCHED)
        rule.onNodeWithText("未匹配 $un").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("正在筛选：未匹配", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNode(hasContentDescription("移动，", substring = true)).assertIsNotEnabled()
        rule.onNode(hasContentDescription("取消筛选")).performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("正在筛选：", substring = true).fetchSemanticsNodes().isEmpty())
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/v35_preview_collapsed_light.png")
    }

    /** v0.3.5：设置页「账号与网络」在「刮削 · 命名」上面。 */
    private fun shotSettings35(name: String, dark: Boolean) {
        seedLoggedIn()
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "s") { composable("s") { com.tamu.pan123.ui.screens.SettingsScreen(nav) } }
            }
        }
        val acc = rule.onNodeWithText("账号与网络", substring = true).fetchSemanticsNode().boundsInRoot.top
        val scr = rule.onNodeWithText("刮削 · 命名", substring = true).fetchSemanticsNode().boundsInRoot.top
        assertTrue("账号与网络应在刮削 · 命名上面", acc < scr)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
    @Config(qualifiers = "w411dp-h1300dp-xhdpi") @Test fun v35SettingsLight() = shotSettings35("v35_settings_light", false)
    @Config(qualifiers = "w411dp-h1300dp-xhdpi") @Test fun v35SettingsDark() = shotSettings35("v35_settings_dark", true)

    @Test fun v30PreviewOpsSubpageStillOpens() {
        seedLoggedIn()
        val plan = demoPreviewPlan()
        rule.setContent {
            IosTheme(false) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "scrape/preview/ops/move") {
                    composable("scrape/preview/ops/{type}") { e -> com.tamu.pan123.ui.screens.PreviewOpsScreen(nav, e.arguments?.getString("type")!!, plan) }
                }
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("全不选").fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("Season 1/", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    private fun shotAdd30(name: String, dark: Boolean) {
        seedLoggedIn()
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "a") { composable("a") { com.tamu.pan123.ui.screens.AddAccountScreen(nav) } }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
    @Test fun v30AddAccountLight() = shotAdd30("v30_add_account_light", false)
    @Test fun v30AddAccountDark() = shotAdd30("v30_add_account_dark", true)
    @Test fun v30P115LoginLight() = shotP115Login("v30_p115_login_light", false)
    @Test fun v30P115LoginDark() = shotP115Login("v30_p115_login_dark", true)
    @Test fun v30AccountCenterLight() {
        seedLoggedIn(); seed115()
        rule.setContent {
            IosTheme(false) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "a") { composable("a") { com.tamu.pan123.ui.screens.AccountCenterScreen(nav) } }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/v30_account_center_light.png")
    }

    private fun shotWebdavHelp(name: String, dark: Boolean) {
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "w") { composable("w") { com.tamu.pan123.ui.screens.WebDavFormScreen(nav) } }
            }
        }
        rule.onNodeWithText("怎么填？").performClick()
        rule.onNodeWithText("http://192.168.1.7:5244/dav").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
    @Config(qualifiers = "w411dp-h1250dp-xhdpi") @Test fun v30WebdavHelpLight() = shotWebdavHelp("v30_webdav_help_light", false)
    @Config(qualifiers = "w411dp-h1250dp-xhdpi") @Test fun v30WebdavHelpDark() = shotWebdavHelp("v30_webdav_help_dark", true)

    private fun shotIcon(name: String, dark: Boolean) {
        rule.setContent {
            IosTheme(dark) {
                val bg = if (dark) androidx.compose.ui.graphics.Color(0xFF1C1C1E) else androidx.compose.ui.graphics.Color(0xFFE5E5EA)
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.fillMaxSize().background(bg).padding(24.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp),
                ) {
                    @androidx.compose.runtime.Composable
                    fun tile(size: Int, shape: androidx.compose.ui.graphics.Shape, mono: Boolean = false) {
                        androidx.compose.foundation.layout.Box(
                            androidx.compose.ui.Modifier.size(size.dp).clip(shape)
                                .then(if (mono) androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color(0xFF3A3A3C))
                                      else androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(
                                          listOf(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color(0xFFEEF0F5))))),
                        ) {
                            androidx.compose.foundation.Image(
                                androidx.compose.ui.res.painterResource(if (mono) com.tamu.pan123.R.drawable.ic_launcher_mono else com.tamu.pan123.R.drawable.ic_launcher_fg),
                                null,
                                // 108dp 画布，图标缩放到 tile 的 150%（与 adaptive icon 的裁切一致）
                                androidx.compose.ui.Modifier.requiredSize((size * 1.5f).dp).align(androidx.compose.ui.Alignment.Center),
                                colorFilter = if (mono) androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.ui.graphics.Color(0xFFFFD60A)) else null,
                            )
                        }
                    }
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                        tile(180, androidx.compose.foundation.shape.RoundedCornerShape(40.dp))
                        tile(96, androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                        tile(60, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    }
                    androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp)) {
                        tile(96, androidx.compose.foundation.shape.CircleShape)
                        tile(96, androidx.compose.foundation.shape.RoundedCornerShape(30.dp))
                        tile(96, androidx.compose.foundation.shape.CircleShape, mono = true)
                    }
                    androidx.compose.material3.Text("123刮削 · 圆角方形 / 圆形遮罩 / 主题单色图标", color = if (dark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black)
                }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
    @Test fun v30IconLight() = shotIcon("v30_icon_light", false)
    @Test fun v30IconDark() = shotIcon("v30_icon_dark", true)

    @Test fun aboutLight() = shotAbout("v2e_about_light", false)
    @Test fun aboutDark() = shotAbout("v2e_about_dark", true)

    private fun shotAbout(name: String, dark: Boolean) {
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "a") {
                    composable("a") { com.tamu.pan123.ui.screens.AboutScreen(nav) }
                }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun providerInfo115Light() = shotInfo("v2_info_115_light", false, "115")
    @Test fun webdavFormDark() = shotWebdav("v2_webdav_form_dark", true)

    private fun shotInfo(name: String, dark: Boolean, kind: String) {
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "i") {
                    composable("i") { com.tamu.pan123.ui.screens.ProviderInfoScreen(nav, kind) }
                }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun shotWebdav(name: String, dark: Boolean) {
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "w") {
                    composable("w") { com.tamu.pan123.ui.screens.WebDavFormScreen(nav) }
                }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun drivePickerLight() = shotPicker("v2_cloud_picker_light", false)
    @Test fun drivePickerDark() = shotPicker("v2_cloud_picker_dark", true)

    private fun shotPicker(name: String, dark: Boolean) {
        seedLoggedIn(); seedMultiAccounts()
        com.tamu.pan123.data.drive.AccountStore.setActive(com.tamu.pan123.data.drive.AccountStore.MAIN_123)
        CloudDemo.items = listOf(
            CloudEntry(fileId = "1", fileName = "资源", isDir = true),
            CloudEntry(fileId = "2", fileName = "电影", isDir = true),
        )
        try {
            rule.setContent { IosTheme(dark) { AppRoot(Tab.CLOUD) } }
            rule.mainClock.advanceTimeBy(300)
            rule.onNodeWithText("当前网盘").performClick()
            rule.mainClock.advanceTimeBy(300)
            rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
        } finally {
            CloudDemo.items = null
        }
    }

    @Test fun pendingDriveLight() = shotPending("v2_cloud_pending_light", false)

    private fun shotPending(name: String, dark: Boolean) {
        // 115 已实现：截一张「已添加但未登录」的空态（原「即将支持」占位）
        seedLoggedIn()
        val S = com.tamu.pan123.data.drive.AccountStore
        val a = S.accounts.value.firstOrNull { it.kind == "115" }
            ?: S.add(com.tamu.pan123.data.drive.ProviderKind.P115, "115网盘")
        // 清掉凭据模拟未登录
        com.tamu.pan123.data.SecureStore.removeSecret("acct.${a.id}")
        S.setActive(a.id)
        rule.setContent { IosTheme(dark) { AppRoot(Tab.CLOUD) } }
        rule.mainClock.advanceTimeBy(300)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun seedLoggedIn() {
        val flags = android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        val header = android.util.Base64.encodeToString("""{"alg":"HS256"}""".toByteArray(), flags)
        val exp = System.currentTimeMillis() / 1000 + 90L * 86400
        val payload = android.util.Base64.encodeToString("""{"exp":$exp,"sub":"screenshot"}""".toByteArray(), flags)
        SecureStore.saveToken("$header.$payload.sig", passport = "13800138000", expiresAt = exp)
        SecureStore.saveTmdbKey("abcd1234efgh5678")
    }

    private fun seedLoggedOut() {
        SecureStore.clearToken()
        SecureStore.clearTmdbKey()
    }

    private fun shot(name: String, dark: Boolean, tab: Tab = Tab.CLOUD, loggedIn: Boolean = false) {
        if (loggedIn) seedLoggedIn() else seedLoggedOut()
        rule.setContent { IosTheme(dark) { AppRoot(tab) } }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun phoneLight() = shot("phone_cloud_out_light", false)
    @Test fun phoneDark() = shot("phone_cloud_out_dark", true)
    @Test fun settingsLight() = shot("settings_light", false, Tab.SETTINGS)
    @Test fun settingsDark() = shot("settings_dark", true, Tab.SETTINGS)

    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    @Test fun tabletLight() = shot("tablet_cloud_out_light", false)

    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    @Test fun tabletDark() = shot("tablet_cloud_out_dark", true)

    @Test fun accountLight() = shotAccount("account_in_light", false)
    @Test fun accountDark() = shotAccount("account_in_dark", true)

    private fun shotAccount(name: String, dark: Boolean) {
        seedLoggedIn()
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "account") {
                    composable("account") { AccountCenterScreen(nav) }
                    composable("settings/account/web-login") { }
                    composable("settings/account/password-login") { }
                    composable("settings/account/tmdb-key") { }
                }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun passwordLoginLight() = shotLogin("password_login_light", false)
    @Test fun passwordLoginDark() = shotLogin("password_login_dark", true)

    private fun shotLogin(name: String, dark: Boolean) {
        seedLoggedOut()
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "p") {
                    composable("p") { PasswordLoginScreen(nav) }
                }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun cloudInLight() = shotCloud("cloud_in_light", false)
    @Test fun cloudInDark() = shotCloud("cloud_in_dark", true)

    private fun shotCloud(name: String, dark: Boolean) {
        seedLoggedIn()
        CloudDemo.items = listOf(
            CloudEntry(fileId = "1", fileName = "资源", isDir = true),
            CloudEntry(fileId = "2", fileName = "Videos", isDir = true),
            CloudEntry(fileId = "3", fileName = "剧集", isDir = true),
            CloudEntry(fileId = "4", fileName = "电影", isDir = true),
            CloudEntry(fileId = "5", fileName = "readme.txt", isDir = false, size = 1280),
        )
        try {
            rule.setContent { IosTheme(dark) { AppRoot(Tab.CLOUD) } }
            rule.mainClock.advanceTimeBy(300)
            rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
        } finally {
            CloudDemo.items = null
        }
    }

    @Test fun scrapeTargetsLight() = shotScrape("scrape_targets_light", false)
    @Test fun scrapeTargetsDark() = shotScrape("scrape_targets_dark", true)
    @Test fun previewLight() = shotPreview("preview_light", false)
    @Test fun previewDark() = shotPreview("preview_dark", true)
    @Test fun rematchLight() = shotRematch("rematch_light", false)
    @Test fun rematchDark() = shotRematch("rematch_dark", true)

    private fun seedPlan() {
        com.tamu.pan123.data.AppDirs.init(RuntimeEnvironment.getApplication())
        val plan = com.tamu.pan123.data.scrape.ScrapePlan(
            createdTs = System.currentTimeMillis() / 1000.0,
            createdAt = "2026-11-09 12:30:00 PT",
            roots = listOf(com.tamu.pan123.data.scrape.PlanRoot("1", "2026")),
            groups = listOf(
                com.tamu.pan123.data.scrape.PlanGroup(
                    id = "tv-106841-1", kind = "tv", tmdbId = 106841,
                    title = "繁花", year = "2023", posterPath = "/poster.jpg",
                    folderId = "300", folderName = "繁花 (2023)",
                    folderNewName = "繁花(2023){tmdb-106841}",
                    itemIds = listOf("a1", "a2"),
                ),
                com.tamu.pan123.data.scrape.PlanGroup(
                    id = "movie-27205-1", kind = "movie", tmdbId = 27205,
                    title = "盗梦空间", year = "2010",
                    folderId = "100", folderName = "Inception.2010",
                    folderNewName = "盗梦空间(2010){tmdb-27205}",
                    itemIds = listOf("b1"),
                ),
            ),
            actions = listOf(
                com.tamu.pan123.data.scrape.PlanAction("a1", "rename", "tv-106841-1", "5001", "301",
                    "Blossoms.Shanghai.S01E01.mkv", "繁花.2023.S01E01.2160p.DoVi.mkv", kind = "file", mediaKind = "tv", tmdbId = 106841),
                com.tamu.pan123.data.scrape.PlanAction("a2", "rename", "tv-106841-1", "300", "0",
                    "繁花 (2023)", "繁花(2023){tmdb-106841}", kind = "folder", mediaKind = "tv", tmdbId = 106841),
                com.tamu.pan123.data.scrape.PlanAction("b1", "rename", "movie-27205-1", "101", "100",
                    "Inception.2010.mkv", "盗梦空间.2010.2160p.DoVi.mkv", kind = "file", mediaKind = "movie", tmdbId = 27205),
                com.tamu.pan123.data.scrape.PlanAction("b2", "artwork", "tv-106841-1", parentId = "300", newName = "poster.jpg", kind = "image", tmdbId = 106841),
            ),
            flags = listOf("目标名重复示例已用 .v2"),
            stats = com.tamu.pan123.data.scrape.PlanStats(rename = 3, artwork = 1, groups = 2),
        )
        com.tamu.pan123.data.scrape.PlanStore.save(plan, "scrape_demo.json")
    }

    private fun shotScrape(name: String, dark: Boolean) {
        seedLoggedIn()
        com.tamu.pan123.ui.screens.ScanTargets.selection =
            com.tamu.pan123.data.pan.BrowseSelection(mapOf("33047126" to "资源/Videos/剧集/国产/2026"))
        com.tamu.pan123.ui.screens.ScanTargets.fullDrive = false
        rule.setContent { IosTheme(dark) { AppRoot(Tab.SCRAPE) } }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun shotPreview(name: String, dark: Boolean) {
        seedLoggedIn(); seedPlan()
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, "preview") {
                    composable("preview") { com.tamu.pan123.ui.screens.PreviewScreen(nav) }
                    composable("scrape/rematch/{groupId}") { }
                }
            }
        }
        rule.mainClock.advanceTimeBy(200)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun shotRematch(name: String, dark: Boolean) {
        seedLoggedIn(); seedPlan()
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, "r") {
                    composable("r") { com.tamu.pan123.ui.screens.RematchScreen(nav, "tv-106841-1") }
                }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun taskRunningLight() = shotTask("task_running_light", false, TaskRun.STATUS_RUNNING)
    @Test fun taskRunningDark() = shotTask("task_running_dark", true, TaskRun.STATUS_RUNNING)
    @Test fun taskPausedLight() = shotTask("task_paused_light", false, TaskRun.STATUS_PAUSED, fail = 3)
    @Test fun taskPausedDark() = shotTask("task_paused_dark", true, TaskRun.STATUS_PAUSED, fail = 3)
    @Test fun taskDoneLight() = shotTask("task_done_light", false, TaskRun.STATUS_DONE, fail = 2)
    @Test fun taskDoneDark() = shotTask("task_done_dark", true, TaskRun.STATUS_DONE, fail = 2)

    private fun shotTask(name: String, dark: Boolean, status: String, fail: Int = 0) {
        seedLoggedIn()
        AppDirs.init(RuntimeEnvironment.getApplication())
        TaskStore.save(
            TaskRun(
                id = "shot",
                planName = "scrape_demo.json",
                actionIds = listOf("a1", "a2", "a3", "a4", "a5"),
                status = status,
                startedAt = System.currentTimeMillis() - 45_000,
                finishedAt = if (status == TaskRun.STATUS_DONE) System.currentTimeMillis() else null,
                ok = if (status == TaskRun.STATUS_DONE) 3 else 2,
                fail = fail,
                skipped = 0,
                message = when (status) {
                    TaskRun.STATUS_RUNNING -> "改名 Blossoms… → 繁花.2023.S01E01…"
                    TaskRun.STATUS_PAUSED -> "连续失败 3 次，已暂停"
                    else -> "完成：成功 3 · 失败 $fail"
                },
                failedIds = if (fail > 0) listOf("a4", "a5") else emptyList(),
                elapsedMs = 45_000,
            )
        )
        // 预置计划供失败列表显示
        seedPlan()
        rule.setContent { IosTheme(dark) { AppRoot(Tab.TASKS) } }
        rule.mainClock.advanceTimeBy(200)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun dataCleanLight() = shotData("data_clean_light", false)
    @Test fun dataCleanDark() = shotData("data_clean_dark", true)
    @Test fun dataConfirmLight() = shotDataConfirm("data_confirm_light", false)
    @Test fun dataConfirmDark() = shotDataConfirm("data_confirm_dark", true)

    private fun seedDataFiles() {
        AppDirs.init(RuntimeEnvironment.getApplication())
        java.io.File(AppDirs.plans, "demo.json").writeText("""{"x":1}""")
        java.io.File(AppDirs.imgCache, "p.jpg").writeBytes(ByteArray(2048) { 1 })
        java.io.File(AppDirs.tmdbCache, "a.json").writeText("{}")
        java.io.File(AppDirs.logs, "app.log").writeText("hello")
    }

    private fun shotData(name: String, dark: Boolean) {
        seedLoggedIn(); seedDataFiles()
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, "d") {
                    composable("d") { com.tamu.pan123.ui.screens.DataScreen(nav) }
                }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun shotDataConfirm(name: String, dark: Boolean) {
        seedLoggedIn(); seedDataFiles()
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, "d") {
                    composable("d") { com.tamu.pan123.ui.screens.DataScreen(nav) }
                }
            }
        }
        rule.onNodeWithText("清理全部数据").performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(400)
        // Dialog 会多出一个 root，截最后一个（含弹窗）
        rule.onAllNodes(isRoot()).onLast().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    // ---------------- v0.2 Milestone B：WebDAV ----------------

    private fun seedDav(): com.tamu.pan123.data.drive.DriveAccount {
        val S = com.tamu.pan123.data.drive.AccountStore
        S.accounts.value.firstOrNull { it.kind == "webdav" && it.label == "WebDAV · NAS" }?.let { S.setActive(it.id); return it }
        val a = S.add(com.tamu.pan123.data.drive.ProviderKind.WEBDAV, "WebDAV · NAS",
            extra = mapOf("url" to "http://192.168.1.10:5005/dav", "user" to "me", "insecure" to "0"),
            secret = """{"password":"x"}""")
        S.setActive(a.id)
        return a
    }

    /** 用本机 wsgidav 端到端测试生成的真实计划渲染预览（文件不存在则跳过）。 */
    @Test fun davPreviewLight() = shotDavPreview("v2b_dav_preview_light", false)
    @Test fun davPreviewDark() = shotDavPreview("v2b_dav_preview_dark", true)

    private fun shotDavPreview(name: String, dark: Boolean) {
        val f = java.io.File("/workspace/davtest/e2e/plan.json")
        org.junit.Assume.assumeTrue(f.isFile)
        seedLoggedIn()
        com.tamu.pan123.data.AppDirs.init(RuntimeEnvironment.getApplication())
        com.tamu.pan123.data.scrape.PlanStore.save(com.tamu.pan123.data.scrape.PlanStore.load(f), "scrape_dav.json")
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, "preview") {
                    composable("preview") { com.tamu.pan123.ui.screens.PreviewScreen(nav) }
                    composable("scrape/rematch/{groupId}") { }
                }
            }
        }
        rule.mainClock.advanceTimeBy(200)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun davCloudLight() = shotDavCloud("v2b_dav_cloud_light", false)
    @Test fun davCloudDark() = shotDavCloud("v2b_dav_cloud_dark", true)

    private fun shotDavCloud(name: String, dark: Boolean) {
        seedLoggedIn(); seedDav()
        CloudDemo.items = listOf(
            CloudEntry("/剧集", "剧集", true, parentFileId = "/"),
            CloudEntry("/杂项", "杂项", true, parentFileId = "/"),
            CloudEntry("/电影", "电影", true, parentFileId = "/"),
            CloudEntry("/README.txt", "README.txt", false, 2048, "/"),
        )
        try {
            rule.setContent { IosTheme(dark) { AppRoot(Tab.CLOUD) } }
            rule.mainClock.advanceTimeBy(300)
            rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
        } finally {
            CloudDemo.items = null
        }
    }

    @Test fun davAccountLight() = shotDavAccount("v2b_dav_account_light", false)
    @Test fun davAccountDark() = shotDavAccount("v2b_dav_account_dark", true)

    private fun shotDavAccount(name: String, dark: Boolean) {
        seedLoggedIn(); val a = seedDav()
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, "a") { composable("a") { com.tamu.pan123.ui.screens.DriveAccountScreen(nav, a.id) } }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun davFormLight() = shotWebdav("v2b_dav_form_light", false)
    @Test fun davFormDark2() = shotWebdav("v2b_dav_form_dark", true)

    // ---------------- v0.2 Milestone C：光鸭云盘 + 电影海报 / NFO ----------------

    private fun seedGy(): com.tamu.pan123.data.drive.DriveAccount {
        val S = com.tamu.pan123.data.drive.AccountStore
        S.accounts.value.firstOrNull { it.kind == "guangya" && it.label == "光鸭 · 8848" }?.let { S.setActive(it.id); return it }
        // 假凭据，不联网
        val a = S.add(com.tamu.pan123.data.drive.ProviderKind.GUANGYA, "光鸭 · 8848",
            secret = com.tamu.pan123.data.guangya.GuangyaDrive.encodeCreds(
                com.tamu.pan123.data.guangya.GyCreds("demo-at", "demo-rt", 0, "0".repeat(32), "10008848")))
        S.setActive(a.id)
        return a
    }

    @Test fun gyLoginLight() = shotGyLogin("v2c_gy_login_light", false)
    @Test fun gyLoginDark() = shotGyLogin("v2c_gy_login_dark", true)

    private fun shotGyLogin(name: String, dark: Boolean) {
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, "l") { composable("l") { com.tamu.pan123.ui.screens.GuangyaLoginScreen(nav, null, showWeb = false) } }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun gyAccountLight() = shotGyAccount("v2c_gy_account_light", false)
    @Test fun gyAccountDark() = shotGyAccount("v2c_gy_account_dark", true)

    private fun shotGyAccount(name: String, dark: Boolean) {
        seedLoggedIn(); val a = seedGy()
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, "a") { composable("a") { com.tamu.pan123.ui.screens.DriveAccountScreen(nav, a.id) } }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun gyAddLight() = shotAdd("v2c_add_account_light", false)

    @Test fun gyCloudLight() = shotGyCloud("v2c_gy_cloud_light", false)
    @Test fun gyCloudDark() = shotGyCloud("v2c_gy_cloud_dark", true)

    private fun shotGyCloud(name: String, dark: Boolean) {
        seedLoggedIn(); seedGy()
        CloudDemo.items = listOf(
            CloudEntry("f01", "我的资源", true, parentFileId = ""),
            CloudEntry("f02", "电影", true, parentFileId = ""),
            CloudEntry("f03", "剧集", true, parentFileId = ""),
            CloudEntry("f04", "readme.txt", false, 1024, ""),
        )
        try {
            rule.setContent { IosTheme(dark) { AppRoot(Tab.CLOUD) } }
            rule.mainClock.advanceTimeBy(300)
            rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
        } finally {
            CloudDemo.items = null
        }
    }

    @Test fun movieArtPreviewLight() = shotMovieArt("v2c_movie_artwork_preview_light", false)
    @Test fun movieArtPreviewDark() = shotMovieArt("v2c_movie_artwork_preview_dark", true)

    private fun shotMovieArt(name: String, dark: Boolean) {
        seedLoggedIn(); val a = seedGy()
        com.tamu.pan123.data.AppDirs.init(RuntimeEnvironment.getApplication())
        val plan = com.tamu.pan123.data.scrape.ScrapePlan(
            accountId = a.id, provider = "guangya",
            createdTs = System.currentTimeMillis() / 1000.0,
            createdAt = "2026-11-09 15:10:00 PT",
            roots = listOf(com.tamu.pan123.data.scrape.PlanRoot("f02", "电影")),
            groups = listOf(
                com.tamu.pan123.data.scrape.PlanGroup(
                    id = "folder-m1", kind = "movie", tmdbId = 27205, title = "盗梦空间", year = "2010",
                    folderId = "m1", folderName = "Inception.2010.2160p",
                    folderNewName = "盗梦空间(2010){tmdb-27205}",
                    itemIds = listOf("c1", "c2", "c3", "c4", "c5"),
                ),
            ),
            actions = listOf(
                com.tamu.pan123.data.scrape.PlanAction("c1", "rename", "folder-m1", "v1", "m1",
                    "Inception.2010.2160p.BluRay.mkv", "盗梦空间.2010.2160p.BluRay.mkv", kind = "file", mediaKind = "movie", tmdbId = 27205),
                com.tamu.pan123.data.scrape.PlanAction("c2", "rename", "folder-m1", "m1", "f02",
                    "Inception.2010.2160p", "盗梦空间(2010){tmdb-27205}", kind = "folder", mediaKind = "movie", tmdbId = 27205),
                com.tamu.pan123.data.scrape.PlanAction("c3", "artwork", "folder-m1", parentId = "m1", newName = "poster.jpg", kind = "image", mediaKind = "movie", tmdbId = 27205),
                com.tamu.pan123.data.scrape.PlanAction("c4", "artwork", "folder-m1", parentId = "m1", newName = "fanart.jpg", kind = "image", mediaKind = "movie", tmdbId = 27205),
                com.tamu.pan123.data.scrape.PlanAction("c5", "artwork", "folder-m1", parentId = "m1", newName = "movie.nfo", kind = "movie_nfo", mediaKind = "movie", tmdbId = 27205),
            ),
            stats = com.tamu.pan123.data.scrape.PlanStats(rename = 2, artwork = 3, groups = 1),
        )
        com.tamu.pan123.data.scrape.PlanStore.save(plan, "scrape_gy.json")
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, "preview") {
                    composable("preview") { com.tamu.pan123.ui.screens.PreviewScreen(nav) }
                    composable("scrape/rematch/{groupId}") { }
                }
            }
        }
        rule.mainClock.advanceTimeBy(200)
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    // ---------------- v0.2 Milestone D：115 网盘 ----------------

    private fun seed115(): com.tamu.pan123.data.drive.DriveAccount {
        val S = com.tamu.pan123.data.drive.AccountStore
        S.accounts.value.firstOrNull { it.kind == "115" && it.label == "115 · 测试用户" }?.let { S.setActive(it.id); return it }
        val a = S.add(com.tamu.pan123.data.drive.ProviderKind.P115, "115 · 测试用户",
            secret = com.tamu.pan123.data.p115.Drive115.encodeCreds(
                com.tamu.pan123.data.p115.P115Creds("UID1","CID1","SEID1","KID1","测试用户",12345,"UK")))
        S.setActive(a.id)
        return a
    }

    @Test fun p115LoginLight() = shotP115Login("v2d_p115_login_light", false)
    @Test fun p115LoginDark() = shotP115Login("v2d_p115_login_dark", true)

    private fun shotP115Login(name: String, dark: Boolean) {
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, "l") { composable("l") { com.tamu.pan123.ui.screens.P115LoginScreen(nav, null, preview = true) } }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun p115AccountLight() = shotP115Account("v2d_p115_account_light", false)
    @Test fun p115AccountDark() = shotP115Account("v2d_p115_account_dark", true)

    private fun shotP115Account(name: String, dark: Boolean) {
        seedLoggedIn(); val a = seed115()
        rule.setContent {
            IosTheme(dark) {
                val nav = rememberNavController()
                NavHost(nav, "a") { composable("a") { com.tamu.pan123.ui.screens.DriveAccountScreen(nav, a.id) } }
            }
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun p115CloudLight() = shotP115Cloud("v2d_p115_cloud_light", false)
    @Test fun p115CloudDark() = shotP115Cloud("v2d_p115_cloud_dark", true)

    private fun shotP115Cloud(name: String, dark: Boolean) {
        seedLoggedIn(); seed115()
        CloudDemo.items = listOf(
            CloudEntry("1", "电影", true, parentFileId = "0"),
            CloudEntry("2", "剧集", true, parentFileId = "0"),
            CloudEntry("3", "纪录片", true, parentFileId = "0"),
            CloudEntry("4", "说明.txt", false, 512, "0"),
        )
        try {
            rule.setContent { IosTheme(dark) { AppRoot(Tab.CLOUD) } }
            rule.mainClock.advanceTimeBy(300)
            rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
        } finally {
            CloudDemo.items = null
        }
    }

    @Test fun p115AddLight() = shotAdd("v2d_add_account_light", false)
}
