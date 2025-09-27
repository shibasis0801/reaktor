@file:JsModule("cloudflare:workers")
@file:JsNonModule
@file:Suppress("unused", "INTERFACE_WITH_SUPERCLASS")

package dev.shibasis.reaktor.cloudflare

import js.core.ReadonlyArray
import org.w3c.fetch.Request

/** Wrapper class used to mark RPC stubs when exchanging bindings. */
external class RpcStub<T>(value: T)

/** Base class for RPC targets exposed by the runtime. */
external abstract class RpcTarget

/**
 * Abstract entrypoint that mirrors the TypeScript `WorkerEntrypoint` helper.
 */
external abstract class WorkerEntrypoint<Env>(protected val ctx: ExecutionContext, protected val env: Env) {
    open fun fetch(request: Request): WorkerResponse?
    open fun tail(events: ReadonlyArray<TraceItem>): Any?
    open fun trace(traces: ReadonlyArray<TraceItem>): Any?
    open fun scheduled(controller: ScheduledController): Any?
    open fun queue(batch: MessageBatch<Any?>): Any?
    open fun test(controller: TestController): Any?
}

/**
 * Abstract durable object entrypoint that aligns with `cloudflare:workers` helpers.
 */
external abstract class DurableObjectEntrypoint<Env>(protected val ctx: DurableObjectState, protected val env: Env) {
    open fun fetch(request: Request): WorkerResponse?
    open fun alarm(): Any?
    open fun webSocketMessage(ws: org.w3c.dom.WebSocket, message: Any?): Any?
    open fun webSocketClose(ws: org.w3c.dom.WebSocket, code: Int, reason: String, wasClean: Boolean): Any?
    open fun webSocketError(ws: org.w3c.dom.WebSocket, error: Any?): Any?
}
