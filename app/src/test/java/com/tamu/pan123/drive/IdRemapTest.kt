package com.tamu.pan123.drive

import com.tamu.pan123.data.drive.DrivePaths
import com.tamu.pan123.data.drive.IdRemap
import org.junit.Assert.assertEquals
import org.junit.Test

class IdRemapTest {
    @Test fun stableIdsUntouched() {
        val r = IdRemap(pathMode = false)
        r.record("11", "11")
        assertEquals("11", r.resolve("11"))
        assertEquals(null, r.resolve(null))
    }

    @Test fun pathPrefixAndChains() {
        val r = IdRemap(pathMode = true)
        r.record("/A", "/B")
        assertEquals("/B/x/y.mkv", r.resolve("/A/x/y.mkv"))
        assertEquals("/AB/z", r.resolve("/AB/z")) // 不是 /A 的子路径
        r.record("/B/x", "/B/Season 1")
        assertEquals("/B/Season 1/y.mkv", r.resolve("/A/x/y.mkv"))
    }

    @Test fun pathHelpers() {
        assertEquals("/a", DrivePaths.join("/", "a"))
        assertEquals("/a/b", DrivePaths.join("/a/", "b"))
        assertEquals("/", DrivePaths.parentOf("/a"))
        assertEquals("/a", DrivePaths.parentOf("/a/b"))
        assertEquals("b", DrivePaths.nameOf("/a/b/"))
    }
}
