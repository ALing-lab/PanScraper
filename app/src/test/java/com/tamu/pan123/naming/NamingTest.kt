package com.tamu.pan123.naming

import com.tamu.pan123.data.naming.MediaParse
import com.tamu.pan123.data.naming.Naming
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NamingTest {
    private val fixtures: JSONObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("naming_fixtures.json")
            ?: error("naming_fixtures.json missing")
        JSONObject(stream.bufferedReader().readText())
    }

    @Test fun groupCases() {
        val arr = fixtures.getJSONArray("group_cases")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val stem = o.getString("stem")
            val expect = o.getString("expect")
            val got = Naming.buildMovieBase("T", "2020", MediaParse.parseMedia(stem), stem)
            assertEquals("stem=$stem", expect, got)
        }
    }

    @Test fun hdrCases() {
        val arr = fixtures.getJSONArray("hdr")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            assertEquals(o.getString("stem"), o.getString("hdr"), MediaParse.movieHdr(o.getString("stem")))
        }
    }

    @Test fun movieBases() {
        val arr = fixtures.getJSONArray("movie_bases")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val got = Naming.buildMovieBase(o.getString("title"), o.getString("year"),
                MediaParse.parseMedia(o.getString("stem")), o.getString("stem"))
            assertEquals(o.getString("stem"), o.getString("base"), got)
        }
    }

    @Test fun episodeBases() {
        val arr = fixtures.getJSONArray("episode_bases")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val got = Naming.buildEpisodeBase("繁花", "2023", o.getInt("season"), o.getInt("episode"),
                MediaParse.parseMedia(o.getString("stem")))
            assertEquals(o.getString("stem"), o.getString("base"), got)
            if (o.has("new_stem") && !o.isNull("new_stem")) {
                assertEquals(o.getString("new_stem"), got)
            }
        }
    }

    @Test fun folderAndCollection() {
        assertEquals(fixtures.getString("folder"), Naming.buildFolderName("盗梦空间", "2010", 27205))
        assertEquals(fixtures.getString("collection"), Naming.buildCollectionName("黑客帝国（系列）", 2344))
    }

    @Test fun queryFromName() {
        val arr = fixtures.getJSONArray("query_from_name")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val (t, y) = Naming.queryFromName(o.getString("stem"))
            assertEquals(o.getString("stem") + " title", o.getString("title"), t)
            val ey = if (o.isNull("year")) null else o.getString("year")
            assertEquals(o.getString("stem") + " year", ey, y)
        }
    }

    @Test fun seasonDir() {
        val arr = fixtures.getJSONArray("season_dir")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            assertEquals(o.getString("name"), o.getBoolean("is_season"), Naming.isSeasonDir(o.getString("name")))
            val en = if (o.isNull("num")) null else o.getInt("num")
            assertEquals(o.getString("name"), en, Naming.seasonFromName(o.getString("name")))
        }
    }

    @Test fun claimV2() {
        val taken = mutableSetOf("流浪地球2.2023.1080p.mp4")
        val got = Naming.claim("流浪地球2.2023.1080p.mp4", "流浪地球2.2023.1080p.WEB-DL.H265.mp4", taken)
        assertEquals("流浪地球2.2023.1080p.v2.mp4", got)
    }
}
