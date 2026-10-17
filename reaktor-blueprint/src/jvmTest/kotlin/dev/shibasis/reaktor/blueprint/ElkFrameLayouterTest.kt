package dev.shibasis.reaktor.blueprint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ElkFrameLayouterTest {
    @Test
    fun auditedFrameAndLargeIslandSystemSurviveRepeatedLayout() {
        val fixture = checkNotNull(javaClass.getResource("/elk-failing-frame.txt")).readText().lineSequence().map { it.split(' ') }.toList()
        val nodes = fixture.filter { it.first() == "node" }.map { BlueprintNode(it[1], it[2].toInt()) }
        val edges = fixture.filter { it.first() == "edge" }.mapIndexed { index, words -> BlueprintEdge("wire-$index", words[1], words[3]) }
        val failing = BlueprintGroup("audit", "Audit", nodes)
        val islands = (0 until 8).map { island -> BlueprintGroup("island-$island", "Island $island", (0 until 60).map { BlueprintNode("$island-$it", it % 3) }) }
        val islandEdges = islands.flatMap { group -> group.nodes.mapIndexed { index, node -> BlueprintEdge("${node.id}-wire", node.id, group.nodes[(index + 7) % 60].id) } }
        repeat(100) {
            for ((groups, wires) in listOf(listOf(failing) to edges, islands to islandEdges)) {
                val layout = BlueprintEngine.layout(it, groups, wires, 1.6)
                assertTrue(layout.layoutFailures.isEmpty(), "Normal graph shapes must use ELK without fallback")
                assertEquals(groups.sumOf { it.nodes.size }, layout.cards.size)
                assertTrue(layout.width.isFinite() && layout.height.isFinite())
                assertTrue(layout.cards.values.all { card -> card.x.isFinite() && card.y.isFinite() })
                assertTrue(layout.links.all { link -> link.points.isNotEmpty() && link.points.all { point -> point.first.isFinite() && point.second.isFinite() } })
            }
        }
    }

    @Test
    fun backwardsLaneRoutingPreservesSemanticEndpointsAndArrowDirection() {
        val nodes = listOf(BlueprintNode("early", 0), BlueprintNode("late", 2))
        val edge = FrameEdge("back", "late", "early", null, null, LinkKind.Wire, false, listOf("back"))
        val layout = ElkFrameLayouter.lay(BlueprintGroup("lanes", "Lanes", nodes), nodes.associate { it.id to emptyList<Pin>() }, listOf(edge))
        assertNull(layout.failure)
        val link = layout.links.single()
        assertEquals("late", link.from)
        assertEquals("early", link.to)
        assertEquals(false, link.reversed)
        val from = layout.cards.getValue("late")
        val to = layout.cards.getValue("early")
        assertTrue(from.x > to.x)
        assertEquals(from.x + from.width, link.points.first().first, 1.0)
        assertEquals(to.x, link.points.last().first, 1.0)
    }

    @Test
    fun invalidElkPinFallsBackAndCarriesFailureEvidence() {
        val node = BlueprintNode("node", 0)
        val grid = ElkFrameLayouter.lay(BlueprintGroup("bad", "Bad", listOf(node)), mapOf("node" to emptyList()), listOf(FrameEdge("bad", "missing", "node", null, null, LinkKind.Wire, false, emptyList())))
        assertNotNull(grid.failure)
        assertEquals(setOf("node"), grid.cards.keys)
        assertTrue(grid.links.isEmpty())
    }
}
