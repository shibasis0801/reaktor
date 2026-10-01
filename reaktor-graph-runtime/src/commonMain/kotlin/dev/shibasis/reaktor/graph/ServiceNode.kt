@file:Suppress("UNUSED_PARAMETER")
package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.core.framework.kSerializer
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.PortCapability
import dev.shibasis.reaktor.portgraph.port.PortDelegate
import dev.shibasis.reaktor.portgraph.port.Type
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import dev.shibasis.reaktor.service.DeleteHandler
import dev.shibasis.reaktor.service.GetHandler
import dev.shibasis.reaktor.service.HeadHandler
import dev.shibasis.reaktor.service.OptionsHandler
import dev.shibasis.reaktor.service.PatchHandler
import dev.shibasis.reaktor.service.PostHandler
import dev.shibasis.reaktor.service.PutHandler
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.RequestHandler
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.Service
import dev.shibasis.reaktor.service.operationPortType
import dev.shibasis.reaktor.service.serviceOperationKey
import kotlin.js.JsExport
import kotlin.properties.PropertyDelegateProvider
import kotlin.reflect.KProperty1
import dev.shibasis.reaktor.graph.core.NodeKind

object GetApi
object PostApi
object PutApi
object DeleteApi
object PatchApi
object OptionsApi
object HeadApi

@JsExport
open class ServiceNode(
        graph: Graph,
        val service: Service,
        val serviceLabel: String = service::class.simpleName ?: "ServiceNode",
) : BasicNode(graph) {
    override val kind: NodeKind get() = NodeKind.Service

    init {
        service.handlers.forEach { handler ->
            registerProvider(
                    Key(handler.endpoint.portKey),
                    Type(operationPortType(handler.endpoint.operation, handler.requestSerializer, handler.responseSerializer)),
                    handler
            )
        }
    }

    override fun toString(): String {
        return "${super.toString()} [Service] label='$serviceLabel' baseUrl='${service.baseUrl}' handlers=${service.handlers.size}"
    }
}

fun Graph.ServiceNode(service: Service, label: String = service::class.simpleName ?: "ServiceNode"): ServiceNode =
    ServiceNode(this, service, label).also { attach(it) }

class ServiceApiPort<In : Request, Out : Response, H : RequestHandler<In, Out>>(
    val consumer: ConsumerPort<H>,
) : AutoCloseable {
    val impl: H?
        get() = consumer.impl

    fun isConnected(): Boolean = consumer.isConnected()

    private fun requireImpl(): H = impl ?: error(
        "Service API endpoint [${consumer.type.type}] is not connected: " +
            "no handler is registered to route this call. Attach a ServiceNode that " +
            "provides this operation and connect its port before invoking. Port: $consumer",
    )

    operator fun invoke(): H = requireImpl()

    operator fun <R> invoke(fn: H.() -> R): R = requireImpl().let { consumer(fn) }

    suspend fun <R> suspended(fn: suspend H.() -> R): R = requireImpl().let { consumer.suspended(fn) }

    suspend operator fun invoke(request: In): Out = requireImpl().let { consumer.suspended { this(request) } }

    override fun close() {
        consumer.close()
    }

    override fun toString(): String = consumer.toString()
}

@PublishedApi
internal inline fun <
    reified In : Request,
    reified Out : Response,
    reified H : RequestHandler<In, Out>,
> PortCapability.serviceApi(
    propertyName: String,
) = PropertyDelegateProvider<PortCapability, PortDelegate<ServiceApiPort<In, Out, H>>> { thisRef, _ ->
    val operation = serviceOperationKey(kSerializer<In>(), propertyName)
    val port = thisRef.registerConsumer<H>(
        Key(operation),
        Type(operationPortType(operation, kSerializer<In>(), kSerializer<Out>())),
    )
    val api = ServiceApiPort(port)
    PortDelegate { _, _ -> api }
}

inline fun <reified S : Service, reified In : Request, reified Out : Response> PortCapability.api(
    route: KProperty1<S, GetHandler<In, Out>>,
    marker: GetApi = GetApi,
) = serviceApi<In, Out, GetHandler<In, Out>>(route.name)

inline fun <reified S : Service, reified In : Request, reified Out : Response> PortCapability.api(
    route: KProperty1<S, PostHandler<In, Out>>,
    marker: PostApi = PostApi,
) = serviceApi<In, Out, PostHandler<In, Out>>(route.name)

inline fun <reified S : Service, reified In : Request, reified Out : Response> PortCapability.api(
    route: KProperty1<S, PutHandler<In, Out>>,
    marker: PutApi = PutApi,
) = serviceApi<In, Out, PutHandler<In, Out>>(route.name)

inline fun <reified S : Service, reified In : Request, reified Out : Response> PortCapability.api(
    route: KProperty1<S, DeleteHandler<In, Out>>,
    marker: DeleteApi = DeleteApi,
) = serviceApi<In, Out, DeleteHandler<In, Out>>(route.name)

inline fun <reified S : Service, reified In : Request, reified Out : Response> PortCapability.api(
    route: KProperty1<S, PatchHandler<In, Out>>,
    marker: PatchApi = PatchApi,
) = serviceApi<In, Out, PatchHandler<In, Out>>(route.name)

inline fun <reified S : Service, reified In : Request, reified Out : Response> PortCapability.api(
    route: KProperty1<S, OptionsHandler<In, Out>>,
    marker: OptionsApi = OptionsApi,
) = serviceApi<In, Out, OptionsHandler<In, Out>>(route.name)

inline fun <reified S : Service, reified In : Request, reified Out : Response> PortCapability.api(
    route: KProperty1<S, HeadHandler<In, Out>>,
    marker: HeadApi = HeadApi,
) = serviceApi<In, Out, HeadHandler<In, Out>>(route.name)
