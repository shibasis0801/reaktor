@file:Suppress("unused", "PropertyName")

package dev.shibasis.reaktor.cloudflare

import js.collections.JsIterable
import js.core.ReadonlyArray
import kotlin.js.Date
import kotlin.js.Promise

/** Content types supported when enqueuing messages. */
typealias QueueContentType = String

/** Cloudflare Queue binding. */
external interface Queue<Body> {
    fun send(message: Body, options: QueueSendOptions = definedExternally): Promise<Unit>
    fun sendBatch(
        messages: JsIterable<MessageSendRequest<Body>>,
        options: QueueSendBatchOptions = definedExternally,
    ): Promise<Unit>
}

/** Per-message enqueue configuration. */
external interface QueueSendOptions {
    var contentType: QueueContentType?
    var delaySeconds: Double?
}

/** Batch level enqueue configuration. */
external interface QueueSendBatchOptions {
    var delaySeconds: Double?
}

/** Description of an outbound queue message. */
external interface MessageSendRequest<Body> {
    var body: Body
    var contentType: QueueContentType?
    var delaySeconds: Double?
}

/** Retry configuration that can be applied to individual queue messages. */
external interface QueueRetryOptions {
    var delaySeconds: Double?
}

/** Incoming queue message delivered to a worker. */
external interface Message<Body> {
    val id: String
    val timestamp: Date
    val body: Body
    val attempts: Int
    fun retry(options: QueueRetryOptions = definedExternally)
    fun ack()
}

/** Event delivered to queue handlers. */
external interface QueueEvent<Body> : ExtendableEvent {
    val messages: ReadonlyArray<Message<Body>>
    val queue: String
    fun retryAll(options: QueueRetryOptions = definedExternally)
    fun ackAll()
}

/** Batch of messages passed to the exported queue handler. */
external interface MessageBatch<Body> {
    val messages: ReadonlyArray<Message<Body>>
    val queue: String
    fun retryAll(options: QueueRetryOptions = definedExternally)
    fun ackAll()
}
