package com.tamu.pan123.v034

import com.tamu.pan123.data.exec.ImageBytes
import com.tamu.pan123.data.exec.MovieNfo
import com.tamu.pan123.data.exec.NfoXml
import com.tamu.pan123.data.io.AtomicFiles
import com.tamu.pan123.data.naming.Naming
import com.tamu.pan123.data.net.HostMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

class V034PureTest {
    @Test fun hostMatchUsesDotBoundary() {
        assertTrue(HostMatch.isOrSubdomainOf("image.tmdb.org", "tmdb.org"))
        assertTrue(HostMatch.isOrSubdomainOf("tmdb.org", "tmdb.org"))
        assertFalse(HostMatch.isOrSubdomainOf("eviltmdb.org", "tmdb.org"))
        assertFalse(HostMatch.isOrSubdomainOf("tmdb.org.evil.com", "tmdb.org"))
        assertTrue(HostMatch.isOrSubdomainOf("WWW.123PAN.COM.", "123pan.com"))
    }

    @Test fun cn2intNeverThrows() {
        assertEquals(0, Naming.cn2int(""))
        assertEquals(12, Naming.cn2int("十二"))
        assertEquals(0, Naming.cn2int("99999999999999999999"))
        assertEquals(10, Naming.cn2int("十啊")) // 不认识的字当 0，不会得到负数
    }

    @Test fun execName() {
        assertEquals("a.mkv", Naming.execName(" a.mkv\u0000"))
        for (bad in listOf("", "..", ".", "a/b.mkv", "a\\b.mkv", "x".repeat(300))) {
            try { Naming.execName(bad); throw AssertionError(bad) } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun cdataAndEscapingRoundTrip() {
        val plot = "他说 \"]]>\" & <b>粗体</b>"
        val xml = "<?xml version=\"1.0\"?><m>${NfoXml.cdata("plot", plot)}<t a=\"${NfoXml.esc("x\"y'<")}\">${NfoXml.esc("A&B")}</t></m>"
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals(plot, doc.getElementsByTagName("plot").item(0).textContent)
        assertEquals("x\"y'<", (doc.getElementsByTagName("t").item(0) as org.w3c.dom.Element).getAttribute("a"))
    }

    @Test fun movieNfoIsWellFormedWithNastyValues() {
        val detail = """{"title":"A<&>B","original_title":"O","overview":"x]]>y","imdb_id":"tt1\"<x","release_date":"2010-01-01"}"""
        val credits = """{"cast":[{"name":"N","character":"C","profile_path":"/a&b.jpg"}],"crew":[]}"""
        val nfo = MovieNfo.build(1, detail, credits, "")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(nfo.byteInputStream())
        assertEquals("x]]>y", doc.getElementsByTagName("plot").item(0).textContent)
        assertEquals("https://image.tmdb.org/t/p/original/a&b.jpg", doc.getElementsByTagName("thumb").item(0).textContent)
    }

    @Test fun imageValidation() {
        assertTrue(ImageBytes.looksLikeImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0)))
        assertTrue(ImageBytes.looksLikeImage(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)))
        assertFalse(ImageBytes.looksLikeImage("<html><body>blocked</body></html>".toByteArray()))
        assertEquals("jpg", ImageBytes.safeExt("https://x/a.jpg?x=1"))
        assertEquals("png", ImageBytes.safeExt("https://x/a.PNG"))
        assertEquals("jpg", ImageBytes.safeExt("https://x/a.php/../../x"))
        assertEquals("jpg", ImageBytes.safeExt("https://x/noext"))
    }

    @Test fun atomicWriteReplaces() {
        val d = Files.createTempDirectory("aw").toFile()
        val f = java.io.File(d, "current.txt")
        AtomicFiles.writeText(f, "one"); AtomicFiles.writeText(f, "two")
        assertEquals("two", f.readText())
        assertEquals(listOf("current.txt"), d.list()!!.toList()) // 没有残留临时文件
        d.deleteRecursively()
    }

    @Test fun lz4LargerThan8k() {
        val plain = ByteArray(100_000) { (it % 7).toByte() }
        val f = net.jpountz.lz4.LZ4Factory.fastestInstance()
        val comp = f.fastCompressor().compress(plain)
        val c = com.tamu.pan123.data.p115.Ec115Cipher.forTest(ByteArray(16), ByteArray(16))
        assertTrue(plain.contentEquals(c.lz4Decompress(comp, 0, comp.size)))
    }
}
