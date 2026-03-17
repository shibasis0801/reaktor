package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.capabilities.Lifecycle
import dev.shibasis.reaktor.graph.core.autoWire
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.requireFullyWired
import dev.shibasis.reaktor.graph.core.unconnectedConsumers
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.RouteBinding
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.graph.di.DependencyAdapter
import dev.shibasis.reaktor.graph.di.DependencyScopeCapability
import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.graph.navigation.Push
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GraphLifecycleIntegrationTest {
    @Test
    fun attachAndDetachRunLifecycleAndCloseNode() {
        val graph = Graph(dependencyAdapter = TestDependencyAdapter())
        val node = RecordingNode(graph)

        assertTrue(graph.attach(node))
        assertEquals(listOf(Lifecycle.Restoring, Lifecycle.Attaching), node.transitions)

        assertTrue(graph.detach(node))
        assertEquals(
            listOf(Lifecycle.Restoring, Lifecycle.Attaching, Lifecycle.Saving, Lifecycle.Destroying),
            node.transitions,
        )
        assertTrue(node.closed)
    }

    @Test
    fun requireFullyWiredFailsForMissingPortsAndPassesAfterWiring() {
        val graph = Graph(dependencyAdapter = TestDependencyAdapter())
        val provider = ProviderNode(graph)
        val consumer = ConsumerNode(graph)

        graph.attach(provider)
        graph.attach(consumer)

        assertEquals(1, graph.unconnectedConsumers().size)
        assertFailsWith<IllegalArgumentException> {
            graph.requireFullyWired()
        }

        graph.autoWire()

        assertTrue(graph.unconnectedConsumers().isEmpty())
        graph.requireFullyWired()
    }

    @Test
    fun crossGraphForwardAlsoRecordsContainerInParentBackStack() {
        val root = Graph(dependencyAdapter = TestDependencyAdapter())
        val home = RouteNode(root, "/") { RouteBinding(Payload()) }
        root.attach(home)
        root.addRoot(home, Payload())

        val child = Graph(parentGraph = root, dependencyAdapter = TestDependencyAdapter())
        val chat = RouteNode(child, "/chat") { RouteBinding(Payload()) }
        child.attach(chat)

        val container = ContainerNode(root, "/home", arrayListOf(child))
        root.attach(container)

        root.dispatch(Push(home.edge(chat), Payload()))

        assertEquals("/home", root.backStack.entries.value.last().edge.end.pattern.original)
        assertEquals("/chat", child.backStack.entries.value.last().edge.end.pattern.original)
    }
}

private class RecordingNode(
    graph: Graph,
) : BasicNode(graph) {
    val transitions = mutableListOf<Lifecycle>()
    var closed = false

    override fun onTransition(previous: Lifecycle, next: Lifecycle) {
        transitions += next
    }

    override fun close() {
        closed = true
        super.close()
    }
}

private class ProviderNode(
    graph: Graph,
) : BasicNode(graph) {
    val textPort by provides("hello")
}

private class ConsumerNode(
    graph: Graph,
) : BasicNode(graph) {
    val textPort by consumes<String>()
}

private class TestDependencyAdapter : DependencyAdapter<Unit>(Unit) {
    override fun createScope(
        id: String,
        parent: DependencyScopeCapability?,
        configure: (ScopeBuilder.() -> Unit),
    ): DependencyScopeCapability = TestScope(id, parent as? TestScope)

    override fun closeScope(scope: DependencyScopeCapability) {
        scope.close()
    }

    override fun <T : Any> get(
        scope: DependencyScopeCapability,
        type: KClass<T>,
        qualifier: String?,
        parameters: Map<String, Any?>,
    ): T = (scope as TestScope).get(type, qualifier, parameters)

    override fun <T : Any> register(
        scope: DependencyScopeCapability,
        instance: T,
        type: KClass<T>,
        qualifier: String?,
    ) {
        (scope as TestScope).register(type, qualifier, instance)
    }
}

private class TestScope(
    override val id: String,
    private val parent: TestScope? = null,
) : DependencyScopeCapability {
    private val values = mutableMapOf<Pair<KClass<*>, String?>, Any>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(
        type: KClass<T>,
        qualifier: String?,
        parameters: Map<String, Any?>,
    ): T = (values[type to qualifier] ?: parent?.get(type, qualifier, parameters))
        as? T
        ?: error("No dependency for ${type.simpleName} qualifier=$qualifier")

    fun <T : Any> register(
        type: KClass<T>,
        qualifier: String?,
        instance: T,
    ) {
        values[type to qualifier] = instance
    }

    override fun close() {
        values.clear()
    }
}
