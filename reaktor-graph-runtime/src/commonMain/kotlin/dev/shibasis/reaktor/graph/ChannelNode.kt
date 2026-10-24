package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.ShapeAttributes
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.PortCapability
import dev.shibasis.reaktor.portgraph.port.PortDelegate
import dev.shibasis.reaktor.portgraph.port.PortEvent
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.Type
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.contract.Broker
import dev.shibasis.reaktor.service.contract.CallAttributes
import dev.shibasis.reaktor.service.contract.Channel
import dev.shibasis.reaktor.service.contract.ChannelShape
import dev.shibasis.reaktor.service.contract.Feed
import dev.shibasis.reaktor.service.contract.Installed
import dev.shibasis.reaktor.service.contract.Interceptor
import dev.shibasis.reaktor.service.contract.InterceptorScope
import dev.shibasis.reaktor.service.contract.InterceptorShape
import dev.shibasis.reaktor.service.contract.InterceptorSource
import dev.shibasis.reaktor.service.contract.Interceptors
import dev.shibasis.reaktor.service.contract.Link
import dev.shibasis.reaktor.service.contract.Publisher
import dev.shibasis.reaktor.service.contract.SocketSession
import dev.shibasis.reaktor.service.contract.Subscription
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.properties.PropertyDelegateProvider

class ChannelNode<M> internal constructor(
    graph: Graph,
    val channel: Channel<M>,
    outlet: Link?,
    interceptors: List<Interceptor>,
) : BasicNode(graph), ShapeAttributes {
    override val kind: NodeKind get() = NodeKind.Service

    override val label: String = channel.id

    val interceptors: Interceptors = Interceptors(InterceptorScope.Node).apply { add(*interceptors.toTypedArray()) }

    val broker: Broker<M> = Broker(channel, coroutineScope)

    val link: Link = outlet ?: broker.link

    val publisher: Publisher<M> = Publisher { message -> link.publish(channel, message) }

    val publishing: ProviderPort<Publisher<M>> = registerProvider(Key(""), publishType(channel), publisher)

    val delivering: ProviderPort<Broker<M>> = registerProvider(Key(""), subscribeType(channel), broker)

    val deadLetters: ConsumerPort<Publisher<M>>? = channel.deadLetter?.let { registerConsumer<Publisher<M>>(Key(""), publishType(it)) }

    init {
        val source = InterceptorSource { graph.serviceInterceptors() + this.interceptors.at(0) }
        val sent = NodeIdentity(this, publishing.type)
        val received = NodeIdentity(this, delivering.type)
        broker.source = source
        broker.identity = { received() + (ServiceCall.ActivationAttribute to rootOf(graph).id.toString()) }
        outlet?.scope = source
        link.identity = { sent() }
        deadLetters?.let { port ->
            val fallback = broker.deadLetter
            broker.deadLetter = { letter -> port.impl?.publish(letter.envelope.message) ?: fallback(letter) }
        }
        (outlet as? Feed)?.let { feed ->
            coroutineScope.launch {
                broker.active.collectLatest { active -> if (active) feed.feed(channel).collect { broker.accept(it.message, it.metadata) } }
            }
        }
    }

    val chain: List<Installed> get() = broker.chain()

    val interceptorShapes: List<InterceptorShape> get() = chain.map { it.shape }

    val shape: ChannelShape get() = ChannelShape.of(channel)

    override val shapeAttributes: Map<String, String>
        get() = buildMap {
            put("service", channel.id)
            put("channel", channel.id)
            put("family", channel.family)
            put("transport", link.transport)
            put("subscribers", broker.subscribers.joinToString())
            channel.deadLetter?.let { put("deadLetter", it.id) }
        }

    override fun toString(): String = "${super.toString()} [Channel] $channel transport=${link.transport}"
}

fun <M> Graph.channel(channel: Channel<M>, outlet: Link? = null, vararg interceptors: Interceptor): ChannelNode<M> =
    ChannelNode(this, channel, outlet, interceptors.toList()).also { attach(it) }

fun Graph.channels(): List<ChannelNode<*>> = nodes.filterIsInstance<ChannelNode<*>>()

class Publishes<M> internal constructor(val port: ConsumerPort<Publisher<M>>, val channel: Channel<M>) : Publisher<M> {
    private val wire = WireIdentity(port)

    val connected: Boolean get() = port.isConnected()

    suspend operator fun invoke(message: M) = publish(message)

    override suspend fun publish(message: M) {
        checkNotNull(port.impl) { "No $channel is wired to ${port.owner}. Add graph.channel(${channel.id}) and wire the graph before publishing." }
        val edge = wire()
        port.suspended {
            if (edge.isEmpty()) this.publish(message)
            else withContext(CallAttributes(mapOf(ServiceCall.EdgeAttribute to edge))) { this@suspended.publish(message) }
        }
    }
}

class Subscribes<M> internal constructor(
    val port: ConsumerPort<Broker<M>>,
    val channel: Channel<M>,
    val name: String,
    private val concurrency: Int,
    private val handler: suspend (M) -> Unit,
) {
    private val subscription = atomic<Subscription?>(null)

    val active: Boolean get() = subscription.value != null

    init {
        port.owner.addPortEventListener { event ->
            if (event.port === port) when (event) {
                is PortEvent.Connected -> port.impl?.subscribe(name, concurrency, handler)?.let { subscription.getAndSet(it)?.cancel() }
                is PortEvent.Disconnected -> subscription.getAndSet(null)?.cancel()
                is PortEvent.Created -> Unit
            }
        }
    }
}

fun <M> PortCapability.publishes(channel: Channel<M>) =
    PropertyDelegateProvider<PortCapability, PortDelegate<Publishes<M>>> { node, _ ->
        val publishes = Publishes(node.registerConsumer<Publisher<M>>(Key(""), publishType(channel)), channel)
        PortDelegate { _, _ -> publishes }
    }

fun <M> PortCapability.subscribes(channel: Channel<M>, name: String? = null, concurrency: Int = 1, handler: suspend (M) -> Unit) =
    PropertyDelegateProvider<PortCapability, PortDelegate<Subscribes<M>>> { node, property ->
        val subscribes = Subscribes(node.registerConsumer<Broker<M>>(Key(""), subscribeType(channel)), channel, name ?: property.name, concurrency, handler)
        PortDelegate { _, _ -> subscribes }
    }

fun publishType(channel: Channel<*>): Type = Type("publish:${channel.id}")

fun subscribeType(channel: Channel<*>): Type = Type("subscribe:${channel.id}")

fun Graph.socket(
    name: String,
    exposed: List<Channel<*>> = emptyList(),
    attributes: Map<String, Any?> = emptyMap(),
    send: suspend (String) -> Unit,
): SocketSession {
    val allowed = exposed.map { it.id }.toSet()
    return SocketSession(name, served(), channels().filter { it.channel.id in allowed }.map { it.broker }, coroutineScope, attributes, send)
}
