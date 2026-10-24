package dev.shibasis.reaktor.blueprint

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayeredTest {
    private fun box(id: String, width: Double = 120.0, height: Double = 40.0, ports: List<LayeredPort> = emptyList(), rank: LayeredRank = LayeredRank.Free) =
        LayeredNode(id, width, height, ports, rank)

    private fun overlaps(result: LayeredResult, nodes: List<LayeredNode>): Boolean = nodes.indices.any { i ->
        (0 until i).any { j ->
            result.x[i] < result.x[j] + nodes[j].width - 0.5 && result.x[j] < result.x[i] + nodes[i].width - 0.5 &&
                result.y[i] < result.y[j] + nodes[j].height - 0.5 && result.y[j] < result.y[i] + nodes[i].height - 0.5
        }
    }

    @Test fun `a chain reads left to right on one straight line`() {
        val nodes = (0..5).map { box("n$it") }
        val edges = (0..4).map { LayeredEdge("e$it", "n$it", "n${it + 1}") }
        val result = Layered.layout(nodes, edges)
        (0..4).forEach { assertTrue(result.x[it] + nodes[it].width < result.x[it + 1]) }
        assertTrue(result.y.distinct().size == 1)
        result.routes.forEach { route -> assertTrue((1 until route.size / 2).all { route[it * 2 + 1] == route[1] }) }
    }

    @Test fun `every edge leaves its source on the right and enters its target on the left`() {
        val nodes = listOf("a", "b", "c", "d", "e").map { box(it) }
        val edges = listOf("a" to "b", "a" to "c", "b" to "d", "c" to "d", "a" to "e", "e" to "d").mapIndexed { i, (f, t) -> LayeredEdge("e$i", f, t) }
        val result = Layered.layout(nodes, edges)
        assertTrue(!overlaps(result, nodes))
        val at = nodes.withIndex().associate { (i, n) -> n.id to i }
        edges.forEachIndexed { i, edge ->
            val route = result.routes[i]
            val s = at.getValue(edge.from); val t = at.getValue(edge.to)
            assertEquals(result.x[s] + nodes[s].width, route[0], 1e-6)
            assertEquals(result.x[t], route[route.size - 2], 1e-6)
            assertTrue(result.x[s] < result.x[t])
        }
    }

    @Test fun `a cycle keeps every node and routes every edge`() {
        val nodes = (0..3).map { box("n$it") }
        val edges = (0..3).map { LayeredEdge("e$it", "n$it", "n${(it + 1) % 4}") }
        val result = Layered.layout(nodes, edges)
        assertTrue(!overlaps(result, nodes))
        assertTrue(result.routes.all { it.size >= 4 && it.all(Double::isFinite) })
    }

    @Test fun `ports decide where edges attach`() {
        val ports = listOf(LayeredPort("out-top", true, 10.0), LayeredPort("out-low", true, 70.0))
        val nodes = listOf(box("source", height = 80.0, ports = ports), box("upper", ports = listOf(LayeredPort("in", false, 20.0))), box("lower", ports = listOf(LayeredPort("in", false, 20.0))))
        val edges = listOf(LayeredEdge("a", "source", "upper", "out-top", "in"), LayeredEdge("b", "source", "lower", "out-low", "in"))
        val result = Layered.layout(nodes, edges)
        assertEquals(result.y[0] + 10.0, result.routes[0][1], 1e-6)
        assertEquals(result.y[0] + 70.0, result.routes[1][1], 1e-6)
        assertEquals(result.y[1] + 20.0, result.routes[0].last(), 1e-6)
        assertTrue(result.y[1] < result.y[2])
    }

    @Test fun `a twisted bipartite graph is untangled`() {
        val left = (0..5).map { box("l$it") }
        val right = (0..5).map { box("r$it") }
        val twist = listOf(3, 0, 5, 1, 4, 2)
        val edges = twist.mapIndexed { i, j -> LayeredEdge("e$i", "l$i", "r$j") }
        val result = Layered.layout(left + right, edges)
        val order = edges.map { edge -> result.y[6 + edge.to.drop(1).toInt()] to result.y[edge.from.drop(1).toInt()] }
        val sorted = order.sortedBy { it.second }
        assertEquals(sorted.map { it.first }, sorted.map { it.first }.sorted())
    }

    @Test fun `components sit apart`() {
        val nodes = (0..7).map { box("n$it") }
        val edges = listOf(LayeredEdge("a", "n0", "n1"), LayeredEdge("b", "n2", "n3"), LayeredEdge("c", "n4", "n5"))
        val result = Layered.layout(nodes, edges)
        assertTrue(!overlaps(result, nodes))
        assertTrue(result.width > 0 && result.height > 0)
    }

    @Test fun `wide layers wrap into columns`() {
        val nodes = listOf(box("hub")) + (0 until 30).map { box("leaf$it") }
        val edges = (0 until 30).map { LayeredEdge("e$it", "hub", "leaf$it") }
        val result = Layered.layout(nodes, edges, LayeredSpacing(wrap = 10))
        assertEquals(3, (1..30).map { result.x[it] }.distinct().size)
        assertTrue(!overlaps(result, nodes))
    }

    @Test fun `the frame layouter keeps lane order and semantic endpoints`() {
        val nodes = listOf(BlueprintNode("early", 0), BlueprintNode("late", 2))
        val layout = BlueprintEngine.layout("lanes", listOf(BlueprintGroup("lanes", "Lanes", nodes)), listOf(BlueprintEdge("back", "late", "early")), 1.6, LayeredFrameLayouter)
        val link = layout.links.single()
        val from = layout.cards.getValue("late")
        val to = layout.cards.getValue("early")
        assertTrue(from.x > to.x)
        assertEquals(from.x + from.width, link.points.first().first, 1.0)
        assertEquals(to.x, link.points.last().first, 1.0)
    }

    @Test fun `large graphs lay out quickly and without overlap`() {
        val random = Random(11)
        val nodes = (0 until 400).map { box("n$it", height = 40.0 + random.nextInt(4) * 18.0) }
        val edges = (0 until 900).map { i ->
            val a = random.nextInt(400); val b = random.nextInt(400)
            LayeredEdge("e$i", "n${minOf(a, b)}", "n${maxOf(a, b)}")
        }
        val started = System.nanoTime()
        val result = Layered.layout(nodes, edges)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(!overlaps(result, nodes))
        assertTrue(result.routes.indices.all { edges[it].from == edges[it].to || result.routes[it].size >= 4 })
        assertTrue(millis < 8_000, "took $millis ms")
    }
}
