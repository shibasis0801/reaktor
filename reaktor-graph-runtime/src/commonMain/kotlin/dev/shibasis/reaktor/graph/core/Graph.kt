package dev.shibasis.reaktor.graph.core

import dev.shibasis.reaktor.graph.core.node.currentRoute
import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.graph.di.DependencyCapability
import dev.shibasis.reaktor.graph.di.DependencyCapabilityImpl
import dev.shibasis.reaktor.graph.capabilities.Lifecycle
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapability
import dev.shibasis.reaktor.graph.capabilities.LifecycleCapabilityImpl
import dev.shibasis.reaktor.graph.navigation.Forward
import dev.shibasis.reaktor.graph.navigation.NavigationCapability
import dev.shibasis.reaktor.graph.navigation.NavigationCapabilityImpl
import dev.shibasis.reaktor.graph.navigation.NavigationActivation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlin.coroutines.CoroutineContext
import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.graph.navigation.BackStackEntry
import dev.shibasis.reaktor.graph.navigation.Push
import dev.shibasis.reaktor.graph.navigation.Replace
import dev.shibasis.reaktor.graph.navigation.Back
import dev.shibasis.reaktor.graph.navigation.Pop
import dev.shibasis.reaktor.graph.core.node.ActorNode
import dev.shibasis.reaktor.graph.core.node.ActorSupervisorStrategy
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.DefaultActorSupervisorStrategy
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.Type
import dev.shibasis.reaktor.portgraph.port.Type.Companion.Type
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.KeyType
import dev.shibasis.reaktor.portgraph.port.PortCapability
import dev.shibasis.reaktor.portgraph.port.PortCapabilityImpl
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.graph.di.DependencyAdapter
import dev.shibasis.reaktor.graph.di.DependencyException
import dev.shibasis.reaktor.graph.di.Dependency
import dev.shibasis.reaktor.graph.navigation.NavCommand
import dev.shibasis.reaktor.portgraph.graph.PortGraph
import dev.shibasis.reaktor.portgraph.traversal.Relation
import dev.shibasis.reaktor.portgraph.traversal.Step
import dev.shibasis.reaktor.portgraph.traversal.Visitable
import dev.shibasis.reaktor.portgraph.traversal.depthFirst
import dev.shibasis.reaktor.core.framework.reaktorDefaultDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.atomicfu.atomic
import kotlin.js.JsExport
import kotlin.uuid.Uuid

sealed interface GraphChange {
    class Attached(val node: Node) : GraphChange
    class Detached(val node: Node) : GraphChange
    class Owned(val graph: Graph) : GraphChange
    data object Closed : GraphChange
}

