package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.PortShape
import dev.shibasis.reaktor.graph.core.ShapeAttributes
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.portgraph.attach.AttachmentKey
import dev.shibasis.reaktor.portgraph.attach.attachment
import dev.shibasis.reaktor.portgraph.attach.update
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.PortCapability
import dev.shibasis.reaktor.portgraph.port.PortDelegate
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.Type
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.ServiceExecutionPhase
import dev.shibasis.reaktor.service.contract.Call
import dev.shibasis.reaktor.service.contract.CallAttributes
import dev.shibasis.reaktor.service.contract.Exchange
import dev.shibasis.reaktor.service.contract.HttpLink
import dev.shibasis.reaktor.service.contract.Installed
import dev.shibasis.reaktor.service.contract.Interceptor
import dev.shibasis.reaktor.service.contract.InterceptorScope
import dev.shibasis.reaktor.service.contract.InterceptorShape
import dev.shibasis.reaktor.service.contract.InterceptorSource
import dev.shibasis.reaktor.service.contract.Interceptors
import dev.shibasis.reaktor.service.contract.Link
import dev.shibasis.reaktor.service.contract.LocalLink
import dev.shibasis.reaktor.service.contract.Served
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.service.contract.Stream
import dev.shibasis.reaktor.service.contract.Unlinked
import dev.shibasis.reaktor.service.contract.descriptors
import dev.shibasis.reaktor.service.contract.over
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.properties.PropertyDelegateProvider

enum class ContractRole { Server, Client }

class ContractNode<S : Service>(
    graph: Graph,
    val api: S,
    vararg interceptors: Interceptor,
    val served: Served<*>? = null,
) : BasicNode(graph), ShapeAttributes, Provides<S> {
    val link: Link = served?.let(::LocalLink) ?: api.link

    val role: ContractRole = if (served != null) ContractRole.Server else ContractRole.Client

    override val kind: NodeKind get() = NodeKind.Service

    override val label: String = if (role == ContractRole.Server) api.id else "${api.id} client"

    val interceptors: Interceptors = Interceptors(InterceptorScope.Node).apply { add(*interceptors) }

    override val port: ProviderPort<S> = registerProvider(Key(""), contractType(api), api)

    @Suppress("UNCHECKED_CAST")
    val calls: List<ProviderPort<*>> = api.operations.filterIsInstance<Call<*, *>>().map { it as Call<Any?, Any?> }.map { call ->
        registerProvider<suspend (Any?) -> Any?>(Key(call.name), callType(call), { request -> link.call(call, request) })
    }

    private val identity = NodeIdentity(this, port.type)

    init {
        val source = InterceptorSource { graph.serviceInterceptors() + this.interceptors.at(0) }
        link.scope = source
        link.identity = { identity() }
        served?.scope = source
        served?.identity = { identity() + (ServiceCall.ActivationAttribute to rootOf(graph).id.toString()) }
    }

    val chain: List<Installed> get() = served?.chain() ?: link.chain()

    val interceptorShapes: List<InterceptorShape> get() = chain.map { it.shape }

    val operations get() = api.descriptors(if (role == ContractRole.Server) ServiceExecutionPhase.SERVER else ServiceExecutionPhase.CLIENT)

    override val shapeAttributes: Map<String, String>
        get() = buildMap {
            put("service", api.id)
            put("contract", api.id)
            put("version", api.version.toString())
            put("role", if (role == ContractRole.Server) "server" else "client")
            put("transport", link.transport)
            put("fingerprint", api.fingerprint)
            (link as? HttpLink)?.baseUrl?.takeIf { it.isNotBlank() }?.let { put("baseUrl", it) }
        }

    override fun toString(): String = "${super.toString()} [Contract] ${api.id}@${api.fingerprint} role=$role transport=${link.transport}"
}

fun <S : Service> Graph.serve(service: S, vararg interceptors: Interceptor): ContractNode<S> =
    serve(Served(service), *interceptors)

fun <S : Service> Graph.serve(served: Served<S>, vararg interceptors: Interceptor): ContractNode<S> =
    Node { ContractNode(it, served.service, *interceptors, served = served) }

fun <S : Service> Graph.remote(api: S, vararg interceptors: Interceptor): ContractNode<S> =
    Node { ContractNode(it, api, *interceptors) }

fun Graph.contracts(): List<ContractNode<*>> = nodes.filterIsInstance<ContractNode<*>>()

fun Graph.served(): List<Served<*>> = contracts().mapNotNull { it.served }

fun <S : Service> PortCapability.uses(service: S) =
    PropertyDelegateProvider<PortCapability, PortDelegate<S>> { node, _ ->
        val stub = service over PortLink(node.registerConsumer<S>(Key(""), contractType(service)))
        PortDelegate { _, _ -> stub }
    }

val Service.connected: Boolean get() = (link as? PortLink<*>)?.port?.isConnected() ?: (link !== Unlinked)

