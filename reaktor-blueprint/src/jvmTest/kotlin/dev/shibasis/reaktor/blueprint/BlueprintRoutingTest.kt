package dev.shibasis.reaktor.blueprint

import dev.shibasis.reaktor.blueprint.graph.AppGraph
import dev.shibasis.reaktor.blueprint.graph.AppGraphLayout
import dev.shibasis.reaktor.blueprint.graph.Detail
import dev.shibasis.reaktor.blueprint.graph.LayoutRequest
import dev.shibasis.reaktor.graph.core.GraphShape
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlueprintRoutingTest {
    @Test fun `connected peers retain the obstacle-free paths from their layouter`() {
        val nodes = (0 until 30).map { BlueprintNode("source$it", 0) } + BlueprintNode("target", 0)
        val edges = (0 until 30).map { BlueprintEdge("e$it", "source$it", "target", kind = LinkKind.Wire) }
        for (layouter in listOf(DefaultFrameLayouter, LayeredFrameLayouter)) {
            val scene = BlueprintEngine.layout("connected", listOf(BlueprintGroup("group", "Group", nodes)), edges, aspect = 16.0 / 9.0, layouter = layouter)
            assertTrue(scene.layoutFailures.isEmpty())
            assertEquals(edges.size, scene.links.sumOf { it.members.size })
            assertEquals(nodes.size, scene.cards.size)
            scene.links.forEach { link ->
                assertFalse(link.curve)
                assertTrue(link.points.zipWithNext().all { (a, b) -> abs(a.first - b.first) < 1e-6 || abs(a.second - b.second) < 1e-6 })
                assertFalse(scene.cards.values.any { card -> card.id != link.from && card.id != link.to && link.trace.zipWithNext().any { (a, b) -> crosses(a, b, card) } })
            }
        }
    }

    @Test fun navigationBetweenRegionsDrawsAsCurvesPortToPort() {
        for (layouter in listOf(LayeredFrameLayouter, ElkFrameLayouter)) {
            val peers = (0 until 6).map { "peer-$it" }
            val groups = listOf(BlueprintGroup("shared", "Shared", listOf(BlueprintNode("source", 0)))) + peers.map { id ->
                val inner = BlueprintGroup("$id-inner", id, listOf(BlueprintNode("$id-a", 0), BlueprintNode("$id-b", 1)))
                BlueprintGroup(id, id, listOf(BlueprintNode(inner.key, 0, group = inner)))
            }
            val edges = peers.flatMap { id -> listOf(BlueprintEdge("source-$id", "source", "$id-a"),
                BlueprintEdge("inside-$id", "$id-a", "$id-b")) } + peers.flatMap { from -> peers.filter { it != from }.flatMap { to ->
                listOf("a", "b").map { member -> BlueprintEdge("$from-$to-$member", "$from-$member", "$to-$member", kind = LinkKind.Route) }
            } }
            val scene = BlueprintEngine.layout("navigation-overlay", groups, edges, 16.0 / 9.0, layouter)
            assertTrue(scene.layoutFailures.isEmpty())
            assertEquals(edges.map { it.id }.sorted(), scene.links.flatMap { it.members }.sorted())
            val navigation = scene.links.filter { it.kind == LinkKind.Route }
            val crossing = navigation.filter { link -> scene.frames.none { it.parent == null && link.from in it.nodes && link.to in it.nodes } }
            assertTrue(crossing.isNotEmpty() && crossing.all { it.curve }, "Navigation between regions draws as curves")
            assertTrue(crossing.all { link -> link.trace.first() != link.trace.last() })
            val repeated = BlueprintEngine.layout("navigation-overlay", groups, edges, 16.0 / 9.0, layouter)
            val selected = navigation.first().members.first()
            assertEquals(navigation.single { selected in it.members }.id, repeated.links.single { selected in it.members }.id)
            assertEquals(scene.links.map { it.id to it.members }, repeated.links.map { it.id to it.members })
        }
    }

    @Test fun `Atlas retains every orthogonal path around every card`() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/blueprint-one/atlas-expanded.json")).use { it.readBytes() }
        val shape = Json { ignoreUnknownKeys = true }.decodeFromString(GraphShape.serializer(), bytes.decodeToString())
        val scene = AppGraphLayout.layout(AppGraph(shape), LayoutRequest(Detail.Standard))
        assertTrue(scene.layoutFailures.isEmpty())
        assertEquals(3239, scene.cards.size)
        assertEquals(8959, scene.links.sumOf { it.members.size })
        assertEquals(0, scene.links.count { it.curve })
        val blocked = scene.links.sumOf { link ->
            val segments = link.points.zipWithNext()
            scene.cards.values.count { card -> card.id != link.from && card.id != link.to && segments.any { (a, b) -> crosses(a, b, card) } }
        }
        assertEquals(0, blocked)
    }

    private fun crosses(a: Pair<Double, Double>, b: Pair<Double, Double>, card: Card): Boolean {
        if (abs(a.second - b.second) < 1e-6) return a.second > card.y + .01 && a.second < card.y + card.height - .01 &&
            maxOf(a.first, b.first) > card.x + .01 && minOf(a.first, b.first) < card.x + card.width - .01
        if (abs(a.first - b.first) < 1e-6) return a.first > card.x + .01 && a.first < card.x + card.width - .01 &&
            maxOf(a.second, b.second) > card.y + .01 && minOf(a.second, b.second) < card.y + card.height - .01
        var enter = 0.0
        var leave = 1.0
        fun clip(start: Double, delta: Double, low: Double, high: Double): Boolean {
            if (abs(delta) < 1e-9) return start > low && start < high
            val first = (low - start) / delta
            val last = (high - start) / delta
            enter = maxOf(enter, minOf(first, last))
            leave = minOf(leave, maxOf(first, last))
            return enter < leave
        }
        return clip(a.first, b.first - a.first, card.x + .01, card.x + card.width - .01) &&
            clip(a.second, b.second - a.second, card.y + .01, card.y + card.height - .01) && enter < leave
    }

    @Test fun `wrapping balances real card heights and keeps every node separate`() {
        listOf(24, 100).forEach { count ->
            val nodes = (0 until count).map { BlueprintNode("n-$it", 0, rows = if (it < count / 2) 20 else 0) }
            val layout = BlueprintEngine.layout("mixed", listOf(BlueprintGroup("mixed", "Mixed heights", nodes)), emptyList(), 16.0 / 9.0, GridFrameLayouter)
            assertEquals(nodes.map { it.id }.toSet(), layout.cards.keys)
            val frame = layout.frames.single()
            assertTrue(frame.height <= frame.width * 1.5 && frame.width <= frame.height * 4, "${frame.width}x${frame.height}")
            val cards = layout.cards.values.toList()
            cards.forEachIndexed { index, a ->
                cards.take(index).forEach { b ->
                    assertTrue(a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y, "${a.id} overlaps ${b.id}")
                }
            }
        }
    }

    @Test fun `disconnected cards fill space beside a tall connected core and below a wide core`() {
        listOf(272.0 to 1800.0, 1800.0 to 272.0).forEach { (width, height) ->
            val boxes = listOf(
                ElkBox("core", width, height, listOf(ElkPin("out", width, height / 2, true)), emptyMap()),
                ElkBox("door", 4.0, 4.0, listOf(ElkPin("in", 0.0, 2.0, false)), emptyMap()),
            ) + (0 until 24).map { ElkBox("loose-$it", 272.0, 46.0, emptyList(), emptyMap()) }
            val points = doubleArrayOf(width, height / 2, width + 28, height / 2)
            val positions = DoubleArray(boxes.size * 2)
            positions[2] = width + 28
            positions[3] = height / 2 - 2
            (2 until boxes.size).forEach { positions[it * 2 + 1] = height + (it - 1) * 64 }
            val request = FrameRequest("shared", BlueprintGroup("shared", "Shared", emptyList()),
                ElkGraph(emptyMap(), boxes, listOf(ElkLine("wire", "out", "in"))),
                emptyList(), emptySet(), listOf(FrameDoor("next", true, 1)))
            val gathered = BlueprintEngine.gather(request,
                FrameResult(width + 32, positions.last() + 46, positions, listOf(points)))
            assertTrue(gathered.width / gathered.height in 2.0 / 3.0..4.0, "${gathered.width}x${gathered.height}")
            assertEquals(positions.take(4), gathered.nodes.take(4), "The connected core and its door keep their geometry")
            assertEquals(points.toList(), gathered.edges.single().toList())
            val corridor = listOf(width + 28 to height / 2, gathered.width to height / 2)
            assertFalse((2 until boxes.size).any { at ->
                val box = boxes[at]
                crosses(corridor[0], corridor[1], Card(box.id, gathered.nodes[at * 2], gathered.nodes[at * 2 + 1], box.width, box.height, emptyList(), 0))
            })
            boxes.forEachIndexed { at, box ->
                val x = gathered.nodes[at * 2]
                val y = gathered.nodes[at * 2 + 1]
                assertTrue(x >= 0 && y >= 0 && x + box.width <= gathered.width && y + box.height <= gathered.height)
                boxes.take(at).forEachIndexed { other, prior ->
                    val px = gathered.nodes[other * 2]
                    val py = gathered.nodes[other * 2 + 1]
                    assertTrue(x + box.width <= px || px + prior.width <= x || y + box.height <= py || py + prior.height <= y,
                        "${box.id} overlaps ${prior.id}")
                }
            }
        }
    }

    @Test fun `loose cards stay outside the routed extent of a connected component`() {
        val boxes = listOf(
            ElkBox("core", 272.0, 1800.0, listOf(ElkPin("out", 272.0, 900.0, true)), emptyMap()),
            ElkBox("door", 4.0, 4.0, listOf(ElkPin("in", 0.0, 2.0, false)), emptyMap()),
        ) + (0 until 24).map { ElkBox("loose-$it", 272.0, 46.0, emptyList(), emptyMap()) }
        val positions = DoubleArray(boxes.size * 2)
        positions[2] = 300.0; positions[3] = 898.0
        (2 until boxes.size).forEach { positions[it * 2 + 1] = 1800.0 + (it - 1) * 64 }
        val points = doubleArrayOf(272.0, 900.0, 600.0, 900.0, 600.0, 1700.0, 300.0, 1700.0, 300.0, 900.0)
        val request = FrameRequest("shared", BlueprintGroup("shared", "Shared", emptyList()),
            ElkGraph(emptyMap(), boxes, listOf(ElkLine("wire", "out", "in"))),
            emptyList(), emptySet(), listOf(FrameDoor("next", true, 1)))
        val result = BlueprintEngine.gather(request, FrameResult(650.0, positions.last() + 46, positions, listOf(points)))
        val path = BlueprintEngine.packed(result.edges.single(), false)
        val blocked = (2 until boxes.size).count { at ->
            val box = boxes[at]
            val card = Card(box.id, result.nodes[at * 2], result.nodes[at * 2 + 1], box.width, box.height, emptyList(), 0)
            path.zipWithNext().any { (a, b) -> crosses(a, b, card) }
        }
        assertEquals(0, blocked)
        assertEquals(positions.take(4), result.nodes.take(4))
    }

    @Test fun `curves preserve their sample geometry and endpoints`() {
        val from = 12.0 to 34.0
        val to = -20.0 to 120.0
        val points = BlueprintEngine.curve(from, to)
        assertEquals(25, points.size)
        assertEquals(from, points.first())
        assertEquals(to, points.last())
        assertEquals(-4.0 to 77.0, points[12])
        assertTrue(points.all { it.first.isFinite() && it.second.isFinite() })
    }

    @Test fun `wrapping and frame packing preserve every wire and its translated anchors`() {
        val groups = (0..2).map { group ->
            BlueprintGroup("group-$group", "Group $group", (0..31).map { node -> BlueprintNode("$group-$node", 0) })
        }
        val edges = groups.flatMapIndexed { group, _ ->
            (0..30).map { node -> BlueprintEdge("within-$group-$node", "$group-$node", "$group-${node + 1}") }
        } + listOf(BlueprintEdge("between", "0-0", "2-31"))
        val layout = BlueprintEngine.layout("fixture", groups, edges, 16.0 / 9.0, GridFrameLayouter)
        assertEquals(groups.flatMap { it.nodes }.map { it.id }.toSet(), layout.cards.keys)
        assertEquals(edges.map { it.id }.toSet(), layout.links.flatMap { it.members }.toSet())
        layout.links.forEach { link ->
            assertEquals(BlueprintEngine.anchor(layout.cards, link.from, link.fromPort, true), link.points.first())
            assertEquals(BlueprintEngine.anchor(layout.cards, link.to, link.toPort, false), link.points.last())
            assertTrue(link.points.all { it.first.isFinite() && it.second.isFinite() })
        }
    }
}
