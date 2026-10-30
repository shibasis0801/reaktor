package dev.shibasis.reaktor.blueprint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlueprintRoutingTest {
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
