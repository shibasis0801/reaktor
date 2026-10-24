package dev.shibasis.reaktor.blueprint

import dev.shibasis.reaktor.graph.core.PortWiring
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlueprintDoorsTest {
    private val pin = PortWiring.Connected
    private fun node(id: String, lane: Int) = BlueprintNode(id, lane, inputs = listOf(PinSpec("in", "T", pin)), outputs = listOf(PinSpec("out", "T", pin)))
    private fun wire(from: String, to: String) = BlueprintEdge("$from>$to", from, to, "out", "in")

    private fun orthogonal(points: List<Pair<Double, Double>>) = points.zipWithNext().all { (a, b) -> abs(a.first - b.first) < 1e-6 || abs(a.second - b.second) < 1e-6 }

    private fun crosses(points: List<Pair<Double, Double>>, card: Card): Boolean {
        val left = card.x + 2; val right = card.x + card.width - 2; val top = card.y + 2; val bottom = card.y + card.height - 2
        return points.zipWithNext().any { (a, b) ->
            min(a.first, b.first) < right && max(a.first, b.first) > left && min(a.second, b.second) < bottom && max(a.second, b.second) > top
        }
    }

    private fun passes(points: List<Pair<Double, Double>>, x: Double, frame: Frame) = points.zipWithNext().any { (a, b) ->
        abs(a.second - b.second) < 1e-6 && a.second > frame.y && a.second < frame.y + frame.height && min(a.first, b.first) <= x + 1e-6 && max(a.first, b.first) >= x - 1e-6
    }

    @Test fun `links between frames leave through the right edge and enter through the left edge`() {
        listOf(LayeredFrameLayouter, ElkFrameLayouter, GridFrameLayouter).forEach { layouter ->
            val groups = listOf(
                BlueprintGroup("a", "A", listOf(node("a1", 0), node("a2", 1), node("a3", 2), node("a4", 1))),
                BlueprintGroup("b", "B", listOf(node("b1", 0), node("b2", 1))),
            )
            val edges = listOf(wire("a1", "a2"), wire("a2", "a3"), wire("a1", "a4"), wire("a1", "b2"), wire("a4", "b1"), wire("b1", "b2"))
            val layout = BlueprintEngine.layout("doors", groups, edges, 1.6, layouter)
            assertTrue(layout.layoutFailures.isEmpty(), "$layouter failed: ${layout.layoutFailures}")
            val a = layout.frames.single { it.key == "a" }
            val b = layout.frames.single { it.key == "b" }
            assertTrue(a.x + a.width < b.x, "$layouter placed the downstream frame first")
            listOf("a1" to "b2", "a4" to "b1").forEach { (from, to) ->
                val link = layout.links.single { "$from>$to" in it.members }
                assertEquals(BlueprintEngine.anchor(layout.cards, from, "out", true), link.points.first())
                assertEquals(BlueprintEngine.anchor(layout.cards, to, "in", false), link.points.last())
                assertTrue(passes(link.points, a.x + a.width, a), "$layouter: $from>$to does not leave through the right edge")
                assertTrue(passes(link.points, b.x, b), "$layouter: $from>$to does not enter through the left edge")
                if (layouter != GridFrameLayouter) {
                    assertTrue(orthogonal(link.points), "$layouter: $from>$to is not orthogonal")
                    assertTrue(layout.cards.values.none { it.id != from && it.id != to && crosses(link.points, it) }, "$layouter: $from>$to crosses a card")
                }
            }
        }
    }

    @Test fun `links leave a nested frame and its parent through both right edges`() {
        listOf(LayeredFrameLayouter, ElkFrameLayouter).forEach { layouter ->
            val inner = BlueprintGroup("inner", "Inner", listOf(node("n1", 0), node("n2", 1)))
            val groups = listOf(
                BlueprintGroup("r", "R", listOf(BlueprintNode("inner", 0, group = inner), node("r1", 1))),
                BlueprintGroup("d", "D", listOf(node("d1", 0))),
            )
            val edges = listOf(wire("n1", "n2"), wire("n1", "d1"), wire("n2", "r1"))
            val layout = BlueprintEngine.layout("nested", groups, edges, 1.6, layouter)
            assertTrue(layout.layoutFailures.isEmpty(), "$layouter failed: ${layout.layoutFailures}")
            val link = layout.links.single { "n1>d1" in it.members }
            val nested = layout.frames.single { it.key == "inner" }
            val root = layout.frames.single { it.key == "r" }
            assertEquals(BlueprintEngine.anchor(layout.cards, "n1", "out", true), link.points.first())
            assertEquals(BlueprintEngine.anchor(layout.cards, "d1", "in", false), link.points.last())
            assertTrue(passes(link.points, nested.x + nested.width, nested), "$layouter: does not leave the nested frame on the right")
            assertTrue(passes(link.points, root.x + root.width, root), "$layouter: does not leave the root frame on the right")
            assertTrue(orthogonal(link.points), "$layouter: not orthogonal")
            assertTrue(layout.cards.values.none { it.id != "n1" && it.id != "d1" && crosses(link.points, it) }, "$layouter: crosses a card")
        }
    }

    @Test fun `a frame whose cards only feed other frames stacks them in one column`() {
        val sessions = BlueprintGroup("sessions", "Sessions", (1..6).map { BlueprintNode("s$it", 0, outputs = listOf(PinSpec("out", "T", pin))) })
        val work = BlueprintGroup("work", "Work", (1..6).map { node("t$it", it % 3) })
        val edges = (1..6).map { BlueprintEdge("s$it>t$it", "s$it", "t$it", "out", "in") } + listOf(wire("t3", "t1"), wire("t1", "t2"))
        val layout = BlueprintEngine.layout("sessions", listOf(sessions, work), edges, 2.4, LayeredFrameLayouter)
        assertEquals(1, (1..6).map { layout.cards.getValue("s$it").x }.distinct().size)
    }

    @Test fun `navigation rides over the map when data wires place the frames`() {
        val panes = (1..5).map { "p$it" }
        val groups = listOf(BlueprintGroup("shared", "Shared", listOf(node("s", 0)))) + panes.map { BlueprintGroup(it, it, listOf(node("${it}n", 1))) }
        val routes = panes.flatMap { a -> panes.filter { it != a }.map { b -> BlueprintEdge("$a>$b", "${a}n", "${b}n", kind = LinkKind.Route) } }
        val layout = BlueprintEngine.layout("switcher", groups, panes.map { wire("s", "${it}n") } + routes, 16.0 / 9.0, LayeredFrameLayouter)
        val shared = layout.frames.single { it.key == "shared" }
        val placed = layout.frames.filter { it.key in panes }
        assertTrue(placed.all { it.x > shared.x + shared.width }, "Every pane sits downstream of the data it reads")
        assertTrue(placed.map { it.x }.distinct().size <= 2, "Panes that switch between each other share a column: ${placed.map { it.x }}")
        assertTrue(layout.links.filter { it.kind == LinkKind.Route }.let { it.size == routes.size && it.all(Link::curve) })
    }

    @Test fun `navigation places the frames when no data wire crosses them`() {
        val groups = listOf("a", "b", "c").map { BlueprintGroup(it, it, listOf(node("${it}1", 0))) }
        val routes = listOf(BlueprintEdge("a>b", "a1", "b1", kind = LinkKind.Route), BlueprintEdge("b>c", "b1", "c1", kind = LinkKind.Route))
        val layout = BlueprintEngine.layout("storyboard", groups, routes, 16.0 / 9.0, LayeredFrameLayouter)
        val (a, b, c) = listOf("a", "b", "c").map { key -> layout.frames.single { it.key == key } }
        assertTrue(a.x + a.width < b.x && b.x + b.width < c.x, "Screens follow their taps from left to right")
        assertTrue(layout.links.none(Link::curve), "Taps go through the frame doors")
    }
}
