package com.tamu.pan123.scrape

import com.tamu.pan123.data.drive.CloudDrive
import com.tamu.pan123.data.drive.CloudEntry
import com.tamu.pan123.data.drive.ProviderKind
import com.tamu.pan123.data.scrape.PlanAction
import com.tamu.pan123.data.scrape.PlanGroup
import com.tamu.pan123.data.scrape.PlanSelection
import com.tamu.pan123.data.scrape.Scanner
import com.tamu.pan123.data.scrape.ScrapePlan
import com.tamu.pan123.data.tmdb.MatchInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只读的内存网盘：按父 ID 列目录。 */
class TreeDrive(private val tree: Map<String, List<CloudEntry>>) : CloudDrive {
    override val kind = ProviderKind.GUANGYA
    override val accountId = "tree"
    override suspend fun list(folderId: String) = tree[folderId].orEmpty()
    override suspend fun rename(fileId: String, newName: String) = Result.success(fileId)
    override suspend fun mkdir(parentId: String, name: String) = Result.success("new")
    override suspend fun move(fileId: String, destParentId: String) = Result.success(fileId)
    override suspend fun upload(parentId: String, name: String, data: ByteArray) = Result.success("ok")
}

class ReplanTest {
    private fun f(id: String, name: String, parent: String) = CloudEntry(id, name, false, 10, parent)
    private fun d(id: String, name: String, parent: String) = CloudEntry(id, name, true, 0, parent)

    /** 设备上的情况：「雀骨2026」TMDB 未匹配，手动改成剧集 295558 后只剩改名，没有文件夹改名 / Season / 移动 / 海报。 */
    @Test fun rematchUnmatchedTvRegeneratesFullOpSet() = runBlocking {
        val drive = TreeDrive(mapOf(
            "F" to listOf(
                f("e1", "Que.Gu.2026.S01E01.2160p.WEB-DL.mkv", "F"),
                f("e2", "Que.Gu.2026.S01E02.2160p.WEB-DL.mkv", "F"),
                f("s1", "Que.Gu.2026.S01E01.2160p.WEB-DL.chs.srt", "F"),
            ),
        ))
        val plan = ScrapePlan(
            groups = listOf(
                PlanGroup("unmatched-F", "unmatched", title = "雀骨2026", folderId = "F", folderName = "雀骨2026", itemIds = listOf("u1", "u2")),
                PlanGroup("other", "movie", 1, "别的", folderId = "O", folderName = "别的"),
            ),
            actions = listOf(
                PlanAction("u1", "unmatched", "unmatched-F", "e1", "F", "Que.Gu.2026.S01E01.2160p.WEB-DL.mkv", kind = "file"),
                PlanAction("u2", "unmatched", "unmatched-F", "e2", "F", "Que.Gu.2026.S01E02.2160p.WEB-DL.mkv", kind = "file"),
                PlanAction("o1", "rename", "other", "x", "O", "a.mkv", "b.mkv", kind = "file"),
            ),
            flags = listOf("剧集「雀骨2026」TMDB 未匹配，整个文件夹未改名", "别的提示"),
        )
        val info = MatchInfo("tv", 295558, "雀骨", "2026", "/p.jpg", "")
        val next = Scanner.replanGroup(plan, "unmatched-F", info, drive)
        val g = next.groups.first { it.folderId == "F" }
        val acts = next.actions.filter { it.groupId == g.id }
        assertEquals("tv", g.kind)
        assertEquals("雀骨(2026){tmdb-295558}", g.folderNewName)
        assertTrue(acts.any { it.op == "rename" && it.kind == "folder" && it.newName == "雀骨(2026){tmdb-295558}" })
        assertTrue(acts.any { it.op == "mkdir" && it.newName == "Season 1" })
        assertEquals(2, acts.count { it.op == "move" && it.kind == "file" && it.destLabel == "Season 1" })
        assertEquals(1, acts.count { it.op == "move" && it.kind == "subtitle" && it.destLabel == "Season 1" }) // v0.3：字幕跟随视频
        assertEquals(2, acts.count { it.op == "rename" && it.kind == "file" && it.newName.contains("S01E0") })
        assertTrue(acts.any { it.op == "artwork" && it.newName == "tvshow.nfo" })
        assertTrue(acts.any { it.op == "artwork" && it.newName == "poster.jpg" })
        assertTrue(acts.none { it.op == "unmatched" })
        assertTrue(acts.none { it.kind == "cleanup" })
        // 其它分组不受影响；提示被移除；计数重算
        assertTrue(next.actions.any { it.id == "o1" })
        assertEquals(listOf("别的提示"), next.flags)
        assertEquals(0, next.stats.unmatched)
        assertTrue(next.stats.mkdir >= 1 && next.stats.move == 3 && next.stats.artwork >= 2)
        assertEquals("手动", g.confidence)
    }

