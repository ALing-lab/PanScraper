package com.tamu.pan123.guangya

import com.tamu.pan123.data.AppDirs
import com.tamu.pan123.data.drive.CloudEntry
import com.tamu.pan123.data.exec.ExecutionEngine
import com.tamu.pan123.data.exec.MovieNfo
import com.tamu.pan123.data.exec.TaskStore
import com.tamu.pan123.data.guangya.GyCreds
import com.tamu.pan123.data.scrape.PlanAction
import com.tamu.pan123.data.scrape.PlanGroup
import com.tamu.pan123.data.scrape.PlanStats
import com.tamu.pan123.data.scrape.PlanStore
import com.tamu.pan123.data.scrape.ScrapePlan
import com.tamu.pan123.data.scrape.Scanner
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

/** 执行引擎 + GuangyaDrive + 假光鸭服务器的端到端测试，以及电影海报 / NFO 规划。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuangyaEngineTest {
    private lateinit var srv: FakeGuangyaServer

    @Before
    fun setUp() {
        AppDirs.init(RuntimeEnvironment.getApplication())
        AppDirs.clearAll()
        TaskStore.clear()
        srv = FakeGuangyaServer().start()
        ExecutionEngine.OP_SLEEP_MS = 0
        ExecutionEngine.artworkOverride = { a -> "fake:${a.newName}".toByteArray() }
    }

    @After
    fun tearDown() {
        ExecutionEngine.driveOverride = null
        ExecutionEngine.artworkOverride = null
        TaskStore.clear()
        srv.shutdown()
    }

    @Test
    fun executesTvAndMoviePlanAgainstFakeGuangya() = runBlocking {
        srv.dir("TV", "庆余年")
        srv.file("e1", "qyn.ep01.1080p.mkv", "TV")
        srv.dir("MV", "inception.2010")
        srv.file("m1", "Inception.2010.1080p.mkv", "MV")
        srv.file("fa", "fanart.jpg", "MV")
        val store = MemTokenStore(GyCreds("AT-1", "RT-1", System.currentTimeMillis() + 3_600_000, "d".repeat(32)))
        ExecutionEngine.driveOverride = srv.drive(store)
        val plan = ScrapePlan(
            createdAt = "test",
            accountId = "guangya-test",
            provider = "guangya",
            groups = listOf(
                PlanGroup("g1", "tv", 1, "庆余年", "2019", folderId = "TV", folderName = "庆余年"),
                PlanGroup("g2", "movie", 27205, "盗梦空间", "2010", folderId = "MV", folderName = "inception.2010"),
            ),
            actions = listOf(
                PlanAction("m1", "mkdir", "g1", parentId = "TV", newName = "Season 01", kind = "folder", season = 1),
                PlanAction("r1", "rename", "g1", fileId = "e1", parentId = "TV", oldName = "qyn.ep01.1080p.mkv", newName = "庆余年.2019.S01E01.1080p.mkv", kind = "file"),
                PlanAction("v1", "move", "g1", fileId = "e1", parentId = "TV", oldName = "庆余年.2019.S01E01.1080p.mkv",
                    newName = "庆余年.2019.S01E01.1080p.mkv", destLabel = "Season 01", kind = "file"),
                PlanAction("r2", "rename", "g1", fileId = "TV", parentId = "", oldName = "庆余年", newName = "庆余年 (2019) {tmdb-1}", kind = "folder"),
                PlanAction("r3", "rename", "g2", fileId = "m1", parentId = "MV", oldName = "Inception.2010.1080p.mkv", newName = "盗梦空间.2010.1080p.mkv", kind = "file"),
                PlanAction("a1", "artwork", "g2", parentId = "MV", newName = "poster.jpg", kind = "image", mediaKind = "movie", tmdbId = 27205),
                PlanAction("a2", "artwork", "g2", parentId = "MV", newName = "fanart.jpg", kind = "image", mediaKind = "movie", tmdbId = 27205),
                PlanAction("a3", "artwork", "g2", parentId = "MV", newName = "movie.nfo", kind = "movie_nfo", mediaKind = "movie", tmdbId = 27205),
            ),
            stats = PlanStats(rename = 3, mkdir = 1, move = 1, artwork = 3, groups = 2),
        )
        PlanStore.save(plan, "scrape_gy.json")
        val summary = ExecutionEngine.run(PlanStore.load())
        assertEquals(0, summary.fail)
        assertEquals(7, summary.ok)
        assertEquals(1, summary.skipped) // fanart.jpg 已存在 → 跳过（未覆盖）
        val season = srv.childrenOf("TV").single { it.dir }
        assertEquals("Season 01", season.name)
        assertEquals(season.id, srv.nodes["e1"]!!.parent)
        assertEquals("庆余年.2019.S01E01.1080p.mkv", srv.nodes["e1"]!!.name)
        assertEquals("庆余年 (2019) {tmdb-1}", srv.nodes["TV"]!!.name)
        assertEquals("盗梦空间.2010.1080p.mkv", srv.nodes["m1"]!!.name)
        val mvNames = srv.childrenOf("MV").map { it.name }.toSet()
        assertTrue(mvNames.containsAll(setOf("poster.jpg", "fanart.jpg", "movie.nfo")))
        // fanart.jpg 已存在 → 不上传（两次上传：poster + nfo）
        assertEquals(2, srv.bodies.count { it.first.contains("get_res_center_token") })
        assertTrue(PlanStore.load().actions.all { it.done })
    }

    @Test
    fun plansMovieArtworkOnlyForMissingFiles() {
        val items = listOf(
            CloudEntry("1", "Inception.2010.mkv", false),
            CloudEntry("2", "Inception.2010-poster.jpg", false),
        )
        val actions = mutableListOf<PlanAction>()
        val ids = mutableListOf<String>()
        Scanner.planMovieArtwork("MV", 27205, "/p.jpg", "g", actions, ids, items)
        assertEquals(listOf("fanart.jpg", "movie.nfo"), actions.map { it.newName })
        assertTrue(actions.all { it.op == "artwork" && it.mediaKind == "movie" && it.tmdbId == 27205 && it.parentId == "MV" })
        assertEquals("movie_nfo", actions[1].kind)
        assertEquals(ids, actions.map { it.id })

        val a2 = mutableListOf<PlanAction>()
        Scanner.planMovieArtwork("MV", 1, "/p.jpg", "g", a2, mutableListOf(), listOf(CloudEntry("1", "a.mkv", false)))
        assertEquals(listOf("poster.jpg", "fanart.jpg", "movie.nfo"), a2.map { it.newName })
        assertEquals("https://image.tmdb.org/t/p/w780/p.jpg", a2[0].artworkUrl)

        val a3 = mutableListOf<PlanAction>()
        Scanner.planMovieArtwork("MV", 1, null, "g", a3, mutableListOf(), listOf(
            CloudEntry("1", "a.mkv", false), CloudEntry("2", "folder.jpg", false),
            CloudEntry("3", "background.png", false), CloudEntry("4", "a.nfo", false),
        ))
        assertTrue(a3.isEmpty())
    }

    @Test
    fun movieNfoHasKodiFields() {
        val detail = """{"id":27205,"title":"盗梦空间","original_title":"Inception","overview":"道姆·柯布\n与他的拍档","tagline":"梦境 & 现实",
            "runtime":148,"release_date":"2010-07-15","vote_average":8.369,"imdb_id":"tt1375666",
            "genres":[{"id":28,"name":"动作"},{"id":878,"name":"科幻"}],
            "production_countries":[{"iso_3166_1":"US","name":"美国"}],
            "production_companies":[{"id":923,"name":"Legendary Pictures"}],
            "belongs_to_collection":null}"""
        val credits = """{"cast":[{"id":6193,"name":"莱昂纳多·迪卡普里奥","character":"Cobb","profile_path":"/x.jpg"}],
            "crew":[{"id":525,"name":"克里斯托弗·诺兰","job":"Director"},{"id":525,"name":"克里斯托弗·诺兰","job":"Director"}]}"""
        val nfo = MovieNfo.build(27205, detail, credits, "盗梦空间")
        assertTrue(nfo.startsWith("<?xml"))
        assertTrue(nfo.contains("<movie>") && nfo.trimEnd().endsWith("</movie>"))
        assertTrue(nfo.contains("<title>盗梦空间</title>"))
        assertTrue(nfo.contains("<originaltitle>Inception</originaltitle>"))
        assertTrue(nfo.contains("<plot><![CDATA[道姆·柯布与他的拍档]]></plot>"))
        assertTrue(nfo.contains("<tagline>梦境 &amp; 现实</tagline>"))
        assertTrue(nfo.contains("<runtime>148</runtime>"))
        assertTrue(nfo.contains("<year>2010</year>"))
        assertTrue(nfo.contains("<rating>8.4</rating>"))
        assertTrue(nfo.contains("<uniqueid type=\"imdb\" default=\"true\">tt1375666</uniqueid>"))
        assertTrue(nfo.contains("<uniqueid type=\"tmdb\" default=\"false\">27205</uniqueid>"))
        assertTrue(nfo.contains("<genre>科幻</genre>"))
        assertTrue(nfo.contains("<country>美国</country>"))
        assertEquals(1, Regex("<director").findAll(nfo).count())
        assertTrue(nfo.contains("<role>Cobb</role>"))
        assertTrue(!nfo.contains("<set>"))
        // XML 可被解析
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(nfo.byteInputStream())
    }
}
