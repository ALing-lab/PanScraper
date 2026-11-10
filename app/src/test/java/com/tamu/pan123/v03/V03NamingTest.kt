package com.tamu.pan123.v03

import com.tamu.pan123.data.naming.MediaParse
import com.tamu.pan123.data.naming.Naming
import com.tamu.pan123.data.update.UpdateChecker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V03NamingTest {
    @After fun reset() { Naming.simpleNaming = false; Naming.tmdbInFileName = false }

    @Test fun categoryFoldersAreNeverTitles() {
        for (n in listOf("资源", "Vidoes", "Videos", "剧集", "电影", "国产", "美剧", "2026", "4K", "国产剧", "欧美电影", "01.电影", "日韩", "动漫", "综艺", "2020s"))
            assertTrue(n, Naming.isCategoryDir(n))
        for (n in listOf("雀骨2026", "盗梦空间(2010)", "钢铁侠系列", "Inception", "繁花", "漫威电影系列"))
            assertFalse(n, Naming.isCategoryDir(n))
    }

    @Test fun genericFolders() {
        for (n in listOf("新建文件夹", "新建文件夹 (2)", "New Folder", "未命名文件夹", "temp")) assertTrue(n, Naming.isGenericDir(n))
        assertFalse(Naming.isGenericDir("第一季"))
        assertFalse(Naming.isGenericDir("雀骨"))
    }

    @Test fun varietyAndAnimeSpecials() {
        assertEquals(null to 3, Naming.parseEpisode("奔跑吧 第3期"))
        assertEquals(Naming.Extra.SPECIAL, Naming.extraKind("奔跑吧 第3期加更"))
        assertEquals(Naming.Extra.SPECIAL, Naming.extraKind("奔跑吧 花絮 01"))
        assertEquals(Naming.Extra.SPECIAL, Naming.extraKind("Kimetsu.no.Yaiba.OVA.02"))
        assertEquals(Naming.Extra.SPECIAL, Naming.extraKind("Show.S00E05.1080p"))
        assertEquals(Naming.Extra.MOVIE, Naming.extraKind("鬼灭之刃 剧场版 无限列车篇"))
        assertNull(Naming.extraKind("Que.Gu.2026.S01E02.2160p"))
        assertEquals(3, Naming.extraNumber("奔跑吧 第3期加更"))
        assertEquals(2, Naming.extraNumber("Kimetsu.OVA.02"))
        assertTrue(Naming.looksLikeCollectionName("钢铁侠系列"))
        assertTrue(Naming.looksLikeCollectionName("钢铁侠{tmdb-collection-131292}"))
    }

    // ---------- #15 音轨 ----------
    @Test fun audioTokensKeptInOrder() {
        fun a(stem: String) = MediaParse.movieAudio(stem)
        assertEquals(listOf("AAC2.0"), a("X.2020.1080p.WEB-DL.AAC2.0.H264"))
        assertEquals(listOf("AAC5.1"), a("X.2020.1080p.AAC.5.1"))
        assertEquals(listOf("DDP5.1", "Atmos"), a("X.2020.2160p.WEB-DL.DDP5.1.Atmos.H265"))
        assertEquals(listOf("DDP2.0"), a("X.2020.1080p.DDP2.0"))
        assertEquals(listOf("DD5.1"), a("X.2020.1080p.DD5.1"))
        assertEquals(listOf("AC3"), a("X.2020.720p.AC3"))
        assertEquals(listOf("EAC3"), a("X.2020.1080p.EAC3"))
        assertEquals(listOf("E-AC-3"), a("X.2020.1080p.E-AC-3"))
        assertEquals(listOf("HIFI"), a("X.2020.1080p.Hi-Fi"))
        assertEquals(listOf("HIFI"), a("X.2020.1080p.HiFi"))
        assertEquals(listOf("FLAC"), a("X.2020.1080p.FLAC"))
        assertEquals(listOf("TrueHD7.1", "Atmos"), a("X.2020.2160p.BluRay.TrueHD.7.1.Atmos"))
        assertEquals(listOf("DTS-HD.MA5.1"), a("X.2020.1080p.BluRay.DTS-HD.MA.5.1"))
        assertEquals(listOf("DTS-HD"), a("X.2020.1080p.DTS-HD"))
        assertEquals(listOf("DTS-X"), a("X.2020.2160p.DTS-X"))
        assertEquals(listOf("LPCM"), a("X.2020.2160p.LPCM"))
        assertEquals(listOf("OPUS"), a("X.2020.1080p.WEBRip.OPUS"))
        assertEquals(listOf("MP3"), a("X.2020.480p.MP3"))
        assertEquals(listOf("DDP5.1", "AAC2.0"), a("X.2020.1080p.DDP5.1.AAC2.0"))
        // 剧集也保留全部音轨
        assertEquals("雀骨.2026.S01E02.2160p.HDR10.DDP5.1.Atmos",
            Naming.buildEpisodeBase("雀骨", "2026", 1, 2, MediaParse.parseMedia("Que.Gu.S01E02.2160p.HDR10.DDP5.1.Atmos")))
    }

    // ---------- #16 简洁命名 ----------
    @Test fun simpleNaming() {
        Naming.simpleNaming = true
        assertEquals("雀骨.2026.S01E02", Naming.buildEpisodeBase("雀骨", "2026", 1, 2, MediaParse.parseMedia("Que.Gu.S01E02.2160p.HDR10.DDP5.1")))
        val mv = "Inception.2010.2160p.UHD.BluRay.REMUX.HDR10.TrueHD.Atmos-FGT"
        assertEquals("盗梦空间.2010.2160p", Naming.buildMovieBase("盗梦空间", "2010", MediaParse.parseMedia(mv), mv))
        assertEquals("盗梦空间(2010){tmdb-27205}", Naming.buildFolderName("盗梦空间", "2010", 27205)) // 文件夹不变
        Naming.simpleNaming = false
        assertTrue(Naming.buildMovieBase("盗梦空间", "2010", MediaParse.parseMedia(mv), mv).contains("TrueHD"))
    }

    // ---------- #17 播放器兼容命名 ----------
    @Test fun tmdbIdInFileName() {
        Naming.tmdbInFileName = true
        // 子开关：完整命名下不追加 ID（名字太长）
        assertEquals("雀骨.2026.S01E02.2160p", Naming.buildEpisodeBase("雀骨", "2026", 1, 2, MediaParse.parseMedia("Que.Gu.S01E02.2160p"), 295558))
        Naming.simpleNaming = true
        assertEquals("雀骨.2026.S01E02{tmdb-295558}", Naming.buildEpisodeBase("雀骨", "2026", 1, 2, MediaParse.parseMedia("x.S01E02.2160p"), 295558))
        assertEquals("盗梦空间.2010.2160p{tmdb-27205}", Naming.buildMovieBase("盗梦空间", "2010", MediaParse.parseMedia("Inception.2010.2160p"), "Inception.2010.2160p", 27205))
        // 已带 ID 的文件：不重复添加，也不影响解析
        val stem = "雀骨.2026.S01E02{tmdb-295558}"
        assertEquals(stem, Naming.buildEpisodeBase("雀骨", "2026", 1, 2, MediaParse.parseMedia(stem), 295558))
        Naming.simpleNaming = false
        val stem2 = "雀骨.2026.S01E02.2160p{tmdb-295558}"
        assertEquals("雀骨.2026.S01E02.2160p", Naming.buildEpisodeBase("雀骨", "2026", 1, 2, MediaParse.parseMedia(stem2), 295558))
        assertEquals(295558, Naming.scrapedTmdbId("$stem2.mp4"))
        assertEquals("雀骨", Naming.queryFromName(stem2).first)
        assertEquals("2160p", MediaParse.parseMedia(stem2).res)
    }

    @Test fun versionCompare() {
        assertTrue(UpdateChecker.compare("0.3.10", "0.3.9") > 0)
        assertTrue(UpdateChecker.compare("v0.4.0", "0.3.1") > 0)
        assertEquals(0, UpdateChecker.compare("v0.3.1", "0.3.1"))
        assertTrue(UpdateChecker.compare("0.3.1-beta", "0.3.2") < 0)
    }
}
