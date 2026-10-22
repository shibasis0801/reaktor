package dev.shibasis.reaktor.blueprint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
        val layout = BlueprintEngine.layout("lanes", listOf(BlueprintGroup("lanes", "Lanes", nodes)), listOf(BlueprintEdge("back", "late", "early")), 1.6, ElkFrameLayouter)
        assertTrue(layout.layoutFailures.isEmpty())
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
        val graph = ElkGraph(emptyMap(), listOf(ElkBox("n0", 272.0, 46.0, listOf(ElkPin("n0w", -1.0, 23.0, false)), emptyMap())), listOf(ElkLine("e0", "missing", "n0w")))
        val grid = ElkFrameLayouter.lay(FrameRequest("bad", BlueprintGroup("bad", "Bad", listOf(BlueprintNode("node", 0))), graph, emptyList(), emptySet()))
        assertNotNull(grid.failure)
        assertEquals(2, grid.nodes.size)
        assertTrue(grid.edges.single().isEmpty())
    }
}
