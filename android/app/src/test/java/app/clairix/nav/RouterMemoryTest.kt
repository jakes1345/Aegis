package app.clairix.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RouterMemoryTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A two-way street of [links] links between two fixed corners, so only the edge count changes. */
    private fun street(links: Int): RoadGraph {
        val m = MiniGraph()
        val ids = (0..links).map { i -> m.node(40.0 + 0.01 * i / links, -83.0 + 0.01 * i / links) }
        for (i in 0 until links) {
            m.edge(ids[i], ids[i + 1], 1, "Main Street", 4, EdgeFlags.FORWARD)
            m.edge(ids[i + 1], ids[i], 1, "Main Street", 4, 0)
        }
        return m.open(tmp.newFolder(), "street$links.agr")
    }

    @Test
    fun estimateIsNonZeroAndGrowsWithEdgeCount() {
        val sizes = listOf(1, 10, 100, 1_000).map { links ->
            val g = street(links)
            assertEquals(2 * links, g.edgeCount)
            Router.estimatedHeapBytes(g)
        }
        assertTrue("estimate must be non-zero: $sizes", sizes.first() > 0)
        for (i in 1 until sizes.size) {
            assertTrue("estimate must grow with edge count: $sizes", sizes[i] > sizes[i - 1])
        }
        // At least the four 4-byte work arrays per edge.
        assertTrue(sizes.last() - sizes.first() >= 16L * (2_000 - 2))
    }

    @Test
    fun builtIndexIsNoLongerCounted() {
        val g = street(10)
        val before = Router.estimatedHeapBytes(g)
        g.nearestEdges(40.005, -82.995, 50.0, 1)
        assertTrue(Router.estimatedHeapBytes(g) < before)
    }
}
