package com.tamu.pan123.naming

import com.tamu.pan123.data.drive.CloudEntry
import com.tamu.pan123.data.scrape.Scanner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleFolderHeuristicTest {
    private fun f(n: String) = CloudEntry(n, n, false)
    private fun d(n: String) = CloudEntry(n, n, true)

    @Test fun categoryWithLooseMovieAndSubfolderIsParent() =
        assertFalse(Scanner.looksLikeTitleFolder(listOf(f("Inception.2010.mkv"), d("The.Matrix.1999"))))

    @Test fun movieWithExtrasIsTitle() =
        assertTrue(Scanner.looksLikeTitleFolder(listOf(f("Inception.2010.mkv"), d("Extras"), d("Subs"))))

    @Test fun showWithSeasonsIsTitle() =
        assertTrue(Scanner.looksLikeTitleFolder(listOf(d("Season 1"), d("S02"))))

    @Test fun onlyFoldersIsParent() =
        assertFalse(Scanner.looksLikeTitleFolder(listOf(d("A"), d("B"))))
}
