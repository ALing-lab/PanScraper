package com.tamu.pan123.v03

import com.tamu.pan123.data.AppDirs
import com.tamu.pan123.data.drive.CloudDrive
import com.tamu.pan123.data.drive.CloudEntry
import com.tamu.pan123.data.drive.DrivePaths
import com.tamu.pan123.data.drive.ProviderKind
import com.tamu.pan123.data.drive.TrashSupport
import com.tamu.pan123.data.exec.ExecutionEngine
import com.tamu.pan123.data.exec.TaskStore
import com.tamu.pan123.data.history.HistoryStore
import com.tamu.pan123.data.naming.Naming
import com.tamu.pan123.data.scrape.PlanAction
import com.tamu.pan123.data.scrape.PlanSelection
import com.tamu.pan123.data.scrape.PlanStore
import com.tamu.pan123.data.scrape.Scanner
import com.tamu.pan123.data.scrape.ScrapePlan
import com.tamu.pan123.data.tmdb.MatchInfo
import com.tamu.pan123.ui.screens.previewOpMatches
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 假 TMDB：按名字里的关键词返回固定条目（绝不联网）。 */
object FakeTmdb : Scanner.Tmdb {
    val shows = linkedMapOf(
        "雀骨" to MatchInfo("tv", 295558, "雀骨", "2026"),
        "quegu" to MatchInfo("tv", 295558, "雀骨", "2026"),
        "鬼灭" to MatchInfo("tv", 85937, "鬼灭之刃", "2019"),
        "奔跑吧" to MatchInfo("tv", 63441, "奔跑吧", "2017"),
    )
    val movies = linkedMapOf(
        "钢铁侠2" to MatchInfo("movie", 10138, "钢铁侠2", "2010"),
        "钢铁侠" to MatchInfo("movie", 1726, "钢铁侠", "2008"),
        "无限列车" to MatchInfo("movie", 635302, "鬼灭之刃剧场版 无限列车篇", "2020"),
        "inception" to MatchInfo("movie", 27205, "盗梦空间", "2010"),
        "盗梦空间" to MatchInfo("movie", 27205, "盗梦空间", "2010"),
        "matrix" to MatchInfo("movie", 603, "黑客帝国", "1999"),
    )
    private fun key(s: String) = s.lowercase().replace(Regex("""[\s._\-]"""), "")
    override suspend fun show(names: List<String>, fixedId: Int?): Pair<MatchInfo?, Int> {
        if (fixedId != null) shows.values.firstOrNull { it.id == fixedId }?.let { return it to -1 }
        names.forEachIndexed { i, n -> shows.entries.firstOrNull { key(n).contains(key(it.key)) }?.let { return it.value to i } }
        return null to -1
    }
    override suspend fun movie(stem: String, fallbackNames: List<String>, fallbackId: Int?): MatchInfo? {
        Naming.scrapedTmdbId(stem)?.let { id -> movies.values.firstOrNull { it.id == id }?.let { return it } }
        for (n in listOf(stem) + fallbackNames) movies.entries.firstOrNull { key(n).contains(key(it.key)) }?.let { return it.value }
        return null
    }
    override suspend fun movieInfo(id: Int) = movies.values.firstOrNull { it.id == id }
    override suspend fun collectionOf(movieId: Int) = if (movieId == 1726 || movieId == 10138) 131292 to "钢铁侠（系列）" else null
}