internal class PortLink<S : Any>(val port: ConsumerPort<S>) : Link() {
    private val wire = WireIdentity(port)

    private val provider: ContractNode<*>? get() = (port.edge?.provider?.owner as? Provides<*>)?.let { if (it is Relay<*>) it.origin else it } as? ContractNode<*>

    private val target: Link
        get() = provider?.link ?: error(
            "No ${port.type.contractId} provider is wired to ${port.owner}. Add graph.serve(…) or graph.remote(…) for ${port.type.contractId} and wire the graph before calling.",
        )

    override val transport: String get() = provider?.link?.transport ?: "port"

    override val served: Served<*>? get() = provider?.link?.served

    override suspend fun <In, Out> call(operation: Call<In, Out>, request: In): Out = target.let { link -> crossing { link.call(operation, request) } }

    override fun <In, Out> stream(operation: Stream<In, Out>, request: In): Flow<Out> = flow { emitAll(target.let { link -> crossing { link.stream(operation, request) } }) }

    override suspend fun <In, Out> transmit(operation: Call<In, Out>, exchange: Exchange<In, Out>): Out = call(operation, exchange.request)

    private suspend fun <R> crossing(block: suspend () -> R): R {
        val edge = wire()
        return port.suspended { if (edge.isEmpty()) block() else withContext(CallAttributes(mapOf(ServiceCall.EdgeAttribute to edge))) { block() } }
    }
}

class CallPort<In, Out> internal constructor(val consumer: ConsumerPort<suspend (In) -> Out>, val call: Call<In, Out>) {
    private val wire = WireIdentity(consumer)

    suspend operator fun invoke(request: In): Out {
        val operation = consumer.impl ?: error("No ${call.id} provider is wired to ${consumer.owner}. Add graph.serve or graph.remote for ${call.service.id} and wire the graph before calling.")
        val edge = wire()
        return consumer.suspended {
            if (edge.isEmpty()) operation(request) else withContext(CallAttributes(mapOf(ServiceCall.EdgeAttribute to edge))) { operation(request) }
        }
    }
}

fun <In, Out> PortCapability.api(call: Call<In, Out>) =
    PropertyDelegateProvider<PortCapability, PortDelegate<CallPort<In, Out>>> { node, _ ->
        val port = CallPort(node.registerConsumer<suspend (In) -> Out>(Key(call.name), callType(call)), call)
        PortDelegate { _, _ -> port }
    }

fun contractType(service: Service): Type = Type("$ContractPrefix${service.id}")

fun callType(call: Call<*, *>): Type = Type("$ContractPrefix${call.service.id}/${call.name}")

val Type.contractId: String? get() = type.takeIf { it.startsWith(ContractPrefix) }?.removePrefix(ContractPrefix)?.substringBefore('/')

val Type.callName: String? get() = type.takeIf { it.startsWith(ContractPrefix) }?.substringAfter('/', "")?.ifEmpty { null }

private const val ContractPrefix = "contract:"

private val GraphInterceptors = AttachmentKey<Interceptors>("reaktor.service.interceptors")

fun Graph.intercept(vararg interceptor: Interceptor): Graph = apply {
    update(GraphInterceptors) { it ?: Interceptors(InterceptorScope.Graph) }?.add(*interceptor)
}

fun Graph.serviceInterceptors(): List<Installed> =
    generateSequence(this) { it.parentGraph }.toList().asReversed()
        .flatMapIndexed { depth, graph -> graph.attachment(GraphInterceptors)?.at(depth).orEmpty() }

internal fun rootOf(graph: Graph): Graph = generateSequence(graph) { it.parentGraph }.last()

internal class NodeIdentity(private val node: Node, private val provided: (PortShape) -> Boolean) {
    constructor(node: Node, type: Type) : this(node, { it.type == type.type })

    private val cached = atomic<Map<String, String>?>(null)

    operator fun invoke(): Map<String, String> {
        cached.value?.let { return it }
        val shape = rootOf(node.graph).shape()
        val shown = shape.nodes.singleOrNull { it.id == node.id.toString() }
        val provider = shown?.ports?.singleOrNull { it.provides && provided(it) }
        val values = mapOf(
            ServiceCall.NodeAttribute to shown?.definitionId.orEmpty(),
            ServiceCall.GraphAttribute to shape.definitionDigest,
            ServiceCall.PortAttribute to provider?.definitionId.orEmpty(),
        )
        if (values.values.all { it.isNotEmpty() }) cached.value = values
        return values
    }
}

internal class WireIdentity(private val port: ConsumerPort<*>) {
    private val cached = atomic<String?>(null)

    operator fun invoke(): String {
        cached.value?.let { return it }
        val owner = port.owner as? Node ?: return ""
        val shape = rootOf(owner.graph).shape()
        val found = shape.wires.singleOrNull { it.consumer == owner.id.toString() && it.consumerPort == port.key.key && it.type == port.type.type }
            ?.definitionId.orEmpty()
        if (found.isNotEmpty()) cached.value = found
        return found
    }
}
