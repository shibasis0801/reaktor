package dev.shibasis.reaktor.blueprint.graph

import dev.shibasis.reaktor.blueprint.LinkKind
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.PortWiring
import dev.shibasis.reaktor.graph.core.RouteShape
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.WireShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppGraphRoutesTest {
    private val screens = listOf("circles" to "/circles", "circle" to "/circles/{id}", "chats" to "/chats", "chat" to "/chats/{id}")
    private val navigation = listOf("circles" to "circle", "chats" to "chat", "circles" to "chats", "chats" to "circles")
    private val shape = GraphShape(
        listOf(ScopeShape("app", "App")),
        screens.flatMap { (screen, route) ->
            val exits = navigation.filter { it.first == screen }.map { PortShape(it.second, "NavBinding", false, PortWiring.Connected, 1) }
            listOf(
                NodeShape("$screen-route", "app", "RouteNode", "RouteNode", NodeKind.Route, route = route,
                    ports = exits + listOf(PortShape("routeBinding", "Binding", true, PortWiring.Connected, 1, internal = true), PortShape("navBinding", "NavBinding", true, PortWiring.Connected, 1, internal = true))),
                NodeShape(screen, "app", screen, "Screen", NodeKind.Screen, route = route, attachedTo = "$screen-route",
                    ports = listOf(PortShape("items", "Items", false, PortWiring.Connected, 1))),
            )
        } + NodeShape("store", "app", "Store", "Store", NodeKind.Node, ports = listOf(PortShape("items", "Items", true, PortWiring.Connected, 4))),
        screens.map { (screen, _) -> WireShape(screen, "items", "store", "items", "Items") } +
            navigation.map { (from, to) -> WireShape("$from-route", to, "$to-route", "navBinding", "NavBinding") },
        navigation.map { (from, to) -> RouteShape("$from-route", "$to-route") },
    )

    private fun layout(source: GraphSource) = AppGraphLayout.layout(AppGraph(shape, source), LayoutRequest(Detail.Standard))

    @Test fun `a route sits in its screen's frame and links to it`() {
        val layout = layout(GraphSource.System)
        val frameOf = layout.frames.flatMap { frame -> frame.nodes.map { it to frame.key } }.toMap()
        screens.forEach { (screen, _) ->
            assertEquals(frameOf.getValue(screen), frameOf.getValue("$screen-route"), "$screen-route joins $screen")
            assertTrue(layout.links.any { it.from == "$screen-route" && it.to == screen && it.fromPort == "routeBinding" }, "$screen-route links to $screen")
            assertTrue(layout.cards.getValue("$screen-route").pins.all { it.provides }, "$screen-route lists its exits as pins")
        }
        assertEquals(setOf("feature:circles", "feature:chats", "feature:shared-data"), layout.frames.map { it.key }.toSet())
    }

    @Test fun `navigation runs between screens, never between their routes`() {
        val layout = layout(GraphSource.System)
        val routes = screens.map { "${it.first}-route" }.toSet()
        assertTrue(layout.links.none { it.from in routes && it.to in routes }, "route to route links: ${layout.links.filter { it.from in routes && it.to in routes }}")
        assertEquals(navigation.map { "${it.first}>${it.second}" }.toSet(), layout.links.filter { it.kind == LinkKind.Route }.flatMap { it.members }.toSet())
    }

    @Test fun `the desktop source keeps routes off the map`() {
        val layout = layout(GraphSource.Desktop)
        assertTrue(layout.cards.keys.none { it.endsWith("-route") })
        assertEquals(navigation.map { "${it.first}>${it.second}" }.toSet(), layout.links.filter { it.kind == LinkKind.Route }.flatMap { it.members }.toSet())
    }
}
