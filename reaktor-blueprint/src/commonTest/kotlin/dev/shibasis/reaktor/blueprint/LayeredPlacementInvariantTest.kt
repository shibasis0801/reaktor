package dev.shibasis.reaktor.blueprint

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayeredPlacementInvariantTest {
    private val nodes = listOf(
        LayeredNode("in", 40.0, 32.0, listOf(LayeredPort("out", true, 8.0)), LayeredRank.Entry),
        LayeredNode("a", 96.0, 60.0, listOf(LayeredPort("in", false, 12.0), LayeredPort("out", true, 48.0))),
        LayeredNode("b", 80.0, 40.0, listOf(LayeredPort("in", false, 8.0), LayeredPort("out", true, 30.0))),
        LayeredNode("c", 90.0, 50.0, listOf(LayeredPort("in", false, 35.0), LayeredPort("out", true, 10.0)), LayeredRank.Last),
        LayeredNode("out", 40.0, 32.0, listOf(LayeredPort("in", false, 24.0)), LayeredRank.Exit),
        LayeredNode("loose-a", 55.0, 35.0),
        LayeredNode("loose-b", 45.0, 25.0),
        LayeredNode("standalone", 33.0, 21.0),
    )
    private val edges = listOf(
        LayeredEdge("in-a", "in", "a", "out", "in"),
        LayeredEdge("in-b", "in", "b", "out", "in"),
        LayeredEdge("a-c", "a", "c", "out", "in", 3),
        LayeredEdge("b-c", "b", "c", "out", "in"),
        LayeredEdge("c-out", "c", "out", "out", "in"),
        LayeredEdge("c-a", "c", "a", "out", "in"),
        LayeredEdge("a-b", "a", "b", "out", "in"),
        LayeredEdge("b-a", "b", "a", "out", "in"),
        LayeredEdge("b-loop", "b", "b", "out", "in"),
        LayeredEdge("loose", "loose-a", "loose-b"),
    )

    // Captured from the solver before invariant caching and scratch reuse.
    @Test fun unconstrainedMixedGraphKeepsEveryCoordinateAndRoute() {
        verify(false, 524.0, 294.06507777277614,
            doubleArrayOf(7.0, 88.0, 225.0, 346.0, 477.0, 7.0, 103.0, 7.0),
            doubleArrayOf(101.83231839112156, 59.0, 116.06507777277612, 76.60881439949122, 62.60881439949122, 202.06507777277614, 207.06507777277614, 266.06507777277614),
            arrayOf(
                doubleArrayOf(47.0, 109.83231839112156, 67.5, 109.83231839112156, 67.5, 71.0, 88.0, 71.0),
                doubleArrayOf(47.0, 109.83231839112156, 67.5, 109.83231839112156, 67.5, 129.2, 215.0, 129.2, 215.0, 124.06507777277612, 225.0, 124.06507777277612),
                doubleArrayOf(184.0, 107.0, 194.0, 107.0, 194.0, 105.86507777277612, 325.5, 105.86507777277612, 325.5, 111.60881439949122, 346.0, 111.60881439949122),
                doubleArrayOf(305.0, 146.06507777277614, 325.5, 146.06507777277614, 325.5, 111.60881439949122, 346.0, 111.60881439949122),
                doubleArrayOf(436.0, 86.60881439949122, 477.0, 86.60881439949122),
                doubleArrayOf(436.0, 86.60881439949122, 452.0, 86.60881439949122, 452.0, 7.0, 72.0, 7.0, 72.0, 71.0, 88.0, 71.0),
                doubleArrayOf(184.0, 107.0, 194.0, 107.0, 194.0, 124.06507777277612, 225.0, 124.06507777277612),
                doubleArrayOf(305.0, 146.06507777277614, 315.0, 146.06507777277614, 315.0, 173.06507777277614, 78.0, 173.06507777277614, 78.0, 71.0, 88.0, 71.0),
                doubleArrayOf(305.0, 146.06507777277614, 317.0, 146.06507777277614, 317.0, 104.06507777277612, 213.0, 104.06507777277612, 213.0, 124.06507777277612, 225.0, 124.06507777277612),
                doubleArrayOf(62.0, 219.56507777277614, 103.0, 219.56507777277614),
            ),
        )
    }

    @Test fun compactMixedGraphKeepsEveryCoordinateAndRoute() {
        verify(true, 524.0, 267.2,
            doubleArrayOf(7.0, 88.0, 225.0, 346.0, 477.0, 7.0, 103.0, 7.0),
            doubleArrayOf(97.2, 59.0, 89.2, 59.0, 59.0, 175.2, 180.2, 239.2),
            arrayOf(
                doubleArrayOf(47.0, 105.2, 67.5, 105.2, 67.5, 71.0, 88.0, 71.0),
                doubleArrayOf(47.0, 105.2, 67.5, 105.2, 67.5, 129.2, 215.0, 129.2, 215.0, 97.2, 225.0, 97.2),
                doubleArrayOf(184.0, 107.0, 194.0, 107.0, 194.0, 79.0, 325.5, 79.0, 325.5, 94.0, 346.0, 94.0),
                doubleArrayOf(305.0, 119.2, 325.5, 119.2, 325.5, 94.0, 346.0, 94.0),
                doubleArrayOf(436.0, 69.0, 456.5, 69.0, 456.5, 83.0, 477.0, 83.0),
                doubleArrayOf(436.0, 69.0, 452.0, 69.0, 452.0, 7.0, 72.0, 7.0, 72.0, 71.0, 88.0, 71.0),
                doubleArrayOf(184.0, 107.0, 194.0, 107.0, 194.0, 97.2, 225.0, 97.2),
                doubleArrayOf(305.0, 119.2, 315.0, 119.2, 315.0, 146.2, 78.0, 146.2, 78.0, 71.0, 88.0, 71.0),
                doubleArrayOf(305.0, 119.2, 317.0, 119.2, 317.0, 77.2, 213.0, 77.2, 213.0, 97.2, 225.0, 97.2),
                doubleArrayOf(62.0, 192.7, 103.0, 192.7),
            ),
        )
    }

    private fun verify(compact: Boolean, width: Double, height: Double, x: DoubleArray, y: DoubleArray, routes: Array<DoubleArray>) {
        val spacing = LayeredSpacing(node = 17.0, layer = 41.0, edge = 6.0, component = 29.0,
            aspect = 1.4, padding = 7.0, align = LayeredAlign.West, compact = compact)
        val actual = Layered.layout(nodes, edges, spacing)
        assertEquals(width, actual.width)
        assertEquals(height, actual.height)
        assertContentEquals(x, actual.x)
        assertContentEquals(y, actual.y)
        assertEquals(edges.size, actual.routes.size)
        routes.forEachIndexed { i, route -> assertContentEquals(route, actual.routes[i], edges[i].id) }

        val repeated = Layered.layout(nodes, edges, spacing)
        assertContentEquals(actual.x, repeated.x)
        assertContentEquals(actual.y, repeated.y)
        actual.routes.forEachIndexed { i, route -> assertContentEquals(route, repeated.routes[i]) }
        assertEquals(actual.width, repeated.width)
        assertEquals(actual.height, repeated.height)
        nodes.forEachIndexed { i, a ->
            assertTrue(actual.x[i].isFinite() && actual.y[i].isFinite())
            assertTrue(actual.x[i] >= 0 && actual.y[i] >= 0)
            assertTrue(actual.x[i] + a.width <= actual.width && actual.y[i] + a.height <= actual.height)
            for (j in i + 1 until nodes.size) {
                val b = nodes[j]
                assertTrue(actual.x[i] + a.width <= actual.x[j] || actual.x[j] + b.width <= actual.x[i] ||
                    actual.y[i] + a.height <= actual.y[j] || actual.y[j] + b.height <= actual.y[i], "${a.id} overlaps ${b.id}")
            }
        }
        actual.routes.forEachIndexed { i, route ->
            assertTrue(route.size >= 4 && route.size % 2 == 0 && route.all { it.isFinite() }, edges[i].id)
            val edge = edges[i]
            val from = nodes.indexOfFirst { it.id == edge.from }
            val to = nodes.indexOfFirst { it.id == edge.to }
            val out = nodes[from].ports.firstOrNull { it.id == edge.fromPort }?.offset ?: nodes[from].anchor
            val into = nodes[to].ports.firstOrNull { it.id == edge.toPort }?.offset ?: nodes[to].anchor
            assertEquals(actual.x[from] + nodes[from].width, route[0], edge.id)
            assertEquals(actual.y[from] + out, route[1], edge.id)
            assertEquals(actual.x[to], route[route.size - 2], edge.id)
            assertEquals(actual.y[to] + into, route.last(), edge.id)
            for (point in 2 until route.size step 2) {
                assertTrue(route[point] == route[point - 2] || route[point + 1] == route[point - 1], "${edge.id} is not orthogonal")
            }
        }
    }
}
