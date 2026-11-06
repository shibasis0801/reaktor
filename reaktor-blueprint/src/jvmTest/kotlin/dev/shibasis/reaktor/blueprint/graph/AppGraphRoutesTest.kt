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

    @Test fun declaredPortsNamedLikeNavigationBindingsKeepTheirRawIdentifiersAndWires() {
        val keys = listOf("id", "routeBinding", "navBinding")
        val parent = NodeShape("parent", "catalog", "parent", "Table", NodeKind.Store,
            ports = keys.map { PortShape(it, "integer", true, PortWiring.Connected) })
        val child = NodeShape("child", "catalog", "child", "Table", NodeKind.Store,
            ports = keys.map { PortShape(it, "integer", false, PortWiring.Connected) })
        val endpoints = listOf("routeBinding" to "id", "id" to "navBinding", "navBinding" to "routeBinding")
        val wires = endpoints.mapIndexed { index, (consumer, provider) ->
            WireShape(child.id, consumer, parent.id, provider, "foreign-key", sourceId = "constraint-$index")
        }
        val declared = AppGraph(GraphShape(listOf(ScopeShape("catalog", "Catalog")), listOf(parent, child), wires, emptyList()),
            GraphSource.Declared("schema", "Schema"))
        assertEquals(wires.map { it.sourceId }, declared.wires.map { it.id })
        assertEquals(endpoints, declared.wires.map { it.consumerPort to it.providerPort })
        assertEquals(keys, declared.ports(parent).map { it.key })
        assertEquals(keys, declared.ports(child).map { it.key })
        assertEquals(wires.size, declared.wiresOut(parent.id).size)
        assertEquals(wires.size, declared.wiresIn(child.id).size)
    }

    @Test fun realInternalNavigationBindingsStayHiddenOutsideTelemetrySources() {
        val provider = NodeShape("provider", "app", "Provider", "Node", NodeKind.Node,
            ports = listOf(PortShape("navBinding", "NavBinding", true, PortWiring.Connected, internal = true)))
        val consumer = NodeShape("consumer", "app", "Consumer", "Screen", NodeKind.Screen,
            ports = listOf(PortShape("routeBinding", "NavBinding", false, PortWiring.Connected, internal = true)))
        val binding = WireShape(consumer.id, "routeBinding", provider.id, "navBinding", "NavBinding", sourceId = "binding")
        val bound = GraphShape(listOf(ScopeShape("app", "App")), listOf(provider, consumer), listOf(binding), emptyList())
        for (source in listOf(GraphSource.Desktop, GraphSource.Declared("declared", "Declared"), GraphSource.Build, GraphSource.Code)) {
            val graph = AppGraph(bound, source)
            assertTrue(graph.wires.isEmpty(), source.id)
            assertTrue(graph.ports(provider).isEmpty(), source.id)
            assertTrue(graph.ports(consumer).isEmpty(), source.id)
        }
        for (source in listOf(GraphSource.System, GraphSource.Revision("revision", "Revision"))) {
            val graph = AppGraph(bound, source)
            assertEquals(listOf("binding"), graph.wires.map { it.id }, source.id)
            assertEquals(provider.ports, graph.ports(provider))
            assertEquals(consumer.ports, graph.ports(consumer))
        }
    }

    @Test fun internalMetadataOnTheOppositePortDirectionDoesNotHideAPublicWire() {
        val provider = NodeShape("provider", "app", "Provider", "Node", NodeKind.Node, ports = listOf(
            PortShape("navBinding", "NavBinding", true, PortWiring.Connected),
            PortShape("navBinding", "NavBinding", false, PortWiring.Unused, internal = true)))
        val consumer = NodeShape("consumer", "app", "Consumer", "Node", NodeKind.Node, ports = listOf(
            PortShape("routeBinding", "NavBinding", false, PortWiring.Connected),
            PortShape("routeBinding", "NavBinding", true, PortWiring.Unused, internal = true)))
        val bound = GraphShape(listOf(ScopeShape("app", "App")), listOf(provider, consumer),
            listOf(WireShape(consumer.id, "routeBinding", provider.id, "navBinding", "NavBinding", sourceId = "public-binding")), emptyList())
        val graph = AppGraph(bound, GraphSource.Declared("declared", "Declared"))
        assertEquals(listOf("public-binding"), graph.wires.map { it.id })
        assertEquals(listOf(true), graph.ports(provider).map { it.provides })
        assertEquals(listOf(false), graph.ports(consumer).map { it.provides })
    }

    @Test fun `the desktop source keeps routes off the map`() {
        val layout = layout(GraphSource.Desktop)
        assertTrue(layout.cards.keys.none { it.endsWith("-route") })
        assertEquals(navigation.map { "${it.first}>${it.second}" }.toSet(), layout.links.filter { it.kind == LinkKind.Route }.flatMap { it.members }.toSet())
    }
}
