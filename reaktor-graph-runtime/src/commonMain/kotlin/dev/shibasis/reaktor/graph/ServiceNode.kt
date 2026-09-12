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
import dev.shibasis.reaktor.service.HttpMethod
import dev.shibasis.reaktor.service.OptionsHandler
import dev.shibasis.reaktor.service.PatchHandler
import dev.shibasis.reaktor.service.PostHandler
import dev.shibasis.reaktor.service.PutHandler
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.RequestHandler
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.Service
import dev.shibasis.reaktor.service.ServiceEndpoint
import dev.shibasis.reaktor.service.serviceOperationKey
import kotlin.js.JsExport
import kotlin.properties.PropertyDelegateProvider
import kotlin.reflect.KProperty1

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
        private val serviceLabel: String = service::class.simpleName ?: "ServiceNode",
) : BasicNode(graph) {
    init {
        service.handlers.forEach { handler ->
            registerProvider(
                    Key(handler.endpoint.portKey),
                    Type(handler.endpoint.portType),
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

    operator fun <R> invoke(fn: H.() -> R): R = fn(requireImpl())

    suspend fun <R> suspended(fn: suspend H.() -> R): R = fn(requireImpl())

    suspend operator fun invoke(request: In): Out = requireImpl()(request)

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
    method: HttpMethod,
) = PropertyDelegateProvider<PortCapability, PortDelegate<ServiceApiPort<In, Out, H>>> { thisRef, _ ->
    val operation = serviceOperationKey(kSerializer<In>(), propertyName)
    val port = thisRef.registerConsumer<H>(
        Key(operation),
        Type(ServiceEndpoint.http(method, "", operation).portType),
    )
    val api = ServiceApiPort(port)
    PortDelegate { _, _ -> api }
}

inline fun <reified S : Service, reified In : Request, reified Out : Response> PortCapability.api(
    route: KProperty1<S, GetHandler<In, Out>>,
    marker: GetApi = GetApi,
) = serviceApi<In, Out, GetHandler<In, Out>>(route.name, HttpMethod.GET)

inline fun <reified S : Service, reified In : Request, reified Out : Response> PortCapability.api(
    route: KProperty1<S, PostHandler<In, Out>>,
    marker: PostApi = PostApi,
) = serviceApi<In, Out, PostHandler<In, Out>>(route.name, HttpMethod.POST)

inline fun <reified S : Service, reified In : Request, reified Out : Response> PortCapability.api(
    route: KProperty1<S, PutHandler<In, Out>>,
    marker: PutApi = PutApi,
) = serviceApi<In, Out, PutHandler<In, Out>>(route.name, HttpMethod.PUT)

inline fun <reified S : Service, reified In : Request, reified Out : Response> PortCapability.api(
    route: KProperty1<S, DeleteHandler<In, Out>>,
    marker: DeleteApi = DeleteApi,
) = serviceApi<In, Out, DeleteHandler<In, Out>>(route.name, HttpMethod.DELETE)

inline fun <reified S : Service, reified In : Request, reified Out : Response> PortCapability.api(
    route: KProperty1<S, PatchHandler<In, Out>>,
    marker: PatchApi = PatchApi,
) = serviceApi<In, Out, PatchHandler<In, Out>>(route.name, HttpMethod.PATCH)

inline fun <reified S : Service, reified In : Request, reified Out : Response> PortCapability.api(
    route: KProperty1<S, OptionsHandler<In, Out>>,
    marker: OptionsApi = OptionsApi,
) = serviceApi<In, Out, OptionsHandler<In, Out>>(route.name, HttpMethod.OPTIONS)

inline fun <reified S : Service, reified In : Request, reified Out : Response> PortCapability.api(
    route: KProperty1<S, HeadHandler<In, Out>>,
    marker: HeadApi = HeadApi,
) = serviceApi<In, Out, HeadHandler<In, Out>>(route.name, HttpMethod.HEAD)