    /** 设备上的情况：盗梦空间2010 被错配成剧集并已执行；手动改成电影后重新规划并给出可选清理。 */
    @Test fun rematchExecutedTvFolderToMovieOffersCleanup() = runBlocking {
        val folderName = "WalangHanggangPaalam(2020){tmdb-105640}"
        val drive = TreeDrive(mapOf(
            "F" to listOf(
                d("S1", "Season 1", "F"),
                f("nfo", "tvshow.nfo", "F"),
                f("pos", "poster.jpg", "F"),
                f("fan", "fanart.jpg", "F"),
                f("sp", "season01-poster.jpg", "F"),
                f("keep", "readme.txt", "F"),
            ),
            "S1" to listOf(
                f("v", "Walang.Hanggang.Paalam.2020.S01E20.2160p.WEB-DL.DV.H265.mkv", "S1"),
                f("snfo", "season.nfo", "S1"),
            ),
        ))
        val plan = ScrapePlan(
            groups = listOf(PlanGroup("tv-105640-F", "tv", 105640, "Walang Hanggang Paalam", "2020", folderId = "F", folderName = folderName)),
            actions = listOf(
                PlanAction("old1", "mkdir", "tv-105640-F", parentId = "F", newName = "Season 1", kind = "folder"),
                PlanAction("old2", "artwork", "tv-105640-F", parentId = "F", newName = "tvshow.nfo", kind = "tvshow_nfo"),
            ),
            flags = listOf("「$folderName」随便一条"),
        )
        val info = MatchInfo("movie", 27205, "盗梦空间", "2010", "/inception.jpg", "")
        val next = Scanner.replanGroup(plan, "tv-105640-F", info, drive)
        val g = next.groups.single()
        val acts = next.actions.filter { it.groupId == g.id }
        assertEquals("movie", g.kind)
        assertTrue(acts.none { it.id == "old1" || it.id == "old2" })
        // 主操作：文件夹改名 + 视频按电影改名 + 电影海报 / NFO；没有 Season / tvshow 产物
        assertTrue(acts.any { it.op == "rename" && it.kind == "folder" && it.newName == "盗梦空间(2010){tmdb-27205}" })
        val vr = acts.single { it.op == "rename" && it.fileId == "v" }
        assertTrue(vr.newName, vr.newName.startsWith("盗梦空间.2010") && !vr.newName.contains("S01E20"))
        val main = acts.filter { it.kind != "cleanup" }
        assertTrue(main.any { it.op == "artwork" && it.newName == "poster.jpg" })
        assertTrue(main.any { it.op == "artwork" && it.newName == "fanart.jpg" })
        assertTrue(main.none { it.op == "mkdir" || it.newName.contains("tvshow") || it.newName.startsWith("season") })
        // 清理：默认不勾选，只认应用生成的文件，不动 readme.txt
        val clean = acts.filter { it.kind == "cleanup" }
        assertTrue(clean.isNotEmpty() && clean.none { it.selected })
        val cleaned = clean.filter { it.op == "move" }.mapNotNull { it.fileId }.toSet()
        assertEquals(setOf("nfo", "pos", "fan", "sp", "v", "S1"), cleaned)
        assertFalse("keep" in cleaned)
        assertTrue(clean.any { it.op == "mkdir" && it.newName == Scanner.TRASH_DIR })
        // 视频移回上层的操作排在把 Season 1 收进回收目录之前
        assertTrue(clean.indexOfFirst { it.fileId == "v" } < clean.indexOfFirst { it.fileId == "S1" })
        assertTrue(next.flags.none { it.contains("随便一条") })
        assertTrue(next.stats.cleanup >= 6)
        // 勾选一个清理移动会自动带上回收目录的新建
        val sel = PlanSelection.set(next, listOf(clean.first { it.fileId == "nfo" }.id), true)
        val ids = PlanSelection.selectedIds(sel)
        assertTrue(ids.contains(clean.first { it.op == "mkdir" }.id))
    }

    @Test fun folderTitleMismatchHeuristic() {
        assertTrue(Scanner.folderTitleMismatch("盗梦空间2010", "Walang Hanggang Paalam"))
        assertFalse(Scanner.folderTitleMismatch("盗梦空间2010", "盗梦空间"))
        assertFalse(Scanner.folderTitleMismatch("Inception 2010", "Walang Hanggang Paalam"))
        assertFalse(Scanner.folderTitleMismatch("盗梦空间(2010){tmdb-27205}", "Whatever"))
    }

    @Test fun cjkTitleGluedYearSplits() {
        assertEquals("盗梦空间" to "2010", com.tamu.pan123.data.naming.Naming.queryFromName("盗梦空间2010"))
        assertEquals("雀骨" to "2026", com.tamu.pan123.data.naming.Naming.queryFromName("雀骨2026"))
    }

    @Test fun deselectingMkdirDeselectsDependentMoves() {
        val plan = ScrapePlan(actions = listOf(
            PlanAction("m", "mkdir", "g", parentId = "F", newName = "Season 1", kind = "folder", selected = true),
            PlanAction("mv", "move", "g", "e1", "F", "a", "a", destLabel = "Season 1", kind = "file", selected = true),
            PlanAction("r", "rename", "g", "e2", "F", "x", "y", kind = "file", selected = true),
        ))
        val off = PlanSelection.set(plan, listOf("m"), false)
        assertFalse(off.actions.first { it.id == "mv" }.selected)
        assertTrue(off.actions.first { it.id == "r" }.selected)
        val on = PlanSelection.set(PlanSelection.setAll(plan, false), listOf("mv"), true)
        assertEquals(listOf("m", "mv"), PlanSelection.selectedIds(on))
        assertEquals(PlanSelection.Tri.SOME, PlanSelection.groupState(on, "g"))
        assertNull(PlanSelection.dependencyOf(plan.actions[2], plan.actions))
    }

    @Test fun webdavAddressHints() {
        val W = com.tamu.pan123.data.webdav.WebDavAddress
        assertEquals("主机 192.168.1.5 · 端口 5005 · 路径 /dav", W.describe("http://192.168.1.5:5005/dav").text)
        assertEquals("主机 dav.example.com · 端口 443（默认） · 路径 /dav", W.describe("https://dav.example.com/dav").text)
        assertTrue(W.describe("192.168.1.5:5005").error)
        assertFalse(W.describe("https://").error)
    }
}
