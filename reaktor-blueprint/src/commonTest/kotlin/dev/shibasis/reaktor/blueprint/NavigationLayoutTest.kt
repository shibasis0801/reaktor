package dev.shibasis.reaktor.blueprint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavigationLayoutTest {
    @Test fun disconnectedScreensPackTogetherAndObservationsDoNotMoveThem() {
        val groups = listOf(BlueprintGroup("app", "App", listOf(BlueprintNode("root", 0, rows = 30)) +
            (1..17).map { BlueprintNode("screen-$it", 0, rows = 30) }))
        val base = BlueprintEngine.navigationLayout("app", groups, emptyList(), "root", 1.6)
        assertTrue(base.width < BlueprintEngine.CardWidth * 13, "Disconnected screens became a horizontal strip: ${base.width}")
        assertTrue(base.width / base.height in .8..3.0, "Mobile cards must pack for the viewport: ${base.width} × ${base.height}")
        val seen = BlueprintEngine.navigationLayout("app", groups, listOf(BlueprintEdge("observed", "screen-1", "screen-17")), "root", 1.6, ordering = emptySet(), outlets = mapOf("observed" to (.8 to .7)))
        assertEquals(base.cards, seen.cards, "Observing a button must not move any screen")
        val cards = base.cards.values.toList()
        cards.forEachIndexed { i, a -> cards.drop(i + 1).forEach { b ->
            assertTrue(a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y, "${a.id} overlaps ${b.id}")
        } }
    }

    @Test fun realContainersPackTallScreensWithoutStretchingTheApp() {
        val groups = listOf(17, 7, 6, 5, 4).mapIndexed { group, count ->
            BlueprintGroup("g$group", "Container $group", (0 until count).map { BlueprintNode("$group:$it", 0, rows = 32) })
        }
        val edges = (0..3).map { BlueprintEdge("exit-$it", "$it:0", "${it + 1}:0") }
        val layout = BlueprintEngine.navigationLayout("app", groups, edges, "0:0", 1.3)
        assertTrue(layout.width / layout.height in .65..2.5, "Container packing became a strip: ${layout.width} × ${layout.height}")
        assertEquals(5, layout.frames.size)
    }

    @Test fun tabsShareTheirContainerAndRemainIndividuallyFocusable() {
        val children = (0..3).map { tab -> BlueprintGroup("tab$tab", "Tab $tab", (0..5).map { BlueprintNode("$tab:$it", 0, rows = 32) }) }
        val home = BlueprintGroup("home", "Home", children.map { BlueprintNode(it.key, 0, group = it) })
        val login = BlueprintGroup("identity", "Sign in", listOf(BlueprintNode("root", 0, rows = 32)))
        val edges = listOf(BlueprintEdge("sign-in", "root", "0:0"), BlueprintEdge("switch-tab", "0:0", "1:0"))
        val layout = BlueprintEngine.navigationLayout("app", listOf(login, home), edges, "root", 1.3)
        assertEquals(24, layout.frames.single { it.key == "home" }.nodes.size)
        assertEquals(4, layout.frames.count { it.parent == "home" })
        assertEquals(layout.cards, layout.navigationFocus("tab0").cards)
        assertEquals(layout.cards.values.minOf { it.x }, layout.cards.getValue("root").x)
    }

    @Test fun rootLeadsTheFlowAndReturnEdgesAvoidEveryCard() {
        val groups = listOf(
            BlueprintGroup("session", "Signed out", listOf(BlueprintNode("root", 0, rows = 30), BlueprintNode("welcome", 0, rows = 30))),
            BlueprintGroup("home", "Home", listOf(BlueprintNode("circles", 0, rows = 30), BlueprintNode("plans", 0, rows = 30))),
            BlueprintGroup("detail", "Circle", listOf(BlueprintNode("chat", 0, rows = 30), BlueprintNode("people", 0, rows = 30))),
        )
        val pairs = listOf("root" to "welcome", "welcome" to "circles", "circles" to "plans", "circles" to "chat", "chat" to "people", "people" to "circles", "plans" to "root")
        val edges = pairs.mapIndexed { index, (from, to) -> BlueprintEdge("exit-$index", from, to, kind = LinkKind.Route) }
        val layout = BlueprintEngine.navigationLayout("app", groups, edges, "root", 1.6)
        assertEquals(edges.size, layout.links.size)
        assertEquals(layout.cards.values.minOf { it.x }, layout.cards.getValue("root").x)
        layout.links.forEach { link -> link.points.zipWithNext().forEach { (a, b) ->
            assertTrue(a.first == b.first || a.second == b.second, "${link.id} must be orthogonal")
            layout.cards.values.forEach { card ->
                val crosses = if (a.first == b.first) a.first > card.x + .01 && a.first < card.x + card.width - .01 && maxOf(a.second, b.second) > card.y + .01 && minOf(a.second, b.second) < card.y + card.height - .01
                else a.second > card.y + .01 && a.second < card.y + card.height - .01 && maxOf(a.first, b.first) > card.x + .01 && minOf(a.first, b.first) < card.x + card.width - .01
                assertTrue(!crosses, "${link.id} crosses ${card.id}: $a → $b")
            }
        } }
    }
}
