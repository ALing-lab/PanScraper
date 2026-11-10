package com.tamu.pan123.v035

import com.tamu.pan123.data.scrape.PlanAction
import com.tamu.pan123.data.scrape.PlanSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** L12：dependencyOf 只返回无依赖的 mkdir 叶子，所以只取一层依赖就够了。 */
class DependencyLeafTest {
    @Test fun dependencyIsAlwaysALeafMkdir() {
        val mk = PlanAction("mk", "mkdir", "g", parentId = "P", newName = "Season 1", kind = "folder")
        val mv = PlanAction("mv", "move", "g", "f1", "P", "a.mkv", "a.mkv", destLabel = "Season 1")
        val art = PlanAction("art", "artwork", "g", parentId = "P", newName = "poster.jpg", destRef = "mk", destLabel = "Season 1")
        val all = listOf(mk, mv, art)
        assertEquals("mk", PlanSelection.dependencyOf(mv, all)?.id)
        assertEquals("mk", PlanSelection.dependencyOf(art, all)?.id)
        for (a in all) PlanSelection.dependencyOf(a, all)?.let { assertNull("依赖链应只有一层", PlanSelection.dependencyOf(it, all)) }
    }
}
