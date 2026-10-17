package dev.shibasis.reaktor.blueprint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlueprintRoutingTest {
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
