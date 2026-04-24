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

/*
Queues are important for
1. decouple servers and make things async, so your server still remains fast. tasks can be prioritised / deferred etc
2. buffering requests until the receiving server is up (maybe it crashed or is overloaded)
3. replaying messages when the server comes up
4. you don't need to know the servers address
5. send one message to multiple services
6. sideline queues help you with error recovery, you can also drop down to manual fixes. (for example if apple login failed, we can manually create the user)
7. unify stuff across a lot of systems
8. rate limiting, etc.

It's a critical primitive.

PubSub vs Queue ?
It's mostly a way of using queues, most support both (gcp pub) but some don't (cf queues)

pubsub is when you want an event to do multiple things. (user login -> send notification, create fcm token, create file_storage, etc)
queue is when you want to things one after another. (user uploaded photo -> compress -> moderate -> store/reject)

if you want your message to be consumed by only one consumer, you send it to a queue
if you want multiple consumers to consumer it, you send it to a topic

generally you want a mix, that's where gcp pubsub comes.

*/