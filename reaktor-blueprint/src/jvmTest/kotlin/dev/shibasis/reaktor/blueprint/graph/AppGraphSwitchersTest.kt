package dev.shibasis.reaktor.blueprint.graph

import dev.shibasis.reaktor.blueprint.LinkKind
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.RouteShape
import dev.shibasis.reaktor.graph.core.ScopeShape
import kotlin.test.Test
import kotlin.test.assertEquals

class AppGraphSwitchersTest {
    private val tabs = (0 until 5).map { "tab$it" }
    private val screens = tabs + "detail"
    private val graph = AppGraph(GraphShape(
        listOf(ScopeShape("app", "App")),
        screens.flatMap { screen ->
            listOf(
                NodeShape("$screen-route", "app", "/$screen", "Route", NodeKind.Route),
                NodeShape(screen, "app", screen, "Screen", NodeKind.Screen, route = "/$screen", attachedTo = "$screen-route"),
            )
        },
        emptyList(),
        tabs.flatMap { a -> tabs.filter { it != a }.map { b -> RouteShape("$a-route", "$b-route") } } +
            listOf(RouteShape("tab0-route", "detail-route"), RouteShape("detail-route", "tab0-route")),
    ))

    @Test fun `screens that all open each other form one switcher`() {
        assertEquals(listOf(tabs.toSet()), AppGraphFeatures.switchers(graph))
    }

    @Test fun `the map leaves out a switcher's links and keeps the rest of the navigation`() {
        val routes = AppGraphLayout.layout(graph, LayoutRequest(Detail.Standard)).links.filter { it.kind == LinkKind.Route }
        assertEquals(setOf("tab0>detail", "detail>tab0"), routes.flatMap { it.members }.toSet())
    }
}
