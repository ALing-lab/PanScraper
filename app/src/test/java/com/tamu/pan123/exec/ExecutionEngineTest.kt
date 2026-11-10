package com.tamu.pan123.exec

import com.tamu.pan123.data.AppDirs
import com.tamu.pan123.data.exec.ExecutionEngine
import com.tamu.pan123.data.exec.TaskRun
import com.tamu.pan123.data.exec.TaskStore
import com.tamu.pan123.data.scrape.PlanAction
import com.tamu.pan123.data.scrape.PlanGroup
import com.tamu.pan123.data.scrape.PlanStats
import com.tamu.pan123.data.scrape.PlanStore
import com.tamu.pan123.data.scrape.ScrapePlan
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExecutionEngineTest {
    private lateinit var fake: FakeDrive

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        AppDirs.init(ctx)
        AppDirs.clearAll()
        TaskStore.clear()
        fake = FakeDrive()
        ExecutionEngine.driveOverride = fake
        ExecutionEngine.OP_SLEEP_MS = 0
        // 缩短等待：直接用默认 200ms，测试动作少
        val plan = ScrapePlan(
            createdAt = "test",
            groups = listOf(PlanGroup("g1", "tv", 1, "测试剧", "2024", folderId = "1", folderName = "测试")),
            actions = listOf(
                PlanAction("m1", "mkdir", "g1", parentId = "1", newName = "Season 1", kind = "folder", season = 1),
                PlanAction("r1", "rename", "g1", fileId = "11", parentId = "1", oldName = "a.mkv", newName = "测.2024.S01E01.mkv", kind = "file"),
                PlanAction("v1", "move", "g1", fileId = "11", parentId = "1", oldName = "测.2024.S01E01.mkv",
                    newName = "测.2024.S01E01.mkv", destLabel = "Season 1", kind = "file"),
                PlanAction("r2", "rename", "g1", fileId = "1", parentId = "0", oldName = "测试", newName = "测试(2024){tmdb-1}", kind = "folder"),
            ),
            stats = PlanStats(rename = 2, mkdir = 1, move = 1, groups = 1),
        )
        PlanStore.save(plan, "scrape_test.json")
    }

    @After
    fun tearDown() {
        ExecutionEngine.driveOverride = null
        TaskStore.clear()
    }

    @Test
    fun runsRenameMkdirMove() = runBlocking {
        val summary = ExecutionEngine.run(PlanStore.load())
        assertEquals(4, summary.ok)
        assertEquals(0, summary.fail)
        assertEquals(1, fake.mkdirs.size)
        assertEquals("Season 1", fake.mkdirs[0].second)
        assertEquals(2, fake.renames.size)
        assertEquals(1, fake.moves.size)
        // move 目标应是 mkdir 返回的 id
        assertEquals(fake.mkdirs.size, 1)
        assertTrue(fake.moves[0].second.toLong() >= 10_000L)
        val run = TaskStore.load()!!
        assertEquals(TaskRun.STATUS_DONE, run.status)
        assertTrue(PlanStore.load().actions.all { it.done })
    }

    @Test
    fun pausesAfterThreeConsecutiveFailures() = runBlocking {
        fake.failNextOps = 3
        val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            while (true) {
                val r = TaskStore.load()
                if (r?.status == TaskRun.STATUS_PAUSED) {
                    ExecutionEngine.stop()
                    break
                }
                kotlinx.coroutines.delay(50)
            }
        }
        val summary = ExecutionEngine.run(PlanStore.load())
        watcher.join()
        assertTrue(summary.fail >= 3)
        assertTrue(summary.stoppedEarly || summary.stoppedOnFailures || summary.fail >= 3)
    }

    @Test
    fun subsetOnly() = runBlocking {
        val summary = ExecutionEngine.run(PlanStore.load(), actionIds = listOf("r1"))
        assertEquals(1, summary.ok)
        assertEquals(1, fake.renames.size)
        assertEquals(0, fake.mkdirs.size)
    }

    /** WebDAV 这类路径型网盘：改名 / 移动后 ID 变化，后续动作要用新路径。 */
    @Test
    fun pathDriveRemapsIdsAfterRenameAndFolderRename() = runBlocking {
        val dav = FakePathDrive(
            initial = setOf("/测试/a.mkv", "/测试/b.mkv"),
            dirs = mutableSetOf("/", "/测试"),
        )
        ExecutionEngine.driveOverride = dav
        val plan = ScrapePlan(
            createdAt = "dav",
            provider = "webdav",
            actions = listOf(
                PlanAction("m1", "mkdir", "g1", parentId = "/测试", newName = "Season 1", kind = "folder"),
                PlanAction("r1", "rename", "g1", fileId = "/测试/a.mkv", parentId = "/测试", oldName = "a.mkv", newName = "测.2024.S01E01.mkv"),
                PlanAction("v1", "move", "g1", fileId = "/测试/a.mkv", parentId = "/测试", destLabel = "Season 1"),
                PlanAction("r2", "rename", "g1", fileId = "/测试", parentId = "/", oldName = "测试", newName = "测试(2024){tmdb-1}", kind = "folder"),
                // 文件夹改名之后才处理的子项：旧路径前缀必须被重映射
                PlanAction("r3", "rename", "g1", fileId = "/测试/b.mkv", parentId = "/测试", oldName = "b.mkv", newName = "测.2024.S01E02.mkv"),
            ),
        )
        PlanStore.save(plan, "scrape_dav.json")
        val summary = ExecutionEngine.run(PlanStore.load())
        assertEquals(summary.failed.toString(), 5, summary.ok)
        assertEquals(
            setOf("/测试(2024){tmdb-1}/Season 1/测.2024.S01E01.mkv", "/测试(2024){tmdb-1}/测.2024.S01E02.mkv"),
            dav.paths,
        )
    }

    /** v0.1 保存的计划（数字 ID）在 v0.2 仍能读取。 */
    @Test
    fun legacyNumericPlanStillLoads() {
        val legacy = """{"version":1,"createdAt":"old","roots":[{"folderId":123,"name":"r"}],
            "actions":[{"id":"a","op":"rename","fileId":456,"parentId":0,"newName":"x"}]}"""
        java.io.File(AppDirs.plans, "scrape_legacy.json").writeText(legacy)
        val p = PlanStore.load()
        assertEquals("456", p.actions.first().fileId)
        assertEquals("0", p.actions.first().parentId)
        assertEquals("p123-main", p.accountId)
    }
}
