package dev.shibasis.reaktor.graph.service

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.Type
import dev.shibasis.reaktor.portgraph.port.registerProvider
import kotlin.js.JsExport

/**
 * A [BasicNode] that wraps a [Service] and exposes each of its [RequestHandler]s as a
 * [ProviderPort].
 *
 * ## Port Matching
 * - **Key** = handler operation (defaults to the route)
 * - **Type** = transport-specific port type (e.g. `"HTTP:POST:Auth.SignIn"`).
 *
 * ## Usage
 *
 * Both server and client Service implementations are wrapped the same way. The difference is in the
 * handler implementation (real vs HTTP proxy):
 *
 * ```kotlin
 * // Server side — provides real implementation
 * val authNode = graph.ServiceNode(AuthServer(loginInteractor))
 *
 * // Client side — provides HTTP-proxying implementation
 * val authNode = graph.ServiceNode(AuthServiceClient("https://api.example.com"))
 * ```
 *
 * Other nodes can `consumes<RequestHandler<In, Out>>()` with the same Key + Type and [autoWire]
 * [Graph.autoWire] (or explicit [connect]) will wire them up.
 *
 * Whether the handler executes business logic locally or proxies over HTTP is completely
 * transparent to the consuming node.
 */
@JsExport
open class ServiceNode(
        graph: Graph,
        val service: Service,
        label: String = service::class.simpleName ?: "ServiceNode"
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
        return "${super.toString()} [Service] baseUrl='${service.baseUrl}' handlers=${service.handlers.size}"
    }
}

/** DSL helper to create a [ServiceNode], attach it to this [Graph], and return it. */
fun Graph.ServiceNode(service: Service, label: String = ""): ServiceNode {
    val node = ServiceNode(this, service, label)
    attach(node)
    return node
}
