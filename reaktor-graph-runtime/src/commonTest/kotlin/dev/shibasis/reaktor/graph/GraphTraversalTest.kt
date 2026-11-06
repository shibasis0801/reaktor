package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.*
import dev.shibasis.reaktor.graph.core.node.*
import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import dev.shibasis.reaktor.portgraph.port.registerProvider
import dev.shibasis.reaktor.portgraph.traversal.*
import kotlin.test.*

class GraphTraversalTest {
    private class Offers(parent: Graph) : Graph(parent, label = "offers") {
        val incoming = input<String>()
        val outgoing by provides(registerProvider("outgoing", 42))
    }

    @Test fun containsIncludesGraphPortsAndNestedOwners() {
        Reaktor.web()
        val root = Graph(label = "root")
        try {
            root.provide("incoming")
            val offers = root.Graph(::Offers)
            val workflow = root.Node { Workflow(it, "work", "workflow") }
            val child = Graph(root, label = "container-child")
            val container = root.Node { ContainerNode(it, "/container", arrayListOf(child)) }
            val steps = root.depthFirst(Contains).filterIsInstance<Step.Enter>().toList()
            offers.providerPorts.flattenedValues().forEach { port -> assertSame(offers, steps.single { it.element === port }.parent) }
            offers.consumerPorts.flattenedValues().forEach { port -> assertSame(offers, steps.single { it.element === port }.parent) }
            assertTrue(steps.any { it.element === offers.incoming })
            assertTrue(steps.any { it.element === offers.outgoing })
            assertSame(workflow, steps.single { it.element === workflow.childGraph }.parent)
            assertSame(container, steps.single { it.element === container.route }.parent)
            assertSame(container, steps.single { it.element === child }.parent)
        } finally { root.close() }
    }

    private fun Graph.oldChildren(): List<Graph> = (owned + nodes.filterIsInstance<ContainerNode>().flatMap { it.graphs }).distinct()

    private fun Graph.oldTree(into: MutableSet<Graph> = linkedSetOf()): Set<Graph> {
        if (into.add(this)) oldChildren().forEach { it.oldTree(into) }
        return into
    }

    @Test fun treeAndChildrenKeepOldDeclarationOrder() {
        Reaktor.web()
        val root = Graph(label = "root")
        try {
            root.Graph { Graph(it, label = "first") }.Graph { Graph(it, label = "nested") }
            root.Node { Workflow(it, "work", "workflow") }
            val child = root.Graph { Graph(it, label = "container-child") }
            root.Node { ContainerNode(it, "/container", arrayListOf(child)) }
            root.Graph { Graph(it, label = "last") }
            assertEquals(root.oldTree().toList(), root.tree().toList())
            root.oldTree().forEach { assertEquals(it.oldChildren(), it.children(), it.label) }
        } finally { root.close() }
    }

    @Test fun shapeKeepsOwnershipSortingAndDormantContainer() {
        Reaktor.web()
        val root = Graph(label = "root")
        try {
            val workflow = root.Node { Workflow(it, "work", "workflow") }
            workflow.childGraph.Node { object : BasicNode(it) { override val label = "zebra" } }
            workflow.childGraph.Node { object : BasicNode(it) { override val label = "alpha" } }
            val child = Graph(root, label = "container-child")
            val saved = GraphShape(listOf(ScopeShape("saved", "saved")), emptyList(), emptyList(), emptyList())
            val container = root.Node { object : ContainerNode(it, "/container", arrayListOf(child)) { override val dormant = saved } }
            val shape = root.shape()
            val workScope = shape.scopes.single { it.id == workflow.childGraph.id.toString() }
            assertEquals(workflow.id.toString(), workScope.container)
            assertEquals(root.id.toString(), workScope.parent)
            assertEquals(container.id.toString(), shape.scopes.single { it.id == child.id.toString() }.container)
            val labels = shape.nodes.filter { it.scope == workScope.id }.map { it.label }
            assertEquals(labels.sorted(), labels)
            val dormant = shape.scopes.single { it.id == "saved" }
            assertEquals(container.id.toString(), dormant.container)
            assertEquals(root.id.toString(), dormant.parent)
            assertFalse(dormant.active)
        } finally { root.close() }
    }

    class Home : RouteBinding<Payload>(Payload()) { val next by exit<Next>() }
    class Next : RouteBinding<Payload>(Payload())
    class Isolated : RouteBinding<Payload>(Payload())

    @Test fun coverageMatchesOldNavigationReachability() {
        Reaktor.web()
        val graph = Graph(label = "routes")
        try {
            val home = graph.Root("/", ::Home) { BasicNode(it) }
            graph.Route("/next", ::Next) { BasicNode(it) }
            graph.Route("/isolated", ::Isolated) { BasicNode(it) }
            graph.wire()
            val reached = mutableSetOf<RouteNode<*, *>>()
            val frontier = ArrayDeque<RouteNode<*, *>>(listOf(home))
            while (frontier.isNotEmpty()) {
                val route = frontier.removeFirst()
                if (reached.add(route)) frontier.addAll(route.navigationTargets())
            }
            assertEquals(reached, home.breadthFirst(Exits).map { it.element }.toSet())
            val coverage = graph.coverage(home)
            coverage.routes.forEach { route -> assertEquals(reached.any { it.pattern.original == route.pattern }, route.reachable, route.pattern) }
            assertFalse(coverage.routes.single { it.pattern == "/isolated" }.reachable)
        } finally { graph.close() }
    }
}
