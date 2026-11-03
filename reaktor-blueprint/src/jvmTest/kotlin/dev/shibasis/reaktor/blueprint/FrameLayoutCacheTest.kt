package dev.shibasis.reaktor.blueprint

import dev.shibasis.reaktor.graph.core.PortWiring
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FrameLayoutCacheTest {
    @Test
    fun equivalentRequestsReuseGeometryAndDetachEveryResultArray() {
        val original = geometry(request())
        val cache = FrameLayoutCache({ original })
        val first = cache.lay(request())
        val expected = first.copy(nodes = first.nodes.copyOf(), edges = first.edges.map { it.copyOf() })
        first.nodes[0] = -999.0
        first.edges[0][0] = -999.0
        val second = cache.lay(request())
        sameGeometry(expected, second)
        second.nodes[1] = -777.0
        second.edges[0][1] = -777.0
        sameGeometry(expected, cache.lay(request()))
        assertEquals(3, cache.stats.requests)
        assertEquals(1, cache.stats.layouts)
        assertEquals(1, cache.stats.entries)
    }

    @Test
    fun everyGeometryFieldAndDirectContentInvalidates() {
        val base = request()
        val graph = base.graph
        val box = graph.boxes.first()
        val pin = box.pins.first()
        fun changeBox(changed: ElkBox) = graph(graph.options, listOf(changed) + graph.boxes.drop(1), graph.lines)
        fun changePin(changed: ElkPin) = changeBox(ElkBox(box.id, box.width, box.height, listOf(changed) + box.pins.drop(1), box.options))
        val changes = listOf(
            request(key = "other"),
            request(group = base.group.copy(label = "changed")),
            request(group = base.group.copy(nodes = base.group.nodes.mapIndexed { i, n -> if (i == 0) n.copy(rows = 2) else n })),
            request(group = base.group.copy(nodes = base.group.nodes.mapIndexed { i, n -> if (i == 0) n.copy(outputs = listOf(PinSpec("renamed", "Other", PortWiring.Connected))) else n })),
            request(graph = graph(graph.options + ("spacing" to "20"), graph.boxes, graph.lines)),
            request(graph = changeBox(ElkBox("renamed", box.width, box.height, box.pins, box.options))),
            request(graph = changeBox(ElkBox(box.id, box.width + 1, box.height, box.pins, box.options))),
            request(graph = changeBox(ElkBox(box.id, box.width, box.height + 1, box.pins, box.options))),
            request(graph = changeBox(ElkBox(box.id, box.width, box.height, box.pins, box.options + ("rank" to "last")))),
            request(graph = changePin(ElkPin("renamed", pin.x, pin.y, pin.east))),
            request(graph = changePin(ElkPin(pin.id, pin.x + 1, pin.y, pin.east))),
            request(graph = changePin(ElkPin(pin.id, pin.x, pin.y + 1, pin.east))),
            request(graph = changePin(ElkPin(pin.id, pin.x, pin.y, !pin.east))),
            request(graph = changeBox(ElkBox(box.id, box.width, box.height, box.pins.reversed(), box.options))),
            request(graph = graph(graph.options, graph.boxes.reversed(), graph.lines)),
            request(graph = graph(graph.options, graph.boxes.drop(1), graph.lines)),
            request(graph = graph(graph.options, graph.boxes, listOf(ElkLine("renamed", "a.out", "b.in")))),
            request(graph = graph(graph.options, graph.boxes, listOf(ElkLine("wire", "b.out", "b.in")))),
            request(graph = graph(graph.options, graph.boxes, listOf(ElkLine("wire", "a.out", "a.in")))),
            request(graph = graph(graph.options, graph.boxes, emptyList())),
            request(graph = graph(graph.options, graph.boxes, graph.lines + ElkLine("other", "b.out", "a.in"))),
        )
        changes.forEachIndexed { index, changed ->
            val cache = FrameLayoutCache(::geometry)
            cache.lay(base)
            cache.lay(changed)
            cache.lay(changed)
            assertEquals(2, cache.stats.layouts, "change $index")
            assertEquals(3, cache.stats.requests, "change $index")
        }
        val reordered = request(graph = graph(linkedMapOf("spacing" to "18", "algorithm" to "layered"), graph.boxes, graph.lines))
        val cache = FrameLayoutCache(::geometry)
        cache.lay(base)
        cache.lay(reordered)
        assertEquals(1, cache.stats.layouts)
    }

    @Test
    fun cachedKeysDoNotShareMutableRequestCollections() {
        val base = request()
        val options = base.graph.options.toMutableMap()
        val boxOptions = base.graph.boxes.first().options.toMutableMap()
        val pins = base.graph.boxes.first().pins.toMutableList()
        val outputs = base.group.nodes.first().outputs.toMutableList()
        val boxes = base.graph.boxes.toMutableList().apply { this[0] = ElkBox("a", 100.0, 50.0, pins, boxOptions) }
        val lines = base.graph.lines.toMutableList()
        val nodes = base.group.nodes.toMutableList().apply { this[0] = first().copy(outputs = outputs) }
        val mutable = request(group = base.group.copy(nodes = nodes), graph = graph(options, boxes, lines))
        val cache = FrameLayoutCache(::geometry)
        cache.lay(mutable)
        options["spacing"] = "90"
        boxOptions["rank"] = "last"
        pins.clear()
        outputs.clear()
        boxes.clear()
        lines.clear()
        nodes.clear()
        cache.lay(base)
        assertEquals(1, cache.stats.layouts)
        cache.lay(mutable)
        assertEquals(2, cache.stats.layouts)
    }

    @Test
    fun failuresAndThrownLayoutsAreRetried() {
        var attempts = 0
        val failed = FrameLayoutCache({ input -> geometry(input).copy(failure = if (attempts++ == 0) IllegalStateException("failed") else null) })
        assertNotNull(failed.lay(request()).failure)
        assertEquals(0, failed.stats.entries)
        assertEquals(null, failed.lay(request()).failure)
        failed.lay(request())
        assertEquals(2, failed.stats.layouts)
        var throws = true
        val throwing = FrameLayoutCache({ input -> if (throws) error("failed") else geometry(input) })
        assertFailsWith<IllegalStateException> { throwing.lay(request()) }
        throws = false
        throwing.lay(request())
        throwing.lay(request())
        assertEquals(2, throwing.stats.layouts)
    }

    @Test
    fun incompleteAndNonFiniteGeometryIsNeverRetained() {
        val good = geometry(request())
        val bad = listOf(good.copy(width = Double.NaN), good.copy(height = -1.0), good.copy(nodes = doubleArrayOf(1.0)),
            good.copy(nodes = doubleArrayOf(Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0)), good.copy(edges = emptyList()),
            good.copy(edges = listOf(doubleArrayOf(0.0, 0.0, 1.0))), good.copy(edges = listOf(doubleArrayOf(0.0, 0.0, Double.NaN, 1.0))))
        bad.forEach { result ->
            val cache = FrameLayoutCache({ result })
            cache.lay(request())
            cache.lay(request())
            assertEquals(2, cache.stats.layouts)
            assertEquals(0, cache.stats.entries)
            assertEquals(0, cache.stats.retainedBytes)
        }
    }

    @Test
    fun retainedWeightIsBoundedAndWarmEntriesAreMostRecent() {
        val probe = FrameLayoutCache(::geometry)
        probe.lay(request(key = "a"))
        val weight = probe.stats.retainedBytes
        assertTrue(weight > 0)
        val cache = FrameLayoutCache(::geometry, capacity = weight * 2)
        cache.lay(request(key = "a"))
        cache.lay(request(key = "b"))
        cache.lay(request(key = "a"))
        cache.lay(request(key = "c"))
        cache.lay(request(key = "a"))
        assertEquals(3, cache.stats.layouts)
        assertEquals(2, cache.stats.entries)
        assertEquals(1, cache.stats.evictions)
        cache.lay(request(key = "b"))
        assertEquals(4, cache.stats.layouts)
        assertTrue(cache.stats.retainedBytes <= weight * 2)
        cache.clear()
        assertEquals(0, cache.stats.retainedBytes)
        assertEquals(0, cache.stats.entries)
        assertEquals(4, cache.stats.evictions)
        val tiny = FrameLayoutCache(::geometry, capacity = weight - 1)
        tiny.lay(request(key = "a"))
        tiny.lay(request(key = "a"))
        assertEquals(2, tiny.stats.layouts)
        assertEquals(0, tiny.stats.retainedBytes)
    }

    @Test
    fun realPlatformDefaultsRetainColdGeometryWithoutChangingResults() {
        assertSame(ElkFrameLayouter, DefaultFrameLayouter)
        listOf(LayeredFrameLayouter to LayeredFrameLayouter.cache, ElkFrameLayouter to ElkFrameLayouter.cache).forEach { (backend, cache) ->
            val plan = BlueprintPlan(listOf(BlueprintGroup("real-cache", "Real", listOf(BlueprintNode("real-a", 0), BlueprintNode("real-b", 1)))),
                listOf(BlueprintEdge("real-wire", "real-a", "real-b")))
            val input = plan.request("real-cache", emptyMap())
            cache.clear()
            val before = cache.stats
            val cold = backend.lay(input)
            val warm = backend.lay(plan.request("real-cache", emptyMap()))
            sameGeometry(cold, warm)
            assertEquals(null, cold.failure)
            assertEquals(2, cache.stats.requests - before.requests)
            assertEquals(1, cache.stats.layouts - before.layouts)
            assertTrue(cache.stats.retainedBytes > 0)
        }
    }

    @Test
    fun oneCardEditLayoutsOnlyItsFrameAndChangedAncestorBounds() {
        listOf(LayeredFrameLayouter, ElkFrameLayouter).forEach { backend ->
            val calls = mutableListOf<String>()
            val cache = FrameLayoutCache({ input -> calls += input.key; backend.lay(input) })
            fun layout(rows: Int): BlueprintLayout<String> {
                val left = BlueprintGroup("cache-left", "Left", listOf(BlueprintNode("cache-a", 0, rows = rows), BlueprintNode("cache-b", 1)))
                val right = BlueprintGroup("cache-right", "Right", listOf(BlueprintNode("cache-c", 0), BlueprintNode("cache-d", 1)))
                val root = BlueprintGroup("cache-parent", "Parent", listOf(BlueprintNode("cache-left", 0, group = left), BlueprintNode("cache-right", 1, group = right)))
                return BlueprintEngine.layout("cache", listOf(root), listOf(BlueprintEdge("ab", "cache-a", "cache-b"), BlueprintEdge("cd", "cache-c", "cache-d")), 1.6, cache)
            }
            val cold = layout(0)
            assertEquals(setOf("cache-left", "cache-right", "cache-parent"), calls.toSet())
            calls.clear()
            val warm = layout(0)
            assertEquals(cold.cards, warm.cards)
            assertTrue(calls.isEmpty())
            val before = cache.stats
            val edited = layout(12)
            assertEquals(listOf("cache-left", "cache-parent"), calls)
            assertEquals(3, cache.stats.requests - before.requests)
            assertEquals(2, cache.stats.layouts - before.layouts)
            assertEquals(cold.cards.keys, edited.cards.keys)
            assertTrue(edited.cards.getValue("cache-a").height > cold.cards.getValue("cache-a").height)
            assertTrue(edited.layoutFailures.isEmpty())
        }
    }

    private fun sameGeometry(expected: FrameResult, actual: FrameResult) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        assertContentEquals(expected.nodes, actual.nodes)
        assertEquals(expected.edges.size, actual.edges.size)
        expected.edges.zip(actual.edges).forEach { (a, b) -> assertContentEquals(a, b) }
    }

    private fun geometry(input: FrameRequest) = FrameResult(300.0, 100.0, DoubleArray(input.graph.boxes.size * 2) { it * 10.0 },
        input.graph.lines.map { doubleArrayOf(0.0, 0.0, 100.0, 50.0) })

    private fun graph(options: Map<String, String>, boxes: List<ElkBox>, lines: List<ElkLine>) = ElkGraph(options, boxes, lines)

    private fun request(key: String = "frame", group: BlueprintGroup? = null, graph: ElkGraph? = null): FrameRequest {
        val nodes = listOf(BlueprintNode("a", 0, outputs = listOf(PinSpec("out", "Flow", PortWiring.Connected))), BlueprintNode("b", 1))
        fun box(id: String) = ElkBox(id, 100.0, 50.0, listOf(ElkPin("$id.out", 100.0, 20.0, true), ElkPin("$id.in", -1.0, 20.0, false)), mapOf("rank" to "first"))
        return FrameRequest(key, group ?: BlueprintGroup("frame", "Frame", nodes),
            graph ?: graph(linkedMapOf("algorithm" to "layered", "spacing" to "18"), listOf(box("a"), box("b")), listOf(ElkLine("wire", "a.out", "b.in"))), emptyList(), emptySet())
    }
}
