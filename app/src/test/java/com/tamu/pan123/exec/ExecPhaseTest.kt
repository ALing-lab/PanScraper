package com.tamu.pan123.exec

import com.tamu.pan123.data.AppDirs
import com.tamu.pan123.data.drive.ProviderKind
import com.tamu.pan123.data.exec.ExecutionEngine
import com.tamu.pan123.data.exec.TaskStore
import com.tamu.pan123.data.scrape.PlanAction
import com.tamu.pan123.data.scrape.PlanGroup
import com.tamu.pan123.data.scrape.PlanStore
import com.tamu.pan123.data.scrape.ScrapePlan
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

/** 光鸭真机：海报第一次上传失败、「再次执行」才成功 → 先结构操作后上传，并自动重试上传。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExecPhaseTest {
    private val log = mutableListOf<String>()
    private var uploadFailures = 0

    private val drive = object : com.tamu.pan123.data.drive.CloudDrive {
        override val kind = ProviderKind.GUANGYA
        override val accountId = "gy"
        override val writeIntervalMs = 0L
        override suspend fun list(folderId: String) = emptyList<com.tamu.pan123.data.drive.CloudEntry>()
        override suspend fun rename(fileId: String, newName: String): Result<String> { log += "rename $newName"; return Result.success(fileId) }
        override suspend fun mkdir(parentId: String, name: String): Result<String> { log += "mkdir $name"; return Result.success("D") }
        override suspend fun move(fileId: String, destParentId: String): Result<String> { log += "move $fileId"; return Result.success(fileId) }
        override suspend fun upload(parentId: String, name: String, data: ByteArray): Result<String> {
            if (uploadFailures > 0) { uploadFailures--; log += "upload-fail $name"; return Result.failure(java.io.IOException("光鸭存储上传失败：NoSuchUpload")) }
            log += "upload $name"; return Result.success("ok")
        }
    }

    @Before fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        AppDirs.init(ctx); AppDirs.clearAll(); TaskStore.clear()
        ExecutionEngine.driveOverride = drive
        ExecutionEngine.OP_SLEEP_MS = 0
        ExecutionEngine.artworkOverride = { byteArrayOf(1, 2, 3) }
        PlanStore.save(ScrapePlan(
            createdAt = "t",
            groups = listOf(PlanGroup("g", "movie", 1, "片", "2020", folderId = "F", folderName = "片")),
            actions = listOf(
                PlanAction("a1", "artwork", "g", parentId = "F", newName = "poster.jpg", kind = "image", artworkUrl = "x"),
                PlanAction("r1", "rename", "g", "F", "0", "片", "片(2020){tmdb-1}", kind = "folder"),
                PlanAction("a2", "artwork", "g", parentId = "F", newName = "movie.nfo", kind = "movie_nfo"),
                PlanAction("r2", "rename", "g", "v", "F", "a.mkv", "片.2020.mkv", kind = "file"),
            ),
        ), "scrape_phase.json")
    }

    @After fun tearDown() { ExecutionEngine.driveOverride = null; ExecutionEngine.artworkOverride = null; TaskStore.clear() }

    @Test fun structureBeforeUploadsAndUploadsRetry() = runBlocking {
        uploadFailures = 2
        val r = ExecutionEngine.run(PlanStore.load(), listOf("a1", "r1", "a2", "r2"))
        assertEquals(4, r.ok)
        assertEquals(0, r.fail)
        val firstUpload = log.indexOfFirst { it.startsWith("upload") }
        assertTrue(log.toString(), log.indexOf("rename 片(2020){tmdb-1}") < firstUpload && log.indexOf("rename 片.2020.mkv") < firstUpload)
        assertEquals(2, log.count { it.startsWith("upload-fail") })
    }

    @Test fun persistentUploadFailureRecordsReason() = runBlocking {
        uploadFailures = 100
        val r = ExecutionEngine.run(PlanStore.load(), listOf("r1", "a1"))
        assertEquals(1, r.fail)
        val plan = PlanStore.load()
        assertTrue(plan.actions.first { it.id == "a1" }.error!!.contains("NoSuchUpload"))
        assertTrue(TaskStore.load()!!.message.contains("失败原因"))
        assertEquals(1 + ExecutionEngine.UPLOAD_RETRY_MS.size, log.count { it.startsWith("upload-fail") })
    }

    @Test fun staleIdsReportedNotSilent() = runBlocking {
        ExecutionEngine.run(PlanStore.load(), listOf("gone1", "gone2"))
        assertTrue(TaskStore.load()!!.message.contains("计划已变化"))
    }

    /** 用户要求的严格顺序：① 全部改名（文件夹先）→ ② 新建 → ③ 移动 → ④ 上传；逐项记录，失败项可单独重试。 */
    @Test fun strictFourPhaseOrderAndItemRecords() = runBlocking {
        PlanStore.save(ScrapePlan(
            createdAt = "t",
            groups = listOf(PlanGroup("g", "tv", 1, "雀骨", "2026", folderId = "F", folderName = "雀骨2026")),
            actions = listOf(
                PlanAction("art", "artwork", "g", parentId = "F", newName = "poster.jpg", kind = "image", artworkUrl = "x"),
                PlanAction("mv", "move", "g", "e1", "F", "雀骨.S01E01.mkv", "雀骨.S01E01.mkv", destLabel = "Season 1", kind = "file"),
                PlanAction("mk", "mkdir", "g", parentId = "F", newName = "Season 1", kind = "folder"),
                PlanAction("rf", "rename", "g", "e1", "F", "a.mkv", "雀骨.S01E01.mkv", kind = "file"),
                PlanAction("rd", "rename", "g", "F", "0", "雀骨2026", "雀骨(2026){tmdb-1}", kind = "folder"),
                PlanAction("nfo", "artwork", "g", parentId = "F", newName = "tvshow.nfo", kind = "tvshow_nfo"),
            ),
        ), "scrape_phase.json")
        uploadFailures = 4 // poster：1 次 + 3 次重试全部失败
        val ids = listOf("art", "mv", "mk", "rf", "rd", "nfo")
        ExecutionEngine.run(PlanStore.load(), ids)
        assertEquals(listOf("rename 雀骨(2026){tmdb-1}", "rename 雀骨.S01E01.mkv", "mkdir Season 1", "move e1"),
            log.filterNot { it.startsWith("upload") })
        assertTrue(log.toString(), log.indexOf("move e1") < log.indexOfFirst { it.startsWith("upload") })
        val run = TaskStore.load()!!
        assertEquals(listOf("rd", "rf", "mk", "mv", "art", "nfo"), run.items.map { it.id })
        assertEquals(listOf(1, 1, 2, 3, 4, 4), run.items.map { it.phase })
        assertEquals(5, run.ok); assertEquals(1, run.fail)
        val bad = run.items.first { it.id == "art" }
        assertEquals(com.tamu.pan123.data.exec.TaskItem.FAIL, bad.status)
        assertTrue(bad.error!!.contains("NoSuchUpload"))
        assertEquals("雀骨", bad.group)
        assertTrue(run.items.all { it.at != null })
        assertEquals(0, run.phase)
        // 单项重试：沿用原列表，只有这一项被重新执行
        log.clear(); uploadFailures = 0
        ExecutionEngine.run(PlanStore.load(), listOf("art"))
        val again = TaskStore.load()!!
        assertEquals(6, again.items.size)
        assertEquals(6, again.ok); assertEquals(0, again.fail)
        assertEquals(listOf("upload poster.jpg"), log)
        assertEquals(2, again.items.first { it.id == "art" }.attempts)
    }

    @Test fun phaseLabels() {
        assertEquals("第 1/5 步：重命名", com.tamu.pan123.data.exec.ExecPhases.label(1))
        assertEquals("第 4/5 步：上传海报和 NFO", com.tamu.pan123.data.exec.ExecPhases.label(4))
        assertEquals("第 5/5 步：删除多余文件夹", com.tamu.pan123.data.exec.ExecPhases.label(5))
    }

    @Test fun failedRetrySelectionPullsInFailedMkdir() {
        val plan = ScrapePlan(actions = listOf(
            PlanAction("mk", "mkdir", "g", parentId = "F", newName = "Season 1", kind = "folder"),
            PlanAction("mv", "move", "g", "e1", "F", "a", "a", destLabel = "Season 1", kind = "file"),
            PlanAction("up", "artwork", "g", parentId = "F", newName = "poster.jpg", kind = "image"),
        ))
        val failed = setOf("mk", "mv", "up")
        val R = com.tamu.pan123.data.exec.FailedRetry
        assertEquals(setOf("mv", "mk"), R.toggle(emptySet(), listOf("mv"), true, failed, plan))
        assertEquals(setOf("up"), R.toggle(failed, listOf("mk"), false, failed, plan))
        // mkdir 不在失败列表（例如之前没勾选）但还没完成：重试移动时也要带上
        assertEquals(listOf("mk", "mv"), R.retryIds(listOf("mv"), plan))
        val done = plan.copy(actions = plan.actions.map { if (it.id == "mk") it.copy(done = true) else it })
        assertEquals(listOf("mv"), R.retryIds(listOf("mv"), done))
    }

    /** 「再次执行未完成项」：暂停 / 结束时没执行到的项 + 勾选的失败项；已成功的不重做、仍算成功。 */
    @Test fun rerunKeepsDoneAndRunsPending() = runBlocking {
        uploadFailures = 100
        ExecutionEngine.run(PlanStore.load(), listOf("a1", "r1", "a2", "r2"))
        val first = TaskStore.load()!!
        assertEquals(2, first.ok); assertEquals(2, first.fail)
        uploadFailures = 0; log.clear()
        // 只勾选 a2 重做（a1 不勾），同时把全部 id 传进来模拟「继续」：r1 / r2 已完成不重做
        ExecutionEngine.run(PlanStore.load(), listOf("r1", "r2", "a2"))
        val again = TaskStore.load()!!
        assertEquals(listOf("upload movie.nfo"), log)
        assertEquals(3, again.ok); assertEquals(1, again.fail); assertEquals(0, again.skipped)
        assertEquals(4, again.items.size)
    }
}
