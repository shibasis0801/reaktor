package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.capabilities.Lifecycle
import dev.shibasis.reaktor.graph.core.input
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.portgraph.port.getConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
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
import dev.shibasis.reaktor.graph.navigation.Pop
import dev.shibasis.reaktor.graph.navigation.Return
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides
import dev.shibasis.reaktor.service.GetHandler
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.Service
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GraphLifecycleIntegrationTest {
    @Test
    fun importedConfigurationIsInheritedAndOwnedHeadlessGraphsAreVisibleAndClosed() {
        val root = Graph(dependencyAdapter = TestDependencyAdapter(), label = "app")
        val config = root.Graph { Graph(parentGraph = it, label = "config") }
        val provider = config.Node(::ProviderNode)
        root.dependsOn(config)
        val child = root.Graph { Graph(parentGraph = it, label = "feature") }
        val consumer = child.Node(::ConsumerNode)
        child.autoWire()
        assertSame(provider.textPort, consumer.textPort.edge?.provider)
        assertSame(provider.textPort, child.input<String>(provider.textPort.key.key))
        val shape = root.shape()
        assertEquals(3, shape.scopes.size)
        assertEquals(root.id.toString(), shape.scopes.single { it.label == "config" }.parent)
        assertTrue(shape.wires.any { it.provider == provider.id.toString() && it.consumer == consumer.id.toString() })
        child.close()
        assertTrue(config.nodes.contains(provider))
        assertTrue(provider.textPort.edges.isEmpty())
        root.close()
        root.close()
        assertTrue(config.nodes.isEmpty())
        assertTrue(child.nodes.isEmpty())
    }

    @Test
    fun configuredNodesUseTypedGraphOverridesWithoutConstructingTheDefault() {
        val root = Graph(dependencyAdapter = TestDependencyAdapter())
        val config = root.Graph { Graph(parentGraph = it) }
        val recipe = config.Node(::ProviderNode)
        val replacement = config.Node { BasicNode(it).apply { registerProvider("api", 42) } }
        root.dependsOn(config)
        val feature = root.Graph { Graph(parentGraph = it) }
        val service = feature.Node(recipe.textPort, "api", { graph, number: Int ->
            BasicNode(graph).apply { registerProvider("result", number) }
        }) { _: String -> error("Default implementation must not be created") }
        assertSame(recipe.textPort, service.getConsumer<String>(recipe.textPort.key.key)?.edge?.provider)
        assertSame(replacement, service.getConsumer<Int>("api")?.edge?.provider?.owner)
        feature.requireFullyWired()
        root.close()
    }

    @Test
    fun ancestorPortsAreScopedAndTheNearestOwnerWins() {
        val adapter = TestDependencyAdapter()
        val root = Graph(dependencyAdapter = adapter)
        val rootProvider = ProviderNode(root).also { root.attach(it) }
        val parent = Graph(parentGraph = root, dependencyAdapter = adapter)
        val nearest = ProviderNode(parent).also { parent.attach(it) }
        val child = Graph(parentGraph = parent, dependencyAdapter = adapter)
        val consumer = ConsumerNode(child).also { child.attach(it) }
        child.autoWire()
        child.requireFullyWired()
        assertSame(nearest.textPort, consumer.textPort.edge?.provider)
        child.close()
        assertEquals("hello", rootProvider.textPort.impl)
        assertEquals("hello", nearest.textPort.impl)
        parent.close()
        root.close()
    }

    @Test
    fun siblingPortsNeedAnExplicitGraphDependency() {
        val adapter = TestDependencyAdapter()
        val parent = Graph(dependencyAdapter = adapter)
        val providerGraph = Graph(parentGraph = parent, dependencyAdapter = adapter)
        val provider = ProviderNode(providerGraph).also { providerGraph.attach(it) }
        val consumerGraph = Graph(parentGraph = parent, dependencyAdapter = adapter)
        val consumer = ConsumerNode(consumerGraph).also { consumerGraph.attach(it) }
        consumerGraph.autoWire()
        assertFalse(consumer.textPort.isConnected())
        consumerGraph.autoWire(providerGraph)
        assertSame(provider.textPort, consumer.textPort.edge?.provider)
        consumerGraph.close()
        providerGraph.close()
        parent.close()
    }

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
    fun autoWireResolvesOnlyDeclaredGraphDependenciesAndDeduplicatesThem() {
        val graph = Graph(dependencyAdapter = TestDependencyAdapter())
        val dependency = Graph(dependencyAdapter = graph.diAdapter)
        val unrelated = Graph(dependencyAdapter = graph.diAdapter)
        val consumer = ConsumerNode(graph).also { graph.attach(it) }
        val provider = ProviderNode(dependency).also { dependency.attach(it) }
        unrelated.attach(ProviderNode(unrelated))
        graph.autoWire()
        assertFalse(consumer.textPort.isConnected())
        graph.autoWire(dependency, dependency)
        assertSame(provider.textPort, consumer.textPort.edge?.provider)
        graph.requireFullyWired()
        graph.close()
        assertTrue(dependency.nodes.contains(provider))
        dependency.close()
        unrelated.close()
    }

    @Test
    fun autoWirePrefersLocalProvidersAndPreservesExplicitConnections() {
        val graph = Graph(dependencyAdapter = TestDependencyAdapter())
        val dependency = Graph(dependencyAdapter = graph.diAdapter)
        val external = ProviderNode(dependency).also { dependency.attach(it) }
        val local = ProviderNode(graph).also { graph.attach(it) }
        val automatic = ConsumerNode(graph).also { graph.attach(it) }
        val explicit = ConsumerNode(graph).also { graph.attach(it) }
        connect(explicit.textPort, external.textPort).getOrThrow()
        graph.autoWire(dependency)
        assertSame(local.textPort, automatic.textPort.edge?.provider)
        assertSame(external.textPort, explicit.textPort.edge?.provider)
        graph.close()
        dependency.close()
    }

    @Test
    fun autoWireRejectsAmbiguousGraphDependenciesAndMatchesQualifiedPorts() {
        val graph = Graph(dependencyAdapter = TestDependencyAdapter())
        val first = Graph(dependencyAdapter = graph.diAdapter)
        val second = Graph(dependencyAdapter = graph.diAdapter)
        first.attach(ProviderNode(first))
        second.attach(ProviderNode(second))
        val consumer = ConsumerNode(graph).also { graph.attach(it) }
        assertFailsWith<IllegalArgumentException> { graph.autoWire(first, second) }
        assertFalse(consumer.textPort.isConnected())
        val alternate = object : BasicNode(second) {
            val text by provides("qualified", name = "alternate")
        }.also { second.attach(it) }
        val named = object : BasicNode(graph) {
            val text by consumes<String>("alternate")
        }.also { graph.attach(it) }
        graph.autoWire(first)
        graph.autoWire(second)
        assertSame(alternate.text, named.text.edge?.provider)
        graph.close()
        first.close()
        second.close()
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

    @Test
    fun childCanReturnToAnApplicationLevelRoute() {
        val root = Graph(dependencyAdapter = TestDependencyAdapter())
        val session = RouteNode(root, "/session") { RouteBinding(Payload()) }
        root.attach(session)
        val identity = Graph(parentGraph = root, dependencyAdapter = root.diAdapter)
        val signIn = RouteNode(identity, "/sign-in") { RouteBinding(Payload()) }
        identity.attach(signIn)
        val container = ContainerNode(root, "/identity", arrayListOf(identity))
        root.attach(container)
        root.addRoot(container.route, Payload())

        identity.dispatch(Push(signIn.edge(session), Payload()))

        assertEquals(session, root.backStack.entries.value.last().edge.end)
        root.close()
    }

    @Test
    fun closingContainerReleasesNestedGraphsAndTheirNodes() {
        val root = Graph(dependencyAdapter = TestDependencyAdapter())
        val child = Graph(parentGraph = root, dependencyAdapter = root.diAdapter)
        val grandchild = Graph(parentGraph = child, dependencyAdapter = root.diAdapter)
        val node = RecordingNode(grandchild)
        grandchild.attach(node)
        child.attach(ContainerNode(child, "/nested", arrayListOf(grandchild)))
        val container = ContainerNode(root, "/features", arrayListOf(child))
        root.attach(container)

        root.close()

        assertTrue(node.closed)
        assertTrue(child.nodes.isEmpty())
        assertTrue(grandchild.nodes.isEmpty())
        assertTrue(container.graphs.isEmpty())
    }

    @Test
    fun closingContainerLeavesBorrowedGraphsAlive() {
        val root = Graph(dependencyAdapter = TestDependencyAdapter())
        val borrowed = Graph(dependencyAdapter = root.diAdapter)
        val node = RecordingNode(borrowed)
        borrowed.attach(node)
        root.attach(ContainerNode(root, "/borrowed", arrayListOf(borrowed)))

        root.close()

        assertTrue(borrowed.nodes.contains(node))
        assertTrue(!node.closed)
        borrowed.close()
        assertTrue(node.closed)
    }

    @Test
    fun crossGraphBackRestoresSourceContainerAndKeepsItsPayload() = runTest {
        val root = Graph(dependencyAdapter = TestDependencyAdapter())
        val source = Graph(parentGraph = root, dependencyAdapter = root.diAdapter)
        val destination = Graph(parentGraph = root, dependencyAdapter = root.diAdapter)
        val origin = RouteNode(source, "/origin") { RouteBinding(Payload()) }
        val landing = RouteNode(destination, "/landing") { RouteBinding(Payload()) }
        val detail = RouteNode(destination, "/detail") { RouteBinding(Payload()) }
        source.attach(origin)
        destination.attach(landing)
        destination.attach(detail)
        source.addRoot(origin, Payload(hashMapOf("id" to "origin")))
        destination.addRoot(landing, Payload())
        val first = ContainerNode(root, "/first", arrayListOf(source))
        val second = ContainerNode(root, "/second", arrayListOf(destination))
        root.attach(first)
        root.attach(second)
        root.addRoot(first.route, Payload())

        source.dispatch(Push(origin.edge(detail), Payload(hashMapOf("id" to "detail"))))
        assertEquals(second.route, root.backStack.top.value?.edge?.end)
        destination.dispatch(Pop)

        assertEquals(first.route, root.backStack.top.value?.edge?.end)
        assertEquals(origin, source.backStack.top.value?.edge?.end)
        assertEquals("origin", source.backStack.top.value?.payload?.routeParams?.get("id"))
        assertEquals(landing, destination.backStack.top.value?.edge?.end)
        val result = CompletableDeferred<String>()
        source.dispatch(Push(origin.edge(detail), Payload(), result))
        destination.dispatch(Return("selected"))
        assertEquals("selected", result.await())
        assertEquals(first.route, root.backStack.top.value?.edge?.end)
        root.close()
    }

    @Test
    fun serviceApiConsumerAutoWiresToDelegatedHandlerProvider() = runTest {
        val graph = Graph(dependencyAdapter = TestDependencyAdapter())
        val service = CatalogClient()
        val serviceNode = ServiceNode(graph, service)
        val consumer = CatalogConsumerNode(graph)

        graph.attach(serviceNode)
        graph.attach(consumer)
        graph.autoWire()

        assertTrue(consumer.fetchApi.isConnected())
        assertEquals(service.fetch, consumer.fetchApi())
        assertEquals(CatalogResponse("catalog-1"), consumer.fetchApi(CatalogRequest("catalog-1")))
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

private abstract class CatalogService : Service() {
    abstract val fetch: GetHandler<CatalogRequest, CatalogResponse>
}

private class CatalogClient : CatalogService() {
    override val fetch by GetHandler<CatalogRequest, CatalogResponse>("/catalog") { request ->
        CatalogResponse(request.id)
    }
}

private class CatalogConsumerNode(
    graph: Graph,
) : BasicNode(graph) {
    val fetchApi by api(CatalogService::fetch)
}

@Serializable
private data class CatalogRequest(
    val id: String = "",
) : Request()

@Serializable
private data class CatalogResponse(
    val id: String = "",
) : Response()

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
