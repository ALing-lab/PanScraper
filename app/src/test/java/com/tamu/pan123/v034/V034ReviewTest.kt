package com.tamu.pan123.v034

import android.app.Service
import android.content.Context
import com.tamu.pan123.data.AppDirs
import com.tamu.pan123.data.SecureStore
import com.tamu.pan123.data.drive.CloudEntry
import com.tamu.pan123.data.exec.ExecutionEngine
import com.tamu.pan123.data.exec.TaskRun
import com.tamu.pan123.data.exec.TaskStore
import com.tamu.pan123.data.scrape.PlanAction
import com.tamu.pan123.data.scrape.PlanGroup
import com.tamu.pan123.data.scrape.PlanStore
import com.tamu.pan123.data.scrape.ScrapePlan
import com.tamu.pan123.data.update.UpdateChecker
import com.tamu.pan123.exec.FakeDrive
import com.tamu.pan123.service.ExecForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** v0.3.4 代码审查修复的回归测试（全部用假网盘 / 本地文件，不联网、不碰真实账号）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V034ReviewTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        AppDirs.init(app); AppDirs.clearAll(); TaskStore.clear()
        ExecutionEngine.OP_SLEEP_MS = 0
    }
    @After fun tearDown() {
        ExecutionEngine.driveOverride = null
        TaskStore.clear()
        // SecureStore 恢复成测试默认配置
        SecureStore.simulateKeystoreFailure = false
        SecureStore.testPlainPrefs = true
        SecureStore.resetForTest(); SecureStore.init(app)
    }

    // ---------------- M2：没有明文回退 ----------------

    @Test fun secureStoreUnavailableKeepsSecretsInMemoryOnlyAndWipesLegacyPlaintext() {
        val legacy = app.getSharedPreferences("secure_v1_fallback", Context.MODE_PRIVATE)
        legacy.edit().putString("tmdb_key", "legacy-key-123").putString("sec.acct.x", "{\"password\":\"p\"}").commit()
        SecureStore.resetForTest()
        SecureStore.testPlainPrefs = false
        SecureStore.simulateKeystoreFailure = true
        SecureStore.init(app)
        assertFalse(SecureStore.available.value)
        // 旧明文内容读进内存后，磁盘上的明文文件被清空
        assertEquals("legacy-key-123", SecureStore.tmdbKey.value)
        assertTrue(app.getSharedPreferences("secure_v1_fallback", Context.MODE_PRIVATE).all.isEmpty())
        // 新保存的登录信息只在内存里
        SecureStore.putSecret("acct.y", "secret-value")
        assertEquals("secret-value", SecureStore.getSecret("acct.y"))
        val prefsDir = File(app.applicationInfo.dataDir, "shared_prefs")
        val onDisk = prefsDir.listFiles().orEmpty().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse("明文不应写到磁盘", onDisk.contains("secret-value") || onDisk.contains("legacy-key-123"))
        // 「进程重启」后内存里的东西就没了，也不会从明文文件恢复
        SecureStore.resetForTest(); SecureStore.init(app)
        assertNull(SecureStore.getSecret("acct.y"))
        assertEquals("", SecureStore.tmdbKey.value)
        assertTrue(SecureStore.UNAVAILABLE_MESSAGE.contains("安全存储不可用"))
    }

    @Test fun credentialToStringIsRedacted() {
        val s = SecureStore.Session("eyJhbGciOiJIUzI1NiJ9.abcdefghijklmnop.qrstuvwxyz", 0, "13812345678").toString()
        assertFalse(s.contains("eyJ")); assertFalse(s.contains("13812345678"))
        val g = com.tamu.pan123.data.guangya.GyCreds("ACCESS-TOKEN-XYZ", "REFRESH-XYZ").toString()
        assertFalse(g.contains("ACCESS-TOKEN-XYZ") || g.contains("REFRESH-XYZ"))
        val p = com.tamu.pan123.data.p115.P115Creds("UID-1", "CID-SECRET", "SEID-SECRET").toString()
        assertFalse(p.contains("CID-SECRET") || p.contains("SEID-SECRET") || p.contains("UID-1"))
    }

    // ---------------- N4：服务被系统重启时不会重跑计划 ----------------

    private fun runOf(status: String, stopped: Boolean = false) =
        TaskRun("t1", "plan", listOf("a"), status = status, userStopped = stopped)

    @Test fun restartDecision() {
        assertNull(ExecForegroundService.restartDecision(null))
        assertNull(ExecForegroundService.restartDecision(runOf(TaskRun.STATUS_DONE)))
        assertNull(ExecForegroundService.restartDecision(runOf(TaskRun.STATUS_STOPPING)))
        assertNull(ExecForegroundService.restartDecision(runOf(TaskRun.STATUS_RUNNING, stopped = true)))
        assertNull(ExecForegroundService.restartDecision(runOf(TaskRun.STATUS_PAUSED, stopped = true)))
        assertEquals(ExecForegroundService.Restart.RESUME_RUNNING, ExecForegroundService.restartDecision(runOf(TaskRun.STATUS_RUNNING)))
        assertEquals(ExecForegroundService.Restart.RESUME_PAUSED, ExecForegroundService.restartDecision(runOf(TaskRun.STATUS_PAUSED)))
    }

    @Test fun nullIntentWithoutUnfinishedTaskDoesNothing() {
        val fake = FakeDrive(); ExecutionEngine.driveOverride = fake
        PlanStore.save(ScrapePlan(createdAt = "t", groups = listOf(PlanGroup("g", "tv", 1, "x", "2024", folderId = "1", folderName = "x")),
            actions = listOf(PlanAction("r1", "rename", "g", fileId = "11", parentId = "1", oldName = "a.mkv", newName = "b.mkv", kind = "file"))), "scrape_t.json")
        TaskStore.save(TaskRun("old", "plan", listOf("r1"), status = TaskRun.STATUS_DONE))
        val svc = Robolectric.buildService(ExecForegroundService::class.java).create().get()
        val ret = svc.onStartCommand(null, 0, 1)
        assertEquals(Service.START_NOT_STICKY, ret)
        Thread.sleep(300)
        assertTrue("不应执行任何改名", fake.renames.isEmpty())
        assertEquals(TaskRun.STATUS_DONE, TaskStore.load()!!.status)
    }

    @Test fun stopFlagIsPersisted() {
        TaskStore.save(TaskRun("t", "plan", listOf("a"), status = TaskRun.STATUS_RUNNING))
        ExecutionEngine.reportLive(TaskStore.load())
        ExecutionEngine.stop()
        assertTrue(TaskStore.load()!!.userStopped)
        assertNull(ExecForegroundService.restartDecision(TaskStore.load()))
    }

    // ---------------- M5：删除前的递归检查有上限 ----------------

    /** 一棵很深的目录树：每层一个子文件夹，最底下有一个视频。 */
    private class DeepDrive(val depth: Int, val wide: Int = 1) : com.tamu.pan123.data.drive.CloudDrive by FakeDrive() {
        var lists = 0
        override val trashSupport = com.tamu.pan123.data.drive.TrashSupport.RECYCLE
        val trashed = mutableListOf<String>()
        override suspend fun trash(fileId: String): Result<String> { trashed += fileId; return Result.success("ok") }
        override suspend fun list(folderId: String): List<CloudEntry> {
            lists++
            val level = folderId.count { it == '/' }
            if (level >= depth) return listOf(CloudEntry("$folderId/v.mkv", "v.mkv", false))
            return (0 until wide).map { CloudEntry("$folderId/d$it", "d$it", true) }
        }
    }

    @Test fun deleteGuardCountsRecursivelyAndStopsAtLimit() = runBlocking {
        val deep = DeepDrive(depth = 5)
        val r = ExecutionEngine.countVideosBounded(deep, "root")
        assertTrue(r.complete); assertEquals(1, r.videos) // 第 5 层的视频也能发现（旧逻辑只看两层）
        val tooDeep = ExecutionEngine.countVideosBounded(DeepDrive(depth = 50), "root", maxDepth = 8)
        assertFalse(tooDeep.complete)
        val tooWide = ExecutionEngine.countVideosBounded(DeepDrive(depth = 3, wide = 30), "root", maxEntries = 500)
        assertFalse(tooWide.complete)
    }

    private fun deletePlan() = ScrapePlan(createdAt = "t",
        groups = listOf(PlanGroup("g", "tv", 1, "x", "2024", folderId = "1", folderName = "x")),
        actions = listOf(PlanAction("d1", "delete", "g", fileId = "root", parentId = "1", oldName = "old", kind = "cleanup")))

    @Test fun deleteRefusedWhenTreeTooBigOrHasDeepVideo() = runBlocking {
        val huge = DeepDrive(depth = 50)
        ExecutionEngine.driveOverride = huge
        PlanStore.save(deletePlan(), "scrape_d.json")
        val s1 = ExecutionEngine.run(PlanStore.load(), listOf("d1"))
        assertEquals(1, s1.fail)
        assertEquals(ExecutionEngine.DELETE_TOO_BIG, s1.failed.single().error)
        assertTrue(huge.trashed.isEmpty())

        val deep = DeepDrive(depth = 4)
        ExecutionEngine.driveOverride = deep
        PlanStore.save(deletePlan(), "scrape_d.json")
        val s2 = ExecutionEngine.run(PlanStore.load(), listOf("d1"))
        assertEquals(1, s2.fail)
        assertTrue(s2.failed.single().error.contains("还有 1 个视频"))
        assertTrue(deep.trashed.isEmpty())
    }

    // ---------------- N6：取消要往上传 ----------------

    @Test fun cancellationIsNotSwallowedAsFailure() = runBlocking {
        val drive = object : com.tamu.pan123.data.drive.CloudDrive by FakeDrive() {
            override suspend fun rename(fileId: String, newName: String): Result<String> = throw CancellationException("用户结束")
        }
        ExecutionEngine.driveOverride = drive
        PlanStore.save(ScrapePlan(createdAt = "t", groups = listOf(PlanGroup("g", "tv", 1, "x", "2024", folderId = "1", folderName = "x")),
            actions = listOf(PlanAction("r1", "rename", "g", fileId = "11", parentId = "1", oldName = "a.mkv", newName = "b.mkv", kind = "file"))), "scrape_c.json")
        try { ExecutionEngine.run(PlanStore.load(), listOf("r1")); fail("应抛出 CancellationException") } catch (_: CancellationException) {}
        // 没有被记成一次失败
        assertFalse(PlanStore.load().actions.single().error != null)
    }

    // ---------------- L10：执行时检查文件名 ----------------

    @Test fun execNameRejectsPathTricks() = runBlocking {
        val fake = FakeDrive(); ExecutionEngine.driveOverride = fake
        PlanStore.save(ScrapePlan(createdAt = "t", groups = listOf(PlanGroup("g", "tv", 1, "x", "2024", folderId = "1", folderName = "x")),
            actions = listOf(PlanAction("r1", "rename", "g", fileId = "11", parentId = "1", oldName = "a.mkv", newName = "../../evil.mkv", kind = "file"))), "scrape_n.json")
        val s = ExecutionEngine.run(PlanStore.load(), listOf("r1"))
        assertEquals(1, s.fail)
        assertTrue(fake.renames.isEmpty())
    }

    // ---------------- N2：WebDAV 局域网 http ----------------

    @Test fun manifestUsesNetworkSecurityConfigAllowingLanCleartext() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""android:networkSecurityConfig="@xml/network_security_config""""))
        assertFalse(manifest.contains("usesCleartextTraffic=\"false\""))
        val nsc = File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue(nsc.contains("""<base-config cleartextTrafficPermitted="true">"""))
        val strict = nsc.substringAfter("""<domain-config cleartextTrafficPermitted="false">""").substringBefore("</domain-config>")
        for (d in listOf("123pan.com", "115.com", "guangyapan.com", "themoviedb.org", "tmdb.org", "github.com")) assertTrue(d, strict.contains(">$d<"))
        // 资源确实打包进了 App
        assertTrue(app.resources.getIdentifier("network_security_config", "xml", app.packageName) != 0)
        assertTrue(com.tamu.pan123.data.webdav.WebDavAddress.HELP.contains("明文"))
    }

    // ---------------- H3：不是 APK / 签名不对的安装包不会拉起安装 ----------------

    @Test fun verifyBeforeInstallRejectsGarbageAndDeletesIt() {
        val f = File(app.cacheDir, "fake.apk").apply { writeText("<html>not an apk</html>") }
        try { UpdateChecker.verifyBeforeInstall(app, f); fail() } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("已"))
        }
        assertFalse(f.exists())
    }
}
