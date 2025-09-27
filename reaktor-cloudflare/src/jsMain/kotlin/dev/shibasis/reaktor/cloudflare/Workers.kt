@file:Suppress(
    "INTERFACE_WITH_SUPERCLASS",
    "NESTED_CLASS_IN_EXTERNAL_INTERFACE",
    "PropertyName",
    "FunctionName",
    "UNUSED_TYPEALIAS_PARAMETER",
)

package dev.shibasis.reaktor.cloudflare

import js.core.ReadonlyArray
import kotlin.js.ArrayBuffer
import kotlin.js.Promise
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventInit
import org.w3c.fetch.Request

/**
 * Cloudflare specific extension of the DOM `ExtendableEvent` type.
 */
external open class ExtendableEvent(type: String, init: EventInit = definedExternally) : Event {
    fun waitUntil(promise: Promise<Any?>)
}

/**
 * Execution context provided to worker entrypoints and queue handlers.
 */
external interface ExecutionContext {
    fun waitUntil(promise: Promise<Any?>)
    fun passThroughOnException()
}

/** Marker interface describing trace log entries supplied to tail handlers. */
external interface TraceItem

/**
 * Generic request handler result returned by Cloudflare callbacks.
 */
typealias WorkerResponse = Any /* Response | Promise<Response> */

/** Fetch handler signature exported by a worker module. */
typealias FetchHandler<Env> = (Request, Env, ExecutionContext) -> WorkerResponse

/** Scheduled handler signature exported by a worker module. */
typealias ScheduledHandler<Env> = (ScheduledController, Env, ExecutionContext) -> Any?

/** Queue handler signature exported by a worker module. */
typealias QueueHandler<Env, Message> = (MessageBatch<Message>, Env, ExecutionContext) -> Any?

/** Tail handler signature exported by a worker module. */
typealias TailHandler<Env> = (ReadonlyArray<TraceItem>, Env, ExecutionContext) -> Any?

/** Trace handler signature exported by a worker module. */
typealias TraceHandler<Env> = (ReadonlyArray<TraceItem>, Env, ExecutionContext) -> Any?

/** Test handler signature exported by a worker module. */
typealias TestHandler<Env> = (TestController, Env, ExecutionContext) -> Any?

/** Email handler signature exported by a worker module. */
typealias EmailHandler<Env> = (EmailMessage, Env, ExecutionContext) -> Any?

/** Controller exposed to scheduled handlers. */
external interface ScheduledController {
    val scheduledTime: Double
    val cron: String
    fun noRetry()
}

/** Base event dispatched for cron triggers. */
external abstract class ScheduledEvent : ExtendableEvent {
    val scheduledTime: Double
    val cron: String
    fun noRetry()
}

/** Queue controller used by Cloudflare's test harness. */
external interface TestController

/** Cloudflare email event payload. */
external interface EmailMessage {
    val from: String
    val to: ReadonlyArray<String>
    val headers: ReadonlyArray<dynamic>
    val raw: ArrayBuffer
}

/**
 * Set of optional lifecycle handlers exported by a worker bundle.
 */
external interface ExportedHandler<Env, QueueMessage, HostMetadata> {
    var fetch: FetchHandler<Env>?
    var scheduled: ScheduledHandler<Env>?
    var queue: QueueHandler<Env, QueueMessage>?
    var tail: TailHandler<Env>?
    var trace: TraceHandler<Env>?
    var test: TestHandler<Env>?
    var email: EmailHandler<Env>?
}

/**
 * Durable object methods invoked via WebSocket lifecycle events.
 */
external interface DurableObjectWebSocketHandlers {
    fun fetch(request: Request): WorkerResponse
    fun alarm(): Any?
    fun webSocketMessage(ws: WebSocket, message: Any?): Any?
    fun webSocketClose(ws: WebSocket, code: Int, reason: String, wasClean: Boolean): Any?
    fun webSocketError(ws: WebSocket, error: Any?): Any?
}