@JsExport
open class Graph(
    val parentGraph: Graph? = null,
    dispatcher: CoroutineDispatcher = parentGraph?.coroutineDispatcher ?: reaktorDefaultDispatcher,
    dependencyAdapter: DependencyAdapter<*> = parentGraph?.diAdapter ?: Feature.Dependency ?: throw DependencyException,
    override val id: Uuid = Uuid.random(),
    override val label: String = "",
    val dependencies: (DependencyAdapter.ScopeBuilder.() -> Unit) = {},
    builder: Graph.() -> Unit = {}
): PortGraph<Graph, Node>(id, label),
    PortCapability by PortCapabilityImpl(),
    LifecycleCapability,
    DependencyCapability,
    ConcurrencyCapability,
    NavigationCapability
{
    private val lifecycleCapability = LifecycleCapabilityImpl()
    private val dependencyCapability = DependencyCapabilityImpl(
        diAdapter = dependencyAdapter,
        id = id.toString(),
        parentScope = parentGraph?.let { (it as DependencyCapability).diScope },
        configure = dependencies
    )
    private val concurrencyCapability = ConcurrencyCapabilityImpl(
        parentGraph?.let { (it as ConcurrencyCapability).coroutineScope.coroutineContext },
        dispatcher
    )

    override val lifecycle get() = lifecycleCapability.lifecycle
    override val diAdapter get() = dependencyCapability.diAdapter
    override val diScope get() = dependencyCapability.diScope
    override val coroutineScope get() = concurrencyCapability.coroutineScope
    override val coroutineDispatcher get() = concurrencyCapability.coroutineDispatcher

    private val ownedGraphs = atomic<Map<Uuid, Graph>>(emptyMap())
    internal val wirings = MutableStateFlow(0)
    private val offered = atomic<List<GraphOutput>>(emptyList())
    private val closed = atomic(false)
    private val structureListeners = atomic<List<(GraphChange) -> Unit>>(emptyList())

    @JsExport.Ignore
    val owned: Collection<Graph> get() = ownedGraphs.value.values

    internal fun own(child: Graph): Boolean {
        while (true) {
            val current = ownedGraphs.value
            if (child.id in current) return false
            if (ownedGraphs.compareAndSet(current, current + (child.id to child))) return true
        }
    }

    private fun disown(child: Graph) {
        while (true) {
            val current = ownedGraphs.value
            if (current[child.id] !== child) return
            if (ownedGraphs.compareAndSet(current, current - child.id)) return
        }
    }

    @JsExport.Ignore
    override fun members(): List<Visitable> {
        val held = nodes.flatMapTo(HashSet()) { it.members() }
        return buildList<Visitable> { addAll(nodes); owned.filterTo(this) { it !in held } }
    }

    @JsExport.Ignore
    fun onStructure(listener: (GraphChange) -> Unit): () -> Unit {
        while (true) {
            val current = structureListeners.value
            if (structureListeners.compareAndSet(current, current + listener)) break
        }
        return {
            while (true) {
                val current = structureListeners.value
                if (structureListeners.compareAndSet(current, current - listener)) break
            }
        }
    }

    internal fun changed(change: GraphChange) = structureListeners.value.forEach { it(change) }

    override fun <Functionality : Any> registerProvider(keyType: KeyType, impl: Functionality): ProviderPort<Functionality> =
        registerProvider(keyType.key, keyType.type, impl)

    override fun <Functionality : Any> registerConsumer(keyType: KeyType): ConsumerPort<Functionality> =
        registerConsumer(keyType.key, keyType.type)

    @JsExport.Ignore
    val outputs: List<GraphOutput>
        get() = offered.value + providerPorts.flattenedValues().map { GraphOutput(it.key.key, listOf(it)) }

    internal fun offer(name: String, owner: PortCapability, ports: List<ProviderPort<Any>>) {
        val home = (owner as? Node)?.graph ?: owner as? Graph
        require(home != null && home in tree()) {
            "Graph '$label' can offer only ports from its own subtree; '$name' belongs to $owner"
        }
        while (true) {
            val current = offered.value
            if (offered.compareAndSet(current, current + GraphOutput(name, ports))) return
        }
    }

    internal fun providers() = nodes.flatMap { it.providerPorts.flattenedValues() }

    internal fun children(): List<Graph> = childGraphs()

    internal fun visible(): List<ProviderPort<Any>> = (providers() + children().flatMap { child -> child.outputs.flatMap { it.ports } }).distinct()

    fun findProvider(type: Type, key: Key): ProviderPort<Any>? {
        var scope: Graph? = this
        while (scope != null) {
            val matches = scope.visible().filter { it.type == type }.matching(key)
            require(matches.size <= 1) { "Graph '${scope.label}' has ambiguous providers for $type / $key: ${matches.joinToString { it.owner.toString() }}" }
            matches.singleOrNull()?.let { return it }
            scope = scope.parentGraph
        }
        return null
    }

    fun findOnlyProvider(type: Type): ProviderPort<Any>? =
        generateSequence(this) { it.parentGraph }
            .map { scope -> scope.visible().filter { it.type == type } }
            .firstOrNull { it.isNotEmpty() }?.singleOrNull()

    val sentinel = RouteNode(this, "")

    @JsExport.Ignore
    var captureNavigationContext: (() -> CoroutineContext)? = null

    private val _navigationActivation = MutableStateFlow<NavigationActivation?>(null)
    @JsExport.Ignore
    val navigationActivation: StateFlow<NavigationActivation?> = _navigationActivation.asStateFlow()

    internal fun recordNavigationActivation(entry: BackStackEntry<*, *>?) {
        var owner: Graph? = this
        while (owner != null) {
            val capture = owner.captureNavigationContext
            if (capture != null) {
                _navigationActivation.value = NavigationActivation(entry?.id, capture())
                return
            }
            owner = owner.parentGraph
        }
    }

    private val navigationImpl = NavigationCapabilityImpl(::recordNavigationActivation)
    override val backStack get() = navigationImpl.backStack

    @JsExport.Ignore
    var actorSupervisorStrategy: ActorSupervisorStrategy = DefaultActorSupervisorStrategy

    override fun dispatch(navCommand: NavCommand) {
        when (navCommand) {
            is Forward<*, *> -> {
                val edge = navCommand.entry.edge
                if (edge.isCrossGraph) {
                    handleCrossGraphForward(navCommand)
                } else {
                    navigationImpl.dispatch(navCommand)
                }
            }
            is Back<*> -> {
                val edge = backStack.top.value?.edge
                navigationImpl.dispatch(navCommand)
                if (edge?.isCrossGraph == true) restoreSourceGraph(edge.sourceGraph)
            }
        }
    }

    private fun restoreSourceGraph(source: Graph) {
        if (parentGraph != null) {
            parentGraph.restoreSourceGraph(source)
            return
        }
        containerPathTo(source)?.forEach { (container, child) ->
            val owner = container.graph
            val entries = owner.backStack.entries.value
            val previous = entries.indexOfLast { it.edge.end == container.route }
            if (previous >= 0) {
                repeat(entries.lastIndex - previous) { owner.navigationImpl.dispatch(Pop) }
            } else {
                owner.pushContainerEntry(container, Payload())
            }
            container.activate(child)
        }
    }

    private fun handleCrossGraphForward(navCommand: Forward<*, *>) {
        // Bubble up to the parent that owns the container
        if (parentGraph != null) {
            parentGraph.dispatch(navCommand as NavCommand)
            return
        }

        val destGraph = navCommand.entry.edge.destinationGraph
        if (destGraph === this) {
            navigationImpl.dispatch(navCommand)
            return
        }
        val path = containerPathTo(destGraph)
        if (path == null) {
            Logger.w("Cross-graph navigation failed: no container found for graph '${destGraph.label}' (${destGraph.id}).")
            return
        }
        val source = navCommand.entry.edge.sourceGraph
        if (navCommand is Replace<*, *> && source.backStack.entries.value.size > 1 &&
            source.currentRoute == navCommand.entry.edge.start) {
            source.navigationImpl.dispatch(Pop)
        }
        path.forEach { (container, child) ->
            container.graph.pushContainerEntry(container, navCommand.entry.payload)
            container.activate(child)
        }
        destGraph.navigationImpl.dispatch(if (navCommand is Replace<*, *>) Push(navCommand.entry) else navCommand)
    }

    private fun pushContainerEntry(
        container: ContainerNode,
        payload: Payload,
    ) {
        val from = currentRoute ?: sentinel
        if (from == container.route) return
        navigationImpl.dispatch(Push<Payload>(from.edge(container.route), Payload(HashMap(payload.routeParams))))
    }

    init { builder() }

    override fun attach(node: Node): Boolean {
        if (node is ActorNode<*>) {
            val existing = node(node.id)
            if (existing != null) {
                if (existing is ActorNode<*> && existing.key == node.key) {
                    Logger.w("Actor '${node.key.name}' is already attached. Ignoring.")
                } else {
                    Logger.w("Node id ${node.id} is already attached. Ignoring actor '${node.key.name}'.")
                }
                return false
            }
        }

        if (!super.attach(node)) {
            Logger.w("Node ${node.id} is already attached. Ignoring.")
            return false
        }

        node.transition(Lifecycle.Restoring)
        node.transition(Lifecycle.Attaching)
        changed(GraphChange.Attached(node))
        return true
    }

    override fun detach(node: Node): Boolean {
        if (!nodes.contains(node)) {
            return false
        }

        node.transition(Lifecycle.Saving)
        node.transition(Lifecycle.Destroying)
        node.close()
        return super.detach(node).also { if (it) changed(GraphChange.Detached(node)) }
    }

    fun addRoot(routeNode: RouteNode<Payload, *>, payload: Payload = Payload()) = dispatch(Push(sentinel.edge(routeNode), payload))

    override fun onTransition(
        previous: Lifecycle,
        next: Lifecycle
    ) {
        val transitionNodes = { nodes.forEach { it.transition(next) } }

        when (next) {
            Lifecycle.Created -> transitionNodes()
            Lifecycle.Restoring -> transitionNodes()
            Lifecycle.Attaching -> { /* hook if needed */ }
            Lifecycle.Saving -> transitionNodes()
            Lifecycle.Destroying -> {
                nodes.toList().forEach { detach(it) }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        parentGraph?.disown(this)
        changed(GraphChange.Closed)
        structureListeners.value = emptyList()
        transition(Lifecycle.Destroying)
        owned.forEach { it.close() }
        ownedGraphs.value = emptyMap()
        consumerPorts.flattenedValues().forEach { it.close() }
        providerPorts.flattenedValues().forEach { it.close() }
        offered.value = emptyList()
        _navigationActivation.value = null
        captureNavigationContext = null
        dependencyCapability.close()
        concurrencyCapability.close()
        lifecycleCapability.close()
    }

    override fun toString(): String {
        return "[Graph] label='$label' id='$id' nodes=${nodes.size} stackDepth=${backStack.entries.value.size}, sentinel=$sentinel"
    }
}

inline fun <reified Functionality : Any> Node.exposePort(port: ProviderPort<Functionality>) {
    graph.diAdapter.register(
        scope = graph.diScope,
        instance = port,
        type = ProviderPort::class,
        qualifier = port.qualifier
    )
}

fun Graph.autoWire(vararg dependencies: Graph) {
    val dependencyProviders = dependencies
        .flatMap { graph -> graph.nodes.flatMap { node -> node.providerPorts.flattenedValues() } }
        .distinct()
        .groupBy { it.type }
    val localProviders = nodes
        .flatMap { node -> node.providerPorts.flattenedValues() }
        .groupBy { it.type }

    nodes.flatMap { it.consumerPorts.flattenedValues() }
        .filter { !it.isConnected() }
        .forEach { consumer ->
            val localCandidates = localProviders[consumer.type] ?: emptyList()
            var match = if (consumer.key.key.isEmpty()) {
                if (localCandidates.size == 1) localCandidates.first()
                else localCandidates.find { it.key.key.isEmpty() }
            } else {
                localCandidates.find { it.key == consumer.key }
            }

            if (match == null) {
                val candidates = dependencyProviders[consumer.type].orEmpty()
                val matching = if (consumer.key.key.isEmpty() && candidates.size == 1) candidates
                    else candidates.filter { it.key == consumer.key }
                require(matching.size <= 1) {
                    "Graph '$label' has ambiguous dependency providers for key='${consumer.key.key}' type='${consumer.type.type}'"
                }
                match = matching.singleOrNull()
            }

            if (match == null) match = findProvider(consumer.type, consumer.key)

            if (match == null) {
                val portFromDI = try {
                    diScope.get(ProviderPort::class, consumer.qualifier)
                } catch (e: Exception) {
                    null
                }

                if (portFromDI != null) {
                    @Suppress("UNCHECKED_CAST")
                    match = portFromDI as ProviderPort<Any>
                }
            }

            if (match == null) match = findOnlyProvider(consumer.type)

            if (match != null) {
                connect(consumer, match).getOrThrow()
            }
        }
}

fun Graph.wire() {
    val graphs = tree()
    graphs.forEach { graph -> graph.wirings.update { it + 1 } }
    try {
        graphs.forEach { it.autoWire() }
        val reach = mutableMapOf<Graph, List<ProviderPort<Any>>>()
        fun pass(pick: List<ProviderPort<Any>>.(Key) -> List<ProviderPort<Any>>) = graphs.forEach { graph ->
            graph.unconnectedConsumers().forEach { consumer ->
                val nearest = generateSequence(graph) { it.parentGraph }
                    .map { scope -> reach.getOrPut(scope) { scope.visible() }.filter { it.type == consumer.type }.pick(consumer.key) }
                    .firstOrNull { it.isNotEmpty() }.orEmpty()
                require(nearest.size <= 1) { "${consumer.owner} can take ${consumer.type.type} from ${nearest.joinToString { it.owner.toString() }}" }
                nearest.singleOrNull()?.let { connect(consumer, it).getOrThrow() }
            }
        }
        pass { matching(it) }
        pass { this }
        graphs.forEach { it.requireFullyWired() }
    } finally {
        graphs.forEach { graph -> graph.wirings.update { it - 1 } }
    }
}

suspend fun Graph.awaitWired() {
    wirings.first { it == 0 }
}

private fun List<ProviderPort<Any>>.matching(key: Key) = if (key.key.isEmpty() && size == 1) this else filter { it.key == key }

private fun Graph.childGraphs(): List<Graph> = (owned + nodes.filterIsInstance<ContainerNode>().flatMap { it.graphs }).distinct()

private val ChildGraphs = Relation { element -> (element as? Graph)?.childGraphs()?.asSequence() ?: emptySequence() }

internal fun Graph.tree(): Set<Graph> =
    depthFirst(ChildGraphs).mapNotNullTo(linkedSetOf()) { (it as? Step.Enter)?.element as? Graph }

fun Graph.unconnectedConsumers() = nodes
    .flatMap { it.consumerPorts.flattenedValues() }
    .filterNot { it.isConnected() }

fun Graph.requireFullyWired() {
    val unconnected = unconnectedConsumers()
    require(unconnected.isEmpty()) {
        val everywhere = generateSequence(this) { it.parentGraph }.last().tree().flatMap { graph -> graph.providers().map { it to graph } }
        buildString {
            append("Graph '$label' has unconnected consumer ports:")
            unconnected.forEach { port ->
                append("\n- key='${port.key.key}' type='${port.type.type}' owner='${port.owner}'")
                val reach = generateSequence((port.owner as? Node)?.graph ?: this@requireFullyWired) { it.parentGraph }.toSet()
                everywhere.filter { it.first.type == port.type }.forEach { (provider, graph) ->
                    val through = generateSequence(graph) { it.parentGraph }.takeWhile { it !in reach }.map { "'${it.label}'" }.toList()
                    if (through.isNotEmpty()) append("\n  ${provider.owner} provides it inside ${through.first()}; offer it with `by provides` from ${through.joinToString(", then ")}")
                }
            }
        }
    }
}

inline fun <reified T : Any> Graph.input(key: String = ""): ProviderPort<T> = input(Type<T>(), Key(key))

@PublishedApi
@Suppress("UNCHECKED_CAST")
internal fun <T : Any> Graph.input(type: Type, key: Key): ProviderPort<T> {
    val provider = requireNotNull(findProvider(type, key)) { "Graph '$label' requires a provider for $type / ${key.key}" } as ProviderPort<T>
    val home = (provider.owner as? Node)?.graph ?: provider.owner as? Graph
    if (home !in tree()) registerConsumer<T>(key, type).let { if (!it.isConnected()) connect(it, provider).getOrThrow() }
    return provider
}

fun<G: Graph> Graph.Graph(builder: (Graph) -> G): G = builder(this).also {
    require(it.parentGraph === this) { "Owned graph must have its owner as parent" }
    if (own(it)) changed(GraphChange.Owned(it))
}

fun Graph.containerPathTo(
    targetGraph: Graph,
    visited: MutableSet<Graph> = mutableSetOf()
): List<Pair<ContainerNode, Graph>>? {
    if (!visited.add(this)) return null
    for (container in nodes.filterIsInstance<ContainerNode>()) {
        for (childGraph in container.graphs) {
            if (childGraph == targetGraph) return listOf(container to childGraph)
            childGraph.containerPathTo(targetGraph, visited)?.let { return listOf(container to childGraph) + it }
        }
    }
    return null
}

@JsExport
fun Graph.findNode(label: String): Node? =
    nodes.firstOrNull { it.label == label }

inline fun <reified T : Node> Graph.findNodeByType(): T? =
    nodes.filterIsInstance<T>().firstOrNull()

fun Graph.findRoute(pattern: String): RouteNode<*, *>? =
    nodes.filterIsInstance<RouteNode<*, *>>().firstOrNull { it.pattern.original == pattern }
