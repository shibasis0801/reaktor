package dev.shibasis.reaktor.graph.service

import kotlin.js.JsExport

@JsExport
enum class ServiceTransport {
    HTTP,
    LOCAL,
    PEER,
    PUBSUB,
    QUEUE,
    WORKFLOW,
}

@JsExport
data class ServiceEndpoint(
    val transport: ServiceTransport,
    val address: String,
    val operation: String = address,
    val method: HttpMethod? = null,
) {
    val portKey: String
        get() = operation

    val portType: String
        get() = when (transport) {
            ServiceTransport.HTTP -> "${transport.name}:${requireNotNull(method).name}:$operation"
            else -> "${transport.name}:$operation"
        }

    companion object {
        fun http(
            method: HttpMethod,
            route: String,
            operation: String = route,
        ): ServiceEndpoint = ServiceEndpoint(
            transport = ServiceTransport.HTTP,
            address = route,
            operation = operation,
            method = method,
        )

        fun local(operation: String): ServiceEndpoint =
            ServiceEndpoint(ServiceTransport.LOCAL, operation, operation)

        fun peer(operation: String): ServiceEndpoint =
            ServiceEndpoint(ServiceTransport.PEER, operation, operation)

        fun pubSub(topic: String): ServiceEndpoint =
            ServiceEndpoint(ServiceTransport.PUBSUB, topic, topic)

        fun queue(name: String): ServiceEndpoint =
            ServiceEndpoint(ServiceTransport.QUEUE, name, name)

        fun workflow(name: String): ServiceEndpoint =
            ServiceEndpoint(ServiceTransport.WORKFLOW, name, name)
    }
}
