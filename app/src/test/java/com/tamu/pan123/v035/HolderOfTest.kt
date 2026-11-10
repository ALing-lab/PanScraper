package com.tamu.pan123.v035

import com.tamu.pan123.data.scrape.Scanner
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** M10：Scanner 目录树的 holder() 向上找根下直属子目录；不在树里时安全返回 null，不再 NPE。 */
class HolderOfTest {
    private class N(val parent: N?)

    @Test fun findsDirectChildOfRoot() {
        val root = N(null); val a = N(root); val b = N(a); val c = N(b)
        assertSame(a, Scanner.holderOf(c, root) { it.parent })
        assertSame(a, Scanner.holderOf(b, root) { it.parent })
        assertSame(a, Scanner.holderOf(a, root) { it.parent })
    }

    @Test fun rootItselfAndForeignNodesReturnNull() {
        val root = N(null); val other = N(null); val x = N(other)
        assertNull(Scanner.holderOf(root, root) { it.parent })
        // 不在 root 这棵树里：旧代码 n.parent!! 会越根 NPE
        assertNull(Scanner.holderOf(x, root) { it.parent })
        assertNull(Scanner.holderOf(other, root) { it.parent })
    }
}
