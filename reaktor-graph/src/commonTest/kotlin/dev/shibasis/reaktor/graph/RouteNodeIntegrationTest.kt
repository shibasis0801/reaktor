package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.autoWire
import dev.shibasis.reaktor.graph.core.node.ControllerNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.node.Route
import dev.shibasis.reaktor.graph.core.node.RouteBinding
import dev.shibasis.reaktor.graph.di.DependencyAdapter
import dev.shibasis.reaktor.graph.di.DependencyScopeCapability
import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.consumes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RouteNodeIntegrationTest {
    private class RouteConsumer(graph: Graph) : ControllerNode<Unit>(graph) {
        override val state = MutableStateFlow(Unit)
        override val routeBinding: ConsumerPort<out RouteBinding<out Payload>> by consumes<RouteBinding<Payload>>()
    }

    @Test
    fun routeNodeExposesAllAttachedNodes() {
        val graph = Graph(label = "root", dependencyAdapter = TestDependencyAdapter())
        val route = graph.Route("/route") { RouteBinding(Payload()) }
        val first = graph.Node(::RouteConsumer)
        val second = graph.Node(::RouteConsumer)

        graph.autoWire()

        val attached = route.attachedNodes()
        assertEquals(2, attached.size)
        assertEquals(setOf(first, second), attached.toSet())
        assertNotNull(route.attachedNode())
    }

    @Test
    fun routeNodeExposesNavigationTargets() {
        val graph = Graph(label = "root", dependencyAdapter = TestDependencyAdapter())
        val start = graph.Route("/start") { RouteBinding(Payload()) }
        val end = graph.Route("/end") { RouteBinding(Payload()) }

        start.edge(end)

        assertEquals(listOf(end), start.navigationTargets())
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
}
