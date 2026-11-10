package com.tamu.pan123.webdav

import com.tamu.pan123.data.AppDirs
import com.tamu.pan123.data.SecureStore
import com.tamu.pan123.data.drive.AccountStore
import com.tamu.pan123.data.exec.ExecutionEngine
import com.tamu.pan123.data.exec.TaskStore
import com.tamu.pan123.data.scrape.PlanStore
import com.tamu.pan123.data.scrape.Scanner
import com.tamu.pan123.data.webdav.WebDavDrive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 针对本机 wsgidav 的端到端测试（不碰任何真实网盘账号）。
 * 需要环境变量：WEBDAV_E2E_URL（如 http://127.0.0.1:18080/dav）、WEBDAV_E2E_USER、WEBDAV_E2E_PASS、
 * 可选 WEBDAV_E2E_HTTPS_URL（自签名证书）、WEBDAV_E2E_ROOT（服务器映射的本地目录，用于核对）、TMDB_API_KEY。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class WebDavE2ETest {
    private val url = System.getenv("WEBDAV_E2E_URL").orEmpty()
    private val user = System.getenv("WEBDAV_E2E_USER").orEmpty()
    private val pass = System.getenv("WEBDAV_E2E_PASS").orEmpty()
    private val out = File(System.getenv("WEBDAV_E2E_OUT") ?: "build/webdav-e2e").apply { mkdirs() }

    private fun drive() = WebDavDrive("e2e-dav", url, user, pass)

    @Before
    fun setUp() {
        assumeTrue("WEBDAV_E2E_URL not set", url.isNotBlank())
        val ctx = RuntimeEnvironment.getApplication()
        SecureStore.init(ctx)
        AppDirs.init(ctx)
        AccountStore.init()
        ExecutionEngine.OP_SLEEP_MS = 0
    }

    @Test
    fun a_primitives() = runBlocking {
        val d = drive()
        assertTrue("ping", d.ping())
        assertFalse("bad password must fail", WebDavDrive("x", url, user, "wrong").ping())

        val root = d.list("/").map { it.fileName }
        assertTrue(root.toString(), root.containsAll(listOf("电影", "剧集", "杂项")))
        // 特殊字符：% # { } + 空格 中文
        val misc = d.list("/杂项")
        assertEquals(listOf("100% 测试 #1 {x}+y.txt"), misc.map { it.fileName })
        assertEquals("/杂项/100% 测试 #1 {x}+y.txt", misc.first().fileId)

        // 在临时目录里测 mkdir / upload / rename / move / Overwrite:F
        val tmp = d.mkdir("/", "_ops 临时 (测试)").getOrThrow()
        assertEquals("/_ops 临时 (测试)", tmp)
        assertEquals(tmp, d.mkdir("/", "_ops 临时 (测试)").getOrThrow()) // 已存在复用
        assertEquals("ok", d.upload(tmp, "poster.jpg", byteArrayOf(1, 2, 3)).getOrThrow())
        assertEquals("已存在", d.upload(tmp, "poster.jpg", byteArrayOf(9, 9)).getOrThrow()) // 不覆盖
        assertTrue(d.exists(tmp, "POSTER.JPG")) // 不区分大小写
        val sub = d.mkdir(tmp, "Season 1").getOrThrow()
        val renamed = d.rename("$tmp/poster.jpg", "folder #1.jpg").getOrThrow()
        assertEquals("$tmp/folder #1.jpg", renamed)
        val moved = d.move(renamed, sub).getOrThrow()
        assertEquals("$sub/folder #1.jpg", moved)
        // 再放一个同名文件到 tmp，往 sub 移动时必须失败且不覆盖
        d.upload(tmp, "folder #1.jpg", byteArrayOf(7)).getOrThrow()
        val clash = d.move("$tmp/folder #1.jpg", sub)
        assertTrue("move onto existing must fail", clash.isFailure)
        assertTrue(clash.exceptionOrNull()!!.message!!.contains("已存在"))
        // 文件夹改名（尾斜杠处理）
        val dirRenamed = d.rename(sub, "Season 01").getOrThrow()
        assertEquals("$tmp/Season 01", dirRenamed)
        assertEquals(listOf("folder #1.jpg"), d.list(dirRenamed).map { it.fileName })
        val sizes = d.list(dirRenamed).first().size
        assertEquals(3L, sizes) // 原始 3 字节，未被覆盖
        File(out, "primitives.txt").writeText("ok\n")
    }

    @Test
    fun b_selfSignedHttps() = runBlocking {
        val https = System.getenv("WEBDAV_E2E_HTTPS_URL").orEmpty()
        assumeTrue(https.isNotBlank())
        assertFalse("strict TLS must reject self-signed", WebDavDrive("s", https, user, pass, insecure = false).ping())
        assertTrue("insecure option accepts self-signed", WebDavDrive("s", https, user, pass, insecure = true).ping())
    }

    @Test
    fun c_scanPreviewExecute() = runBlocking {
        val key = System.getenv("TMDB_API_KEY").orEmpty()
        assumeTrue("TMDB_API_KEY not set", key.isNotBlank())
        SecureStore.saveTmdbKey(key)
        AppDirs.clearAll()
        TaskStore.clear()
        val d = drive()

        // 1) 扫描（只读）
        val plan = Scanner.scan(listOf("/电影" to "电影", "/剧集" to "剧集"), fullDrive = false, cloud = d)
        assertEquals("webdav", plan.provider)
        assertEquals("e2e-dav", plan.accountId)
        val previewTxt = buildString {
            appendLine("groups=${plan.groups.size} actions=${plan.actions.size} stats=${plan.stats}")
            plan.groups.forEach { g -> appendLine("[${g.kind}] ${g.title} (${g.year}) tmdb=${g.tmdbId} folder=${g.folderName} -> ${g.folderNewName}") }
            plan.actions.forEach { a -> appendLine("  ${a.op.padEnd(8)} ${a.kind.padEnd(10)} ${a.oldName} -> ${a.newName} ${a.destLabel ?: ""} @${a.parentId}") }
        }
        File(out, "plan_preview.txt").writeText(previewTxt)
        PlanStore.latestFile()?.copyTo(File(out, "plan.json"), overwrite = true)
        assertTrue(previewTxt, plan.groups.any { it.tmdbId == 27205 })   // Inception
        assertTrue(previewTxt, plan.groups.any { it.tmdbId == 603 })     // The Matrix
        assertTrue(previewTxt, plan.groups.any { it.tmdbId == 106841 || it.title.contains("繁花") })
        assertTrue(previewTxt, plan.groups.any { it.tmdbId == 100088 })  // The Last of Us

        // 2) 执行（只针对本机 wsgidav）
        ExecutionEngine.driveOverride = d
        try {
            val summary = ExecutionEngine.run(PlanStore.load())
            File(out, "exec_summary.txt").writeText(
                "ok=${summary.ok} fail=${summary.fail} skipped=${summary.skipped}\n" +
                    summary.failed.joinToString("\n") { "${it.op} ${it.label}: ${it.error}" })
            assertEquals(summary.failed.joinToString("\n") { "${it.label}: ${it.error}" }, 0, summary.fail)
        } finally {
            ExecutionEngine.driveOverride = null
        }

        // 3) 核对：遍历服务器
        val tree = mutableListOf<String>()
        suspend fun walk(p: String) {
            for (e in d.list(p)) {
                tree += e.fileId + if (e.isDir) "/" else ""
                if (e.isDir) walk(e.fileId)
            }
        }
        walk("/")
        File(out, "tree_after.txt").writeText(tree.joinToString("\n"))
        val all = tree.joinToString("\n")
        // 「电影」下散落的单个电影：原地改名（与 v0.1 / Python 行为一致，不自动建文件夹），分类文件夹本身不改名
        assertTrue(all, tree.any { it.startsWith("/电影/盗梦空间.2010.") && it.endsWith(".mkv") })
        assertTrue(all, tree.contains("/电影/"))
        assertTrue(all, tree.contains("/电影/黑客帝国(1999){tmdb-603}/黑客帝国.1999.1080p.BluRay-GROUP.chs.srt"))
        assertTrue(all, tree.any { it.contains("{tmdb-106841}/Season 1/") && it.contains("S01E01") })
        assertTrue(all, tree.any { it.contains("{tmdb-100088}/Season 1/") && it.contains("S01E02") })
        assertTrue(all, tree.any { it.contains("{tmdb-100088}/Season 1/season.nfo") })
        assertTrue(all, tree.any { it.endsWith("{tmdb-106841}/tvshow.nfo") })
        // 杂项未被动过
        assertTrue(all, tree.contains("/杂项/100% 测试 #1 {x}+y.txt"))

        // 4) 再跑一次扫描：应该基本无事可做（幂等）
        val again = Scanner.scan(listOf("/电影" to "电影", "/剧集" to "剧集"), fullDrive = false, cloud = d)
        File(out, "rescan.txt").writeText("stats=${again.stats}\n" +
            again.actions.filter { it.op in setOf("rename", "move", "mkdir", "artwork") }.joinToString("\n") { "${it.op} ${it.oldName} -> ${it.newName} @${it.parentId}" })
        assertEquals(0, again.stats.mkdir + again.stats.move)
    }
}