/** 路径型内存网盘（像 WebDAV），支持删除。trash = null 时模拟不支持删除的网盘。 */
class MemDrive(files: List<String>, dirs: List<String> = emptyList(), private val support: TrashSupport = TrashSupport.RECYCLE,
               override val kind: ProviderKind = ProviderKind.WEBDAV) : CloudDrive {
    override val accountId = "mem"
    override val writeIntervalMs = 0L
    val paths = files.toMutableSet()
    val folders = (dirs + files.flatMap { f -> generateSequence(DrivePaths.parentOf(f)) { p -> if (p == "/") null else DrivePaths.parentOf(p) }.toList() } + "/").toMutableSet()
    val log = mutableListOf<String>()
    private fun all() = paths + folders
    override val trashSupport get() = support
    override val rootId = "/"
    override suspend fun list(folderId: String) = all().filter { it != "/" && DrivePaths.parentOf(it) == folderId }.sorted()
        .map { CloudEntry(it, DrivePaths.nameOf(it), it in folders, parentFileId = folderId) }
    private fun relocate(from: String, to: String) {
        fun mv(set: MutableSet<String>) { val hit = set.filter { it == from || it.startsWith("$from/") }; set.removeAll(hit.toSet()); set.addAll(hit.map { to + it.substring(from.length) }) }
        mv(paths); mv(folders)
    }
    override suspend fun rename(fileId: String, newName: String) = runCatching {
        require(fileId in all()) { "not found $fileId" }; val to = DrivePaths.join(DrivePaths.parentOf(fileId), newName); relocate(fileId, to); log += "rename $fileId -> $newName"; to }
    override suspend fun mkdir(parentId: String, name: String) = runCatching { require(parentId in folders) { "no parent $parentId" }; DrivePaths.join(parentId, name).also { folders += it; log += "mkdir $it" } }
    override suspend fun move(fileId: String, destParentId: String) = runCatching {
        require(fileId in all()) { "not found $fileId" }; require(destParentId in folders) { "no dest $destParentId" }
        val to = DrivePaths.join(destParentId, DrivePaths.nameOf(fileId)); relocate(fileId, to); log += "move $fileId -> $destParentId"; to }
    override suspend fun upload(parentId: String, name: String, data: ByteArray) = runCatching { require(parentId in folders) { "no dir $parentId" }; paths += DrivePaths.join(parentId, name); "ok" }
    override suspend fun trash(fileId: String): Result<String> = runCatching {
        if (support == TrashSupport.NONE) error("403 Forbidden")
        paths.removeAll { it == fileId || it.startsWith("$fileId/") }; folders.removeAll { it == fileId || it.startsWith("$fileId/") }
        log += "trash $fileId"; "ok"
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V03ScanTest {
    @Before fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        AppDirs.init(ctx); AppDirs.clearAll(); TaskStore.clear()
        Scanner.tmdb = FakeTmdb
        HistoryStore.dirOverride = java.io.File(ctx.cacheDir, "hist-test").also { it.deleteRecursively() }
        HistoryStore.reload()
        ExecutionEngine.OP_SLEEP_MS = 0
        ExecutionEngine.artworkOverride = { byteArrayOf(1) }
    }
    @After fun tearDown() {
        Scanner.tmdb = Scanner.RealTmdb
        ExecutionEngine.driveOverride = null
        Naming.simpleNaming = false; Naming.tmdbInFileName = false
    }

    private fun scan(d: CloudDrive, root: String, name: String = DrivePaths.nameOf(root)) =
        runBlocking { Scanner.scan(listOf(root to name), fullDrive = false, cloud = d) }

    private fun ScrapePlan.ops(op: String) = actions.filter { it.op == op }

    /** 需求 #2：资源/Vidoes/剧集/国产/2026/雀骨2026/第一季/新建文件夹/雀骨.S01E01.mkv */
    @Test fun deepPathFindsShowFolderAndFlattensIntoSeason() {
        val d = MemDrive(listOf(
            "/资源/Vidoes/剧集/国产/2026/雀骨2026/第一季/新建文件夹/雀骨.S01E01.mkv",
            "/资源/Vidoes/剧集/国产/2026/雀骨2026/第一季/新建文件夹/雀骨.S01E02.mkv",
            "/资源/Vidoes/剧集/国产/2026/雀骨2026/第一季/新建文件夹/说明.txt",
        ))
        val plan = scan(d, "/资源")
        // 分类目录永远不当片名
        assertTrue(plan.groups.toString(), plan.groups.none { it.folderName in setOf("资源", "Vidoes", "剧集", "国产", "2026") })
        val g = plan.groups.single { it.kind == "tv" }
        assertEquals("雀骨2026", g.folderName)
        assertEquals("雀骨(2026){tmdb-295558}", g.folderNewName)
        val season = plan.ops("rename").single { it.fileId == "/资源/Vidoes/剧集/国产/2026/雀骨2026/第一季" }
        assertEquals("Season 1", season.newName)
        val moves = plan.ops("move").filter { it.kind == "file" }
        assertEquals(2, moves.size)
        assertTrue(moves.all { it.destParentId == "/资源/Vidoes/剧集/国产/2026/雀骨2026/第一季" })
        assertTrue(plan.ops("mkdir").none { it.newName == "Season 1" })
        val del = plan.ops("delete").single()
        assertEquals("第一季/新建文件夹", del.oldName)
        assertFalse(del.selected)
        assertNull(del.blocked)
        assertTrue(del.note!!, del.note!!.contains("仍有其它文件 1 个") && del.note!!.contains("说明.txt"))
    }

    /** 需求 #1：剧集文件夹里只有一个装着视频的普通子文件夹 → 直接改名为 Season 1，不新建、不移动。 */
    @Test fun singleSubfolderIsRenamedToSeason() {
        val d = MemDrive(listOf("/剧集/雀骨2026/新建文件夹/Que.Gu.S01E01.mkv", "/剧集/雀骨2026/新建文件夹/Que.Gu.S01E02.mkv",
            "/剧集/雀骨2026/新建文件夹/Que.Gu.S01E01.chs.srt"))
        val plan = scan(d, "/剧集")
        assertEquals("Season 1", plan.ops("rename").single { it.fileId == "/剧集/雀骨2026/新建文件夹" }.newName)
        assertTrue(plan.ops("mkdir").isEmpty())
        assertTrue(plan.ops("move").isEmpty())
        assertTrue(plan.ops("delete").isEmpty())
        assertTrue(plan.actions.any { it.kind == "subtitle" && it.newName.endsWith(".chs.srt") })
    }

    /** 需求 #1 + 执行：多层嵌套 → 视频移进季文件夹；勾选删除后多余文件夹最后删除。端到端在内存网盘上验证。 */
    @Test fun nestedEndToEndWithDelete() {
        val d = MemDrive(listOf("/剧集/雀骨2026/A/B/Que.Gu.S01E01.mkv", "/剧集/雀骨2026/A/B/Que.Gu.S01E01.zh.srt"))
        var plan = scan(d, "/剧集")
        val del = plan.ops("delete").single()
        assertEquals("A/B", del.oldName)
        assertTrue(del.note!!.contains("空的"))
        plan = PlanSelection.set(plan, listOf(del.id), true)
        PlanStore.save(plan)
        ExecutionEngine.driveOverride = d
        val sum = runBlocking { ExecutionEngine.run(plan, PlanSelection.selectedIds(plan)) }
        assertEquals(d.log.joinToString("\n"), 0, sum.fail)
        val show = "/剧集/雀骨(2026){tmdb-295558}"
        assertTrue(d.paths.toString(), "$show/Season 1/雀骨.2026.S01E01.mkv" in d.paths)
        assertTrue(d.paths.toString(), "$show/Season 1/雀骨.2026.S01E01.zh.srt" in d.paths)
        assertTrue(d.folders.toString(), d.folders.none { it.endsWith("/B") })
        assertTrue("$show/tvshow.nfo" in d.paths)
        // 删除排在最后一步
        assertTrue(d.log.last().startsWith("trash") || d.log.indexOfFirst { it.startsWith("trash") } > d.log.indexOfLast { it.startsWith("move") })
    }

    /** 不支持删除的网盘：删除项标记为不可执行并给出提示；执行时权限错误给出明确说明。 */
    @Test fun deleteBlockedWhenDriveCannotDelete() {
        val d = MemDrive(listOf("/剧集/雀骨2026/第一季/x/Que.Gu.S01E01.mkv"), support = TrashSupport.NONE, kind = ProviderKind.GUANGYA)
        val plan = scan(d, "/剧集")
        val del = plan.ops("delete").single()
        assertNotNull(del.blocked)
        assertTrue(del.blocked!!.contains("手动删除"))
        assertFalse(PlanSelection.executable(del))
        assertTrue(TrashSupport.explain(ProviderKind.P115, "HTTP 403 Forbidden").startsWith("没有删除权限"))
    }

    /** 需求 #3：电影合集 → 合集文件夹改名，每部电影单独文件夹，字幕 / 同名海报 / NFO 跟着走。 */
    @Test fun movieCollectionGetsPerMovieFolders() {
        val d = MemDrive(listOf(
            "/电影/漫威电影系列/钢铁侠系列/钢铁侠1.mkv", "/电影/漫威电影系列/钢铁侠系列/钢铁侠1.chs.srt",
            "/电影/漫威电影系列/钢铁侠系列/钢铁侠1-poster.jpg", "/电影/漫威电影系列/钢铁侠系列/钢铁侠2.mkv",
            "/电影/漫威电影系列/钢铁侠系列/钢铁侠2.nfo",
        ))
        var plan = scan(d, "/电影")
        val col = plan.groups.single { it.kind == "collection" }
        assertEquals("钢铁侠（系列）{tmdb-collection-131292}", col.folderNewName)
        val mk = plan.ops("mkdir").map { it.newName }.toSet()
        assertEquals(setOf("钢铁侠(2008){tmdb-1726}", "钢铁侠2(2010){tmdb-10138}"), mk)
        assertTrue(plan.groups.none { it.folderName == "漫威电影系列" })
        val moved = plan.ops("move").map { it.kind }
        assertEquals(listOf("companion", "companion", "file", "file", "subtitle").sorted(), moved.sorted())
        PlanStore.save(plan)
        ExecutionEngine.driveOverride = d
        val sum = runBlocking { ExecutionEngine.run(plan, PlanSelection.selectedIds(plan)) }
        assertEquals(d.log.joinToString("\n"), 0, sum.fail)
        val base = "/电影/漫威电影系列/钢铁侠（系列）{tmdb-collection-131292}"
        assertTrue(d.paths.toString(), d.paths.any { it.startsWith("$base/钢铁侠(2008){tmdb-1726}/钢铁侠.2008") && it.endsWith(".mkv") })
        assertTrue(d.paths.toString(), d.paths.any { it.startsWith("$base/钢铁侠(2008){tmdb-1726}/") && it.endsWith(".chs.srt") })
        assertTrue(d.paths.toString(), d.paths.any { it.startsWith("$base/钢铁侠(2008){tmdb-1726}/") && it.endsWith("-poster.jpg") })
        assertTrue(d.paths.toString(), "$base/钢铁侠2(2010){tmdb-10138}/movie.nfo" !in d.paths) // 已有同名 nfo，不再补
        assertTrue(d.paths.toString(), "$base/钢铁侠(2008){tmdb-1726}/movie.nfo" in d.paths)
    }

    /** 字幕名比视频名短也能配对（钢铁侠1.chs.srt ↔ 钢铁侠1.2160p.mkv）。 */
    @Test fun shortSubtitleNamePairsWithVideo() {
        val d = MemDrive(listOf("/电影/钢铁侠系列/钢铁侠1.2160p.mkv", "/电影/钢铁侠系列/钢铁侠1.chs.srt", "/电影/钢铁侠系列/钢铁侠2.2160p.mkv"))
        val plan = scan(d, "/电影")
        assertTrue(plan.flags.toString(), plan.flags.none { it.contains("字幕") })
        val sub = plan.actions.single { it.op == "rename" && it.kind == "subtitle" }
        assertEquals("钢铁侠.2008.2160p.chs.srt", sub.newName)
    }

    /** 需求 #4：分类目录里的散落电影 → 每部单独建文件夹并移入（含字幕）。 */
    @Test fun looseMoviesGetOwnFolders() {
        val d = MemDrive(listOf("/电影/Inception.2010.2160p.mkv", "/电影/Inception.2010.2160p.zh.srt", "/电影/The.Matrix.1999.1080p.mkv"))
        val plan = scan(d, "/电影")
        assertEquals(setOf("盗梦空间(2010){tmdb-27205}", "黑客帝国(1999){tmdb-603}"), plan.ops("mkdir").map { it.newName }.toSet())
        val mv = plan.ops("move")
        assertEquals(3, mv.size)
        assertTrue(mv.all { it.destRef != null })
        assertTrue(plan.ops("artwork").any { it.destRef != null && it.newName == "movie.nfo" })
    }

    /** 需求 #5：综艺的加更 / 花絮识别为特别篇 → Specials / S00Exx，正片不受影响。 */
    @Test fun varietySpecialsGoToSeason0() {
        val d = MemDrive(listOf("/综艺/奔跑吧/奔跑吧 第1期.mp4", "/综艺/奔跑吧/奔跑吧 第2期.mp4",
            "/综艺/奔跑吧/奔跑吧 第1期加更.mp4", "/综艺/奔跑吧/奔跑吧 花絮.mp4"))
        val plan = scan(d, "/综艺")
        val renames = plan.ops("rename").filter { it.kind == "file" }.associate { it.oldName to it.newName }
        assertEquals("奔跑吧.2017.S01E01.mp4", renames["奔跑吧 第1期.mp4"])
        assertEquals("奔跑吧.2017.S01E02.mp4", renames["奔跑吧 第2期.mp4"])
        assertTrue(renames.toString(), renames["奔跑吧 第1期加更.mp4"]!!.contains("S00E"))
        assertTrue(renames.toString(), renames["奔跑吧 花絮.mp4"]!!.contains("S00E"))
        assertEquals(setOf("Season 1", "Specials"), plan.ops("mkdir").map { it.newName }.toSet())
    }

    /** 需求 #6：动漫的 OVA → S00；剧场版按电影匹配，在剧集文件夹旁边单独建电影文件夹。 */
    @Test fun animeOvaAndMovie() {
        val d = MemDrive(listOf("/动漫/鬼灭之刃/Season 1/Kimetsu.S01E01.mkv", "/动漫/鬼灭之刃/Season 1/Kimetsu.OVA.01.mkv",
            "/动漫/鬼灭之刃/鬼灭之刃 剧场版 无限列车篇.mkv"))
        val plan = scan(d, "/动漫")
        val ova = plan.ops("rename").single { it.oldName == "Kimetsu.OVA.01.mkv" }
        assertEquals("鬼灭之刃.2019.S00E01.mkv", ova.newName)
        val movieGroup = plan.groups.single { it.tmdbId == 635302 }
        assertEquals("movie", movieGroup.kind)
        val mk = plan.ops("mkdir").single { it.newName.contains("635302") }
        assertEquals("/动漫", mk.parentId)
        assertTrue(plan.ops("move").any { it.oldName.endsWith(".mkv") && it.destRef == mk.id })
    }

    /** 需求 #7：执行成功的项不再出现在预览。 */
    @Test fun doneActionsHiddenFromPreview() {
        val a = PlanAction("x", "rename", "g", "f", "p", "a", "b", done = true)
        val b = PlanAction("y", "rename", "g", "f2", "p", "c", "d")
        assertFalse(previewOpMatches(a, "rename"))
        assertTrue(previewOpMatches(b, "rename"))
        assertEquals(1, Scanner.computeStats(listOf(a, b), emptyList()).rename)
    }

    /** 需求 #8：执行后记入历史；再次扫描跳过（不再匹配 TMDB）；关闭开关后不跳过；可删除。 */
    @Test fun historySkipsExecutedFolders() {
        val d = MemDrive(listOf("/剧集/雀骨2026/Que.Gu.S01E01.mkv"))
        val plan = scan(d, "/剧集")
        PlanStore.save(plan)
        ExecutionEngine.driveOverride = d
        runBlocking { ExecutionEngine.run(plan, PlanSelection.selectedIds(plan)) }
        val rec = HistoryStore.load().single()
        assertEquals("雀骨", rec.title)
        assertEquals("雀骨(2026){tmdb-295558}", rec.groups.single().folderName)
        assertTrue(rec.items.all { it.from.length < 200 })
        // 再扫：记住的文件夹被跳过，TMDB 不再被调用
        var calls = 0
        Scanner.tmdb = object : Scanner.Tmdb by FakeTmdb {
            override suspend fun show(names: List<String>, fixedId: Int?): Pair<MatchInfo?, Int> { calls++; return FakeTmdb.show(names, fixedId) }
        }
        val again = scan(d, "/剧集")
        assertEquals(0, calls)
        assertEquals(1, again.stats.history)
        assertEquals("历史", again.groups.single().confidence)
        assertTrue("已完整整理的目录无需重复写入", again.actions.none { PlanSelection.executable(it) && it.kind != "cleanup" && it.op != "delete" })
        // 新增一集：沿用记住的条目，只处理新文件，仍不调 TMDB
        d.paths += "/剧集/雀骨(2026){tmdb-295558}/Season 1/Que.Gu.S01E02.mkv"
        val third = scan(d, "/剧集")
        assertEquals(0, calls)
        assertTrue(third.ops("rename").any { it.newName == "雀骨.2026.S01E02.mkv" })
        assertEquals("历史", third.groups.single().confidence)
        // 关闭开关 → 不再跳过
        HistoryStore.setEnabled(false)
        scan(d, "/剧集")
        assertTrue(calls > 0)
        HistoryStore.setEnabled(true)
        HistoryStore.delete(rec.id)
        assertTrue(HistoryStore.load().isEmpty())
    }

    /** #17：播放器兼容命名（简洁命名的子开关）：字幕跟着同名；已有 ID 的文件名可作为强匹配线索。 */
    @Test fun tmdbSuffixFollowsSubtitles() {
        Naming.simpleNaming = true; Naming.tmdbInFileName = true
        val d = MemDrive(listOf("/剧集/某剧/x.S01E02.2160p{tmdb-295558}.mp4", "/剧集/某剧/x.S01E02.2160p{tmdb-295558}.zh.srt"))
        val plan = scan(d, "/剧集")
        val g = plan.groups.single()
        assertEquals(295558, g.tmdbId) // 名字里没有「雀骨」，靠文件名里的 ID 匹配
        val names = plan.actions.filter { it.op == "rename" && it.kind != "folder" }.map { it.newName }.toSet()
        assertTrue(names.toString(), "雀骨.2026.S01E02{tmdb-295558}.mp4" in names)
        assertTrue(names.toString(), "雀骨.2026.S01E02{tmdb-295558}.zh.srt" in names)
    }

    /** 删除前安全检查：文件夹里还有视频就不删。 */
    @Test fun deleteRefusesWhenVideosRemain() {
        val d = MemDrive(listOf("/x/keep.mkv"))
        val plan = ScrapePlan(createdAt = "t", actions = listOf(PlanAction("d1", "delete", "g", "/x", "/", "x", kind = "leftover")))
        PlanStore.save(plan)
        ExecutionEngine.driveOverride = d
        val sum = runBlocking { ExecutionEngine.run(plan, listOf("d1")) }
        assertEquals(1, sum.fail)
        assertTrue("/x/keep.mkv" in d.paths)
        assertTrue(sum.failed.single().error.contains("视频"))
    }
}
