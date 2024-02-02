@file:Suppress("INTERFACE_WITH_SUPERCLASS", "OVERRIDING_FINAL_MEMBER", "RETURN_TYPE_MISMATCH_ON_OVERRIDE", "CONFLICTING_OVERLOADS")

package dev.shibasis.reaktor.core.server.externals

import js.core.AsyncIterable
import js.core.JsIterable
import js.core.Record
import kotlin.js.*
import js.buffer.*
import js.typedarrays.Uint8Array
import web.file.File

typealias DOMException = Error

@JsExport
external interface Env {
    val DB: D1Database
    val FILE: R2Bucket

}

@JsExport
external interface ExecutionContext {
    fun waitUntil(promise: Promise<Any>)
    fun passThroughOnException()
}

external interface WorkerGlobalScopeEventMap {
    var fetch: FetchEvent
    var scheduled: ScheduledEvent
    var queue: QueueEvent__0
    var unhandledrejection: PromiseRejectionEvent
    var rejectionhandled: PromiseRejectionEvent
}

external open class WorkerGlobalScope : EventTarget<Record<String, Event>> {
    open var EventTarget: Any
}

external interface Console {
    fun clear()
    fun count(label: String = definedExternally)
    fun countReset(label: String = definedExternally)
    fun debug(vararg data: Any)
    fun dir(item: Any = definedExternally, options: Any = definedExternally)
    fun dirxml(vararg data: Any)
    fun error(vararg data: Any)
    fun group(vararg data: Any)
    fun groupCollapsed(vararg data: Any)
    fun groupEnd()
    fun info(vararg data: Any)
    fun log(vararg data: Any)
    fun table(tabularData: Any = definedExternally, properties: Array<String> = definedExternally)
    fun time(label: String = definedExternally)
    fun timeEnd(label: String = definedExternally)
    fun timeLog(label: String = definedExternally, vararg data: Any)
    fun timeStamp(label: String = definedExternally)
    fun trace(vararg data: Any)
    fun warn(vararg data: Any)
}

external var console: Console

external interface ServiceWorkerGlobalScope : WorkerGlobalScope {
    var DOMException: Any
    var WorkerGlobalScope: Any
    fun btoa(data: String): String
    fun atob(data: String): String
    fun setTimeout(callback: (args: Any) -> Unit, msDelay: Number = definedExternally): Number
    fun setTimeout(callback: (args: Any) -> Unit): Number
    fun <Args : Array<Any>> setTimeout(callback: (args: Args) -> Unit, msDelay: Number = definedExternally, vararg args: Args): Number
    fun clearTimeout(timeoutId: Number?)
    fun setInterval(callback: (args: Any) -> Unit, msDelay: Number = definedExternally): Number
    fun setInterval(callback: (args: Any) -> Unit): Number
    fun <Args : Array<Any>> setInterval(callback: (args: Args) -> Unit, msDelay: Number = definedExternally, vararg args: Args): Number
    fun clearInterval(timeoutId: Number?)
    fun queueMicrotask(task: Function<*>)
    fun <T> structuredClone(value: T, options: StructuredSerializeOptions = definedExternally): T
    fun fetch(input: Request<Any, Any /* IncomingRequestCfPropertiesBase & IncomingRequestCfPropertiesBotManagementEnterprise & IncomingRequestCfPropertiesCloudflareForSaaSEnterprise<CfHostMetadata> & IncomingRequestCfPropertiesGeographicInformation & IncomingRequestCfPropertiesCloudflareAccessOrApiShield | RequestInitCfProperties */>, init: RequestInit<RequestInitCfProperties> = definedExternally): Promise<Response>
    fun fetch(input: Request<Any, Any /* IncomingRequestCfPropertiesBase & IncomingRequestCfPropertiesBotManagementEnterprise & IncomingRequestCfPropertiesCloudflareForSaaSEnterprise<CfHostMetadata> & IncomingRequestCfPropertiesGeographicInformation & IncomingRequestCfPropertiesCloudflareAccessOrApiShield | RequestInitCfProperties */>): Promise<Response>
    fun fetch(input: String, init: RequestInit<RequestInitCfProperties> = definedExternally): Promise<Response>
    fun fetch(input: String): Promise<Response>
    fun fetch(input: URL, init: RequestInit<RequestInitCfProperties> = definedExternally): Promise<Response>
    fun fetch(input: URL): Promise<Response>
    var self: ServiceWorkerGlobalScope
    var crypto: Crypto
    var caches: CacheStorage
    var scheduler: Scheduler
    var performance: Performance
    var origin: String
    var Event: Any
    var ExtendableEvent: Any
    var PromiseRejectionEvent: Any
    var FetchEvent: Any
    var TailEvent: Any
    var TraceEvent: Any
    var ScheduledEvent: Any
    var MessageEvent: Any
    var CloseEvent: Any
    var ReadableStreamDefaultReader: Any
    var ReadableStreamBYOBReader: Any
    var ReadableStream: Any
    var WritableStream: Any
    var WritableStreamDefaultWriter: Any
    var TransformStream: Any
    var ByteLengthQueuingStrategy: Any
    var CountQueuingStrategy: Any
    var CompressionStream: Any
    var DecompressionStream: Any
    var TextEncoderStream: Any
    var TextDecoderStream: Any
    var Headers: Any
    var Body: Any
    var Request: Any
    var Response: Any
    var WebSocket: Any
    var WebSocketPair: Any
    var WebSocketRequestResponsePair: Any
    var AbortController: Any
    var AbortSignal: Any
    var TextDecoder: Any
    var TextEncoder: Any
    var URL: Any
    var URLSearchParams: Any
    var URLPattern: Any
    var Blob: Any
    var File: Any
    var FormData: Any
    var Crypto: Any
    var SubtleCrypto: Any
    var CryptoKey: Any
    var CacheStorage: Any
    var Cache: Any
    var FixedLengthStream: Any
    var IdentityTransformStream: Any
    var HTMLRewriter: Any
}

external fun <Type : String> addEventListener(type: Type, handler: EventListener<Any>, options: EventTargetAddEventListenerOptions = definedExternally)

external fun <Type : String> addEventListener(type: Type, handler: EventListener<Any>)

external fun <Type : String> addEventListener(type: Type, handler: EventListener<Any>, options: Boolean = definedExternally)

external fun <Type : String> addEventListener(type: Type, handler: EventListenerObject<Event>, options: EventTargetAddEventListenerOptions = definedExternally)

external fun <Type : String> addEventListener(type: Type, handler: EventListenerObject<Event>)

external fun <Type : String> addEventListener(type: Type, handler: EventListenerObject<Event>, options: Boolean = definedExternally)

external fun <Type : String> removeEventListener(type: Type, handler: EventListener<Any>, options: EventTargetEventListenerOptions = definedExternally)

external fun <Type : String> removeEventListener(type: Type, handler: EventListener<Any>)

external fun <Type : String> removeEventListener(type: Type, handler: EventListener<Any>, options: Boolean = definedExternally)

external fun <Type : String> removeEventListener(type: Type, handler: EventListenerObject<Event>, options: EventTargetEventListenerOptions = definedExternally)

external fun <Type : String> removeEventListener(type: Type, handler: EventListenerObject<Event>)

external fun <Type : String> removeEventListener(type: Type, handler: EventListenerObject<Event>, options: Boolean = definedExternally)

external fun dispatchEvent(event: FetchEvent): Boolean

external fun dispatchEvent(event: ScheduledEvent): Boolean

external fun dispatchEvent(event: QueueEvent__0): Boolean

external fun dispatchEvent(event: PromiseRejectionEvent): Boolean

external fun btoa(data: String): String

external fun atob(data: String): String

external fun setTimeout(callback: (args: Any) -> Unit, msDelay: Number = definedExternally): Number

external fun setTimeout(callback: (args: Any) -> Unit): Number

external fun <Args : Array<Any>> setTimeout(callback: (args: Args) -> Unit, msDelay: Number = definedExternally, vararg args: Args): Number

external fun clearTimeout(timeoutId: Number?)

external fun setInterval(callback: (args: Any) -> Unit, msDelay: Number = definedExternally): Number

external fun setInterval(callback: (args: Any) -> Unit): Number

external fun <Args : Array<Any>> setInterval(callback: (args: Args) -> Unit, msDelay: Number = definedExternally, vararg args: Args): Number

external fun clearInterval(timeoutId: Number?)

external fun queueMicrotask(task: Function<*>)

external fun <T> structuredClone(value: T, options: StructuredSerializeOptions = definedExternally): T

external fun fetch(input: Request<Any, Any /* IncomingRequestCfPropertiesBase & IncomingRequestCfPropertiesBotManagementEnterprise & IncomingRequestCfPropertiesCloudflareForSaaSEnterprise<CfHostMetadata> & IncomingRequestCfPropertiesGeographicInformation & IncomingRequestCfPropertiesCloudflareAccessOrApiShield | RequestInitCfProperties */>, init: RequestInit<RequestInitCfProperties> = definedExternally): Promise<Response>

external fun fetch(input: Request<Any, Any /* IncomingRequestCfPropertiesBase & IncomingRequestCfPropertiesBotManagementEnterprise & IncomingRequestCfPropertiesCloudflareForSaaSEnterprise<CfHostMetadata> & IncomingRequestCfPropertiesGeographicInformation & IncomingRequestCfPropertiesCloudflareAccessOrApiShield | RequestInitCfProperties */>): Promise<Response>

external fun fetch(input: String, init: RequestInit<RequestInitCfProperties> = definedExternally): Promise<Response>

external fun fetch(input: String): Promise<Response>

external fun fetch(input: URL, init: RequestInit<RequestInitCfProperties> = definedExternally): Promise<Response>

external fun fetch(input: URL): Promise<Response>

external var self: ServiceWorkerGlobalScope

external var crypto: Crypto

external var caches: CacheStorage

external var scheduler: Scheduler

external var performance: Performance

external var origin: String

external interface TestController


typealias ExportedHandlerFetchHandler<Env, CfHostMetadata> = (request: Request<CfHostMetadata, IncomingRequestCfPropertiesBase /* IncomingRequestCfPropertiesBase & IncomingRequestCfPropertiesBotManagementEnterprise & IncomingRequestCfPropertiesCloudflareForSaaSEnterprise<CfHostMetadata> & IncomingRequestCfPropertiesGeographicInformation & IncomingRequestCfPropertiesCloudflareAccessOrApiShield */>, env: Env, ctx: ExecutionContext) -> dynamic

typealias ExportedHandlerTailHandler<Env> = (events: Array<TraceItem>, env: Env, ctx: ExecutionContext) -> dynamic

typealias ExportedHandlerTraceHandler<Env> = (traces: Array<TraceItem>, env: Env, ctx: ExecutionContext) -> dynamic

typealias ExportedHandlerScheduledHandler<Env> = (controller: ScheduledController, env: Env, ctx: ExecutionContext) -> dynamic

typealias ExportedHandlerQueueHandler<Env, Message> = (batch: MessageBatch<Message>, env: Env, ctx: ExecutionContext) -> dynamic

typealias ExportedHandlerTestHandler<Env> = (controller: TestController, env: Env, ctx: ExecutionContext) -> dynamic

external interface ExportedHandler<Env, QueueHandlerMessage, CfHostMetadata> {
    var fetch: ExportedHandlerFetchHandler<Env, CfHostMetadata>?
        get() = definedExternally
        set(value) = definedExternally
    var tail: ExportedHandlerTailHandler<Env>?
        get() = definedExternally
        set(value) = definedExternally
    var trace: ExportedHandlerTraceHandler<Env>?
        get() = definedExternally
        set(value) = definedExternally
    var scheduled: ExportedHandlerScheduledHandler<Env>?
        get() = definedExternally
        set(value) = definedExternally
    var test: ExportedHandlerTestHandler<Env>?
        get() = definedExternally
        set(value) = definedExternally
    var email: EmailExportedHandler<Env>?
        get() = definedExternally
        set(value) = definedExternally
    var queue: ExportedHandlerQueueHandler<Env, QueueHandlerMessage>?
        get() = definedExternally
        set(value) = definedExternally
}

external interface StructuredSerializeOptions {
    var transfer: Array<Any>?
        get() = definedExternally
        set(value) = definedExternally
}

external open class PromiseRejectionEvent(type: String, init: EventInit = definedExternally) : Event {
    open var promise: Promise<Any>
    open var reason: Any
}

external interface Performance {
    var timeOrigin: Number
    fun now(): Number
}

external interface DurableObject {
    fun fetch(request: Request__0): dynamic /* Response | Promise<Response> */
    val alarm: (() -> dynamic)?
}

external interface DurableObjectStub : Fetcher {
    var id: DurableObjectId
    var name: String?
        get() = definedExternally
        set(value) = definedExternally
}

external interface DurableObjectId {
    override fun toString(): String
    fun equals(other: DurableObjectId): Boolean
    var name: String?
        get() = definedExternally
        set(value) = definedExternally
}

external interface DurableObjectNamespace {
    fun newUniqueId(options: DurableObjectNamespaceNewUniqueIdOptions = definedExternally): DurableObjectId
    fun idFromName(name: String): DurableObjectId
    fun idFromString(id: String): DurableObjectId
    fun get(id: DurableObjectId, options: DurableObjectNamespaceGetDurableObjectOptions = definedExternally): DurableObjectStub
    fun jurisdiction(jurisdiction: String /* "eu" | "fedramp" */): DurableObjectNamespace
}

external interface DurableObjectNamespaceNewUniqueIdOptions {
    var jurisdiction: String? /* "eu" | "fedramp" */
        get() = definedExternally
        set(value) = definedExternally
}

external interface DurableObjectNamespaceGetDurableObjectOptions {
    var locationHint: String? /* "wnam" | "enam" | "sam" | "weur" | "eeur" | "apac" | "oc" | "afr" | "me" */
        get() = definedExternally
        set(value) = definedExternally
}

external interface DurableObjectState {
    fun waitUntil(promise: Promise<Any>)
    var id: DurableObjectId
    var storage: DurableObjectStorage
    fun <T> blockConcurrencyWhile(callback: () -> Promise<T>): Promise<T>
    fun acceptWebSocket(ws: WebSocket, tags: Array<String> = definedExternally)
    fun getWebSockets(tag: String = definedExternally): Array<WebSocket>
    fun setWebSocketAutoResponse(maybeReqResp: WebSocketRequestResponsePair = definedExternally)
    fun getWebSocketAutoResponse(): WebSocketRequestResponsePair?
    fun getWebSocketAutoResponseTimestamp(ws: WebSocket): Date?
}

external interface DurableObjectTransaction {
    fun <T> get(key: String, options: DurableObjectGetOptions = definedExternally): Promise<T?>
    fun <T> get(key: String): Promise<T?>
    fun <T> get(keys: Array<String>, options: DurableObjectGetOptions = definedExternally): Promise<Map<String, T>>
    fun <T> get(keys: Array<String>): Promise<Map<String, T>>
    fun <T> list(options: DurableObjectListOptions = definedExternally): Promise<Map<String, T>>
    fun <T> put(key: String, value: T, options: DurableObjectPutOptions = definedExternally): Promise<Unit>
    fun <T> put(key: String, value: T): Promise<Unit>
    fun <T: Any> put(entries: Record<String, T>, options: DurableObjectPutOptions = definedExternally): Promise<Unit>
    fun <T: Any> put(entries: Record<String, T>): Promise<Unit>
    fun delete(key: String, options: DurableObjectPutOptions = definedExternally): Promise<Boolean>
    fun delete(key: String): Promise<Boolean>
    fun delete(keys: Array<String>, options: DurableObjectPutOptions = definedExternally): Promise<Number>
    fun delete(keys: Array<String>): Promise<Number>
    fun rollback()
    fun getAlarm(options: DurableObjectGetAlarmOptions = definedExternally): Promise<Number?>
    fun setAlarm(scheduledTime: Number, options: DurableObjectSetAlarmOptions = definedExternally): Promise<Unit>
    fun setAlarm(scheduledTime: Number): Promise<Unit>
    fun setAlarm(scheduledTime: Date, options: DurableObjectSetAlarmOptions = definedExternally): Promise<Unit>
    fun setAlarm(scheduledTime: Date): Promise<Unit>
    fun deleteAlarm(options: DurableObjectSetAlarmOptions = definedExternally): Promise<Unit>
}

external interface DurableObjectStorage {
    fun <T> get(key: String, options: DurableObjectGetOptions = definedExternally): Promise<T?>
    fun <T> get(key: String): Promise<T?>
    fun <T> get(keys: Array<String>, options: DurableObjectGetOptions = definedExternally): Promise<Map<String, T>>
    fun <T> get(keys: Array<String>): Promise<Map<String, T>>
    fun <T> list(options: DurableObjectListOptions = definedExternally): Promise<Map<String, T>>
    fun <T> put(key: String, value: T, options: DurableObjectPutOptions = definedExternally): Promise<Unit>
    fun <T> put(key: String, value: T): Promise<Unit>
    fun <T: Any> put(entries: Record<String, T>, options: DurableObjectPutOptions = definedExternally): Promise<Unit>
    fun <T: Any> put(entries: Record<String, T>): Promise<Unit>
    fun delete(key: String, options: DurableObjectPutOptions = definedExternally): Promise<Boolean>
    fun delete(key: String): Promise<Boolean>
    fun delete(keys: Array<String>, options: DurableObjectPutOptions = definedExternally): Promise<Number>
    fun delete(keys: Array<String>): Promise<Number>
    fun deleteAll(options: DurableObjectPutOptions = definedExternally): Promise<Unit>
    fun <T> transaction(closure: (txn: DurableObjectTransaction) -> Promise<T>): Promise<T>
    fun getAlarm(options: DurableObjectGetAlarmOptions = definedExternally): Promise<Number?>
    fun setAlarm(scheduledTime: Number, options: DurableObjectSetAlarmOptions = definedExternally): Promise<Unit>
    fun setAlarm(scheduledTime: Number): Promise<Unit>
    fun setAlarm(scheduledTime: Date, options: DurableObjectSetAlarmOptions = definedExternally): Promise<Unit>
    fun setAlarm(scheduledTime: Date): Promise<Unit>
    fun deleteAlarm(options: DurableObjectSetAlarmOptions = definedExternally): Promise<Unit>
    fun sync(): Promise<Unit>
    fun <T> transactionSync(closure: () -> T): T
}

external interface DurableObjectListOptions {
    var start: String?
        get() = definedExternally
        set(value) = definedExternally
    var startAfter: String?
        get() = definedExternally
        set(value) = definedExternally
    var end: String?
        get() = definedExternally
        set(value) = definedExternally
    var prefix: String?
        get() = definedExternally
        set(value) = definedExternally
    var reverse: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var limit: Number?
        get() = definedExternally
        set(value) = definedExternally
    var allowConcurrency: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var noCache: Boolean?
        get() = definedExternally
        set(value) = definedExternally
}

external interface DurableObjectGetOptions {
    var allowConcurrency: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var noCache: Boolean?
        get() = definedExternally
        set(value) = definedExternally
}

external interface DurableObjectGetAlarmOptions {
    var allowConcurrency: Boolean?
        get() = definedExternally
        set(value) = definedExternally
}

external interface DurableObjectPutOptions {
    var allowConcurrency: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var allowUnconfirmed: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var noCache: Boolean?
        get() = definedExternally
        set(value) = definedExternally
}

external interface DurableObjectSetAlarmOptions {
    var allowConcurrency: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var allowUnconfirmed: Boolean?
        get() = definedExternally
        set(value) = definedExternally
}

external open class WebSocketRequestResponsePair(request: String, response: String)

external interface AnalyticsEngineDataset {
    fun writeDataPoint(event: AnalyticsEngineDataPoint = definedExternally)
}

external interface AnalyticsEngineDataPoint {
    var indexes: Array<dynamic /* ArrayBuffer? | String? */>?
        get() = definedExternally
        set(value) = definedExternally
    var doubles: Array<Number>?
        get() = definedExternally
        set(value) = definedExternally
    var blobs: Array<dynamic /* ArrayBuffer? | String? */>?
        get() = definedExternally
        set(value) = definedExternally
}

external open class Event(type: String, init: EventInit = definedExternally) {
    open var type: String
    open var eventPhase: Number
    open var composed: Boolean
    open var bubbles: Boolean
    open var cancelable: Boolean
    open var defaultPrevented: Boolean
    open var returnValue: Boolean
    open var currentTarget: EventTarget__0
    open var srcElement: EventTarget__0
    open var timeStamp: Number
    open var isTrusted: Boolean
    open var cancelBubble: Boolean
    open fun stopImmediatePropagation()
    open fun preventDefault()
    open fun stopPropagation()
    open fun composedPath(): Array<EventTarget__0>

    companion object {
        var NONE: Number
        var CAPTURING_PHASE: Number
        var AT_TARGET: Number
        var BUBBLING_PHASE: Number
    }
}

external interface EventInit {
    var bubbles: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var cancelable: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var composed: Boolean?
        get() = definedExternally
        set(value) = definedExternally
}

typealias EventListener<EventType> = (event: EventType) -> Unit

external interface EventListenerObject<EventType : Event> {
    fun handleEvent(event: EventType)
}

external open class EventTarget<EventMap : Record<String, Event>> {
    open fun <Type : Any> addEventListener(type: Type, handler: EventListener<Any>, options: EventTargetAddEventListenerOptions = definedExternally)
    open fun <Type : Any> addEventListener(type: Type, handler: EventListener<Any>)
    open fun <Type : Any> addEventListener(type: Type, handler: EventListener<Any>, options: Boolean = definedExternally)
    open fun <Type : Any> addEventListener(type: Type, handler: EventListenerObject<Event>, options: EventTargetAddEventListenerOptions = definedExternally)
    open fun <Type : Any> addEventListener(type: Type, handler: EventListenerObject<Event>)
    open fun <Type : Any> addEventListener(type: Type, handler: EventListenerObject<Event>, options: Boolean = definedExternally)
    open fun <Type : Any> removeEventListener(type: Type, handler: EventListener<Any>, options: EventTargetEventListenerOptions = definedExternally)
    open fun <Type : Any> removeEventListener(type: Type, handler: EventListener<Any>)
    open fun <Type : Any> removeEventListener(type: Type, handler: EventListener<Any>, options: Boolean = definedExternally)
    open fun <Type : Any> removeEventListener(type: Type, handler: EventListenerObject<Event>, options: EventTargetEventListenerOptions = definedExternally)
    open fun <Type : Any> removeEventListener(type: Type, handler: EventListenerObject<Event>)
    open fun <Type : Any> removeEventListener(type: Type, handler: EventListenerObject<Event>, options: Boolean = definedExternally)
    open fun dispatchEvent(event: Any): Boolean
}

external open class EventTarget__0 : EventTarget<Record<String, Event>>

external interface EventTargetEventListenerOptions {
    var capture: Boolean?
        get() = definedExternally
        set(value) = definedExternally
}

external interface EventTargetAddEventListenerOptions {
    var capture: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var passive: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var once: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var signal: AbortSignal?
        get() = definedExternally
        set(value) = definedExternally
}

external interface EventTargetHandlerObject {
    var handleEvent: (event: Event) -> Any?
}

external open class AbortController {
    open var signal: AbortSignal
    open fun abort(reason: Any = definedExternally)
}

external open class AbortSignal : EventTarget__0 {
    open var aborted: Boolean
    open var reason: Any
    open fun throwIfAborted()

    companion object {
        fun abort(reason: Any = definedExternally): AbortSignal
        fun timeout(delay: Number): AbortSignal
        fun Any(signals: Array<AbortSignal>): AbortSignal
    }
}

external interface Scheduler {
    fun wait(delay: Number, maybeOptions: SchedulerWaitOptions = definedExternally): Promise<Unit>
}

external interface SchedulerWaitOptions {
    var signal: AbortSignal?
        get() = definedExternally
        set(value) = definedExternally
}

external open class ExtendableEvent(type: String, init: EventInit = definedExternally) : Event {
    open fun waitUntil(promise: Promise<Any>)
}

external open class Blob(bits: Array<Any /* ArrayBuffer | ArrayBufferView | String | Blob */> = definedExternally, options: BlobOptions = definedExternally) {
    open var size: Number
    open var type: String
    open fun slice(start: Number = definedExternally, end: Number = definedExternally, type: String = definedExternally): Blob
    open fun arrayBuffer(): Promise<ArrayBuffer>
    open fun text(): Promise<String>
    open fun stream(): ReadableStream<Uint8Array>
}

external interface BlobOptions {
    var type: String?
        get() = definedExternally
        set(value) = definedExternally
}


external interface FileOptions {
    var type: String?
        get() = definedExternally
        set(value) = definedExternally
    var lastModified: Number?
        get() = definedExternally
        set(value) = definedExternally
}

external open class CacheStorage {
    open fun open(cacheName: String): Promise<Cache>
    open var default: Cache
}

external open class Cache {
    open fun delete(request: Request<Any, Any /* IncomingRequestCfPropertiesBase & IncomingRequestCfPropertiesBotManagementEnterprise & IncomingRequestCfPropertiesCloudflareForSaaSEnterprise<CfHostMetadata> & IncomingRequestCfPropertiesGeographicInformation & IncomingRequestCfPropertiesCloudflareAccessOrApiShield | RequestInitCfProperties */>, options: CacheQueryOptions = definedExternally): Promise<Boolean>
    open fun delete(request: Request<Any, Any /* IncomingRequestCfPropertiesBase & IncomingRequestCfPropertiesBotManagementEnterprise & IncomingRequestCfPropertiesCloudflareForSaaSEnterprise<CfHostMetadata> & IncomingRequestCfPropertiesGeographicInformation & IncomingRequestCfPropertiesCloudflareAccessOrApiShield | RequestInitCfProperties */>): Promise<Boolean>
    open fun delete(request: String, options: CacheQueryOptions = definedExternally): Promise<Boolean>
    open fun delete(request: String): Promise<Boolean>
    open fun delete(request: URL, options: CacheQueryOptions = definedExternally): Promise<Boolean>
    open fun delete(request: URL): Promise<Boolean>
    open fun match(request: Request<Any, Any /* IncomingRequestCfPropertiesBase & IncomingRequestCfPropertiesBotManagementEnterprise & IncomingRequestCfPropertiesCloudflareForSaaSEnterprise<CfHostMetadata> & IncomingRequestCfPropertiesGeographicInformation & IncomingRequestCfPropertiesCloudflareAccessOrApiShield | RequestInitCfProperties */>, options: CacheQueryOptions = definedExternally): Promise<Response?>
    open fun match(request: Request<Any, Any /* IncomingRequestCfPropertiesBase & IncomingRequestCfPropertiesBotManagementEnterprise & IncomingRequestCfPropertiesCloudflareForSaaSEnterprise<CfHostMetadata> & IncomingRequestCfPropertiesGeographicInformation & IncomingRequestCfPropertiesCloudflareAccessOrApiShield | RequestInitCfProperties */>): Promise<Response?>
    open fun match(request: String, options: CacheQueryOptions = definedExternally): Promise<Response?>
    open fun match(request: String): Promise<Response?>
    open fun match(request: URL, options: CacheQueryOptions = definedExternally): Promise<Response?>
    open fun match(request: URL): Promise<Response?>
    open fun put(request: Request<Any, Any /* IncomingRequestCfPropertiesBase & IncomingRequestCfPropertiesBotManagementEnterprise & IncomingRequestCfPropertiesCloudflareForSaaSEnterprise<CfHostMetadata> & IncomingRequestCfPropertiesGeographicInformation & IncomingRequestCfPropertiesCloudflareAccessOrApiShield | RequestInitCfProperties */>, response: Response): Promise<Unit>
    open fun put(request: String, response: Response): Promise<Unit>
    open fun put(request: URL, response: Response): Promise<Unit>
}

external interface CacheQueryOptions {
    var ignoreMethod: Boolean?
        get() = definedExternally
        set(value) = definedExternally
}

external open class Crypto {
    open var subtle: SubtleCrypto
    open fun <T> getRandomValues(buffer: T): T
    open fun randomUUID(): String
    open var DigestStream: Any
}

external open class SubtleCrypto {
    open fun encrypt(algorithm: String, key: CryptoKey, plainText: ArrayBuffer): Promise<ArrayBuffer>
    open fun encrypt(algorithm: String, key: CryptoKey, plainText: ArrayBufferView): Promise<ArrayBuffer>
    open fun encrypt(algorithm: SubtleCryptoEncryptAlgorithm, key: CryptoKey, plainText: ArrayBuffer): Promise<ArrayBuffer>
    open fun encrypt(algorithm: SubtleCryptoEncryptAlgorithm, key: CryptoKey, plainText: ArrayBufferView): Promise<ArrayBuffer>
    open fun decrypt(algorithm: String, key: CryptoKey, cipherText: ArrayBuffer): Promise<ArrayBuffer>
    open fun decrypt(algorithm: String, key: CryptoKey, cipherText: ArrayBufferView): Promise<ArrayBuffer>
    open fun decrypt(algorithm: SubtleCryptoEncryptAlgorithm, key: CryptoKey, cipherText: ArrayBuffer): Promise<ArrayBuffer>
    open fun decrypt(algorithm: SubtleCryptoEncryptAlgorithm, key: CryptoKey, cipherText: ArrayBufferView): Promise<ArrayBuffer>
    open fun sign(algorithm: String, key: CryptoKey, data: ArrayBuffer): Promise<ArrayBuffer>
    open fun sign(algorithm: String, key: CryptoKey, data: ArrayBufferView): Promise<ArrayBuffer>
    open fun sign(algorithm: SubtleCryptoSignAlgorithm, key: CryptoKey, data: ArrayBuffer): Promise<ArrayBuffer>
    open fun sign(algorithm: SubtleCryptoSignAlgorithm, key: CryptoKey, data: ArrayBufferView): Promise<ArrayBuffer>
    open fun verify(algorithm: String, key: CryptoKey, signature: ArrayBuffer, data: ArrayBuffer): Promise<Boolean>
    open fun verify(algorithm: String, key: CryptoKey, signature: ArrayBuffer, data: ArrayBufferView): Promise<Boolean>
    open fun verify(algorithm: String, key: CryptoKey, signature: ArrayBufferView, data: ArrayBuffer): Promise<Boolean>
    open fun verify(algorithm: String, key: CryptoKey, signature: ArrayBufferView, data: ArrayBufferView): Promise<Boolean>
    open fun verify(algorithm: SubtleCryptoSignAlgorithm, key: CryptoKey, signature: ArrayBuffer, data: ArrayBuffer): Promise<Boolean>
    open fun verify(algorithm: SubtleCryptoSignAlgorithm, key: CryptoKey, signature: ArrayBuffer, data: ArrayBufferView): Promise<Boolean>
    open fun verify(algorithm: SubtleCryptoSignAlgorithm, key: CryptoKey, signature: ArrayBufferView, data: ArrayBuffer): Promise<Boolean>
    open fun verify(algorithm: SubtleCryptoSignAlgorithm, key: CryptoKey, signature: ArrayBufferView, data: ArrayBufferView): Promise<Boolean>
    open fun digest(algorithm: String, data: ArrayBuffer): Promise<ArrayBuffer>
    open fun digest(algorithm: String, data: ArrayBufferView): Promise<ArrayBuffer>
    open fun digest(algorithm: SubtleCryptoHashAlgorithm, data: ArrayBuffer): Promise<ArrayBuffer>
    open fun digest(algorithm: SubtleCryptoHashAlgorithm, data: ArrayBufferView): Promise<ArrayBuffer>
    open fun generateKey(algorithm: String, extractable: Boolean, keyUsages: Array<String>): Promise<dynamic /* CryptoKey | CryptoKeyPair */>
    open fun generateKey(algorithm: SubtleCryptoGenerateKeyAlgorithm, extractable: Boolean, keyUsages: Array<String>): Promise<dynamic /* CryptoKey | CryptoKeyPair */>
    open fun deriveKey(algorithm: String, baseKey: CryptoKey, derivedKeyAlgorithm: String, extractable: Boolean, keyUsages: Array<String>): Promise<CryptoKey>
    open fun deriveKey(algorithm: String, baseKey: CryptoKey, derivedKeyAlgorithm: SubtleCryptoImportKeyAlgorithm, extractable: Boolean, keyUsages: Array<String>): Promise<CryptoKey>
    open fun deriveKey(algorithm: SubtleCryptoDeriveKeyAlgorithm, baseKey: CryptoKey, derivedKeyAlgorithm: String, extractable: Boolean, keyUsages: Array<String>): Promise<CryptoKey>
    open fun deriveKey(algorithm: SubtleCryptoDeriveKeyAlgorithm, baseKey: CryptoKey, derivedKeyAlgorithm: SubtleCryptoImportKeyAlgorithm, extractable: Boolean, keyUsages: Array<String>): Promise<CryptoKey>
    open fun deriveBits(algorithm: String, baseKey: CryptoKey, length: Number?): Promise<ArrayBuffer>
    open fun deriveBits(algorithm: SubtleCryptoDeriveKeyAlgorithm, baseKey: CryptoKey, length: Number?): Promise<ArrayBuffer>
    open fun importKey(format: String, keyData: ArrayBuffer, algorithm: String, extractable: Boolean, keyUsages: Array<String>): Promise<CryptoKey>
    open fun importKey(format: String, keyData: ArrayBuffer, algorithm: SubtleCryptoImportKeyAlgorithm, extractable: Boolean, keyUsages: Array<String>): Promise<CryptoKey>
    open fun importKey(format: String, keyData: ArrayBufferView, algorithm: String, extractable: Boolean, keyUsages: Array<String>): Promise<CryptoKey>
    open fun importKey(format: String, keyData: ArrayBufferView, algorithm: SubtleCryptoImportKeyAlgorithm, extractable: Boolean, keyUsages: Array<String>): Promise<CryptoKey>
    open fun importKey(format: String, keyData: JsonWebKey, algorithm: String, extractable: Boolean, keyUsages: Array<String>): Promise<CryptoKey>
    open fun importKey(format: String, keyData: JsonWebKey, algorithm: SubtleCryptoImportKeyAlgorithm, extractable: Boolean, keyUsages: Array<String>): Promise<CryptoKey>
    open fun exportKey(format: String, key: CryptoKey): Promise<dynamic /* ArrayBuffer | JsonWebKey */>
    open fun wrapKey(format: String, key: CryptoKey, wrappingKey: CryptoKey, wrapAlgorithm: String): Promise<ArrayBuffer>
    open fun wrapKey(format: String, key: CryptoKey, wrappingKey: CryptoKey, wrapAlgorithm: SubtleCryptoEncryptAlgorithm): Promise<ArrayBuffer>
    open fun unwrapKey(format: String, wrappedKey: ArrayBuffer, unwrappingKey: CryptoKey, unwrapAlgorithm: String, unwrappedKeyAlgorithm: String, extractable: Boolean, keyUsages: Array<String>): Promise<CryptoKey>
    open fun unwrapKey(format: String, wrappedKey: ArrayBuffer, unwrappingKey: CryptoKey, unwrapAlgorithm: String, unwrappedKeyAlgorithm: SubtleCryptoImportKeyAlgorithm, extractable: Boolean, keyUsages: Array<String>): Promise<CryptoKey>
    open fun unwrapKey(format: String, wrappedKey: ArrayBuffer, unwrappingKey: CryptoKey, unwrapAlgorithm: SubtleCryptoEncryptAlgorithm, unwrappedKeyAlgorithm: String, extractable: Boolean, keyUsages: Array<String>): Promise<CryptoKey>
    open fun unwrapKey(format: String, wrappedKey: ArrayBuffer, unwrappingKey: CryptoKey, unwrapAlgorithm: SubtleCryptoEncryptAlgorithm, unwrappedKeyAlgorithm: SubtleCryptoImportKeyAlgorithm, extractable: Boolean, keyUsages: Array<String>): Promise<CryptoKey>
    open fun unwrapKey(format: String, wrappedKey: ArrayBufferView, unwrappingKey: CryptoKey, unwrapAlgorithm: String, unwrappedKeyAlgorithm: String, extractable: Boolean, keyUsages: Array<String>): Promise<CryptoKey>
    open fun unwrapKey(format: String, wrappedKey: ArrayBufferView, unwrappingKey: CryptoKey, unwrapAlgorithm: String, unwrappedKeyAlgorithm: SubtleCryptoImportKeyAlgorithm, extractable: Boolean, keyUsages: Array<String>): Promise<CryptoKey>
    open fun unwrapKey(format: String, wrappedKey: ArrayBufferView, unwrappingKey: CryptoKey, unwrapAlgorithm: SubtleCryptoEncryptAlgorithm, unwrappedKeyAlgorithm: String, extractable: Boolean, keyUsages: Array<String>): Promise<CryptoKey>
    open fun unwrapKey(format: String, wrappedKey: ArrayBufferView, unwrappingKey: CryptoKey, unwrapAlgorithm: SubtleCryptoEncryptAlgorithm, unwrappedKeyAlgorithm: SubtleCryptoImportKeyAlgorithm, extractable: Boolean, keyUsages: Array<String>): Promise<CryptoKey>
    open fun timingSafeEqual(a: ArrayBuffer, b: ArrayBuffer): Boolean
    open fun timingSafeEqual(a: ArrayBuffer, b: ArrayBufferView): Boolean
    open fun timingSafeEqual(a: ArrayBufferView, b: ArrayBuffer): Boolean
    open fun timingSafeEqual(a: ArrayBufferView, b: ArrayBufferView): Boolean
}

external open class CryptoKey {
    open var type: String
    open var extractable: Boolean
    open var algorithm: dynamic /* CryptoKeyKeyAlgorithm | CryptoKeyAesKeyAlgorithm | CryptoKeyHmacKeyAlgorithm | CryptoKeyRsaKeyAlgorithm | CryptoKeyEllipticKeyAlgorithm | CryptoKeyArbitraryKeyAlgorithm */
    open var usages: Array<String>
}

external interface CryptoKeyPair {
    var publicKey: CryptoKey
    var privateKey: CryptoKey
}

external interface JsonWebKey {
    var kty: String
    var use: String?
        get() = definedExternally
        set(value) = definedExternally
    var key_ops: Array<String>?
        get() = definedExternally
        set(value) = definedExternally
    var alg: String?
        get() = definedExternally
        set(value) = definedExternally
    var ext: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var crv: String?
        get() = definedExternally
        set(value) = definedExternally
    var x: String?
        get() = definedExternally
        set(value) = definedExternally
    var y: String?
        get() = definedExternally
        set(value) = definedExternally
    var d: String?
        get() = definedExternally
        set(value) = definedExternally
    var n: String?
        get() = definedExternally
        set(value) = definedExternally
    var e: String?
        get() = definedExternally
        set(value) = definedExternally
    var p: String?
        get() = definedExternally
        set(value) = definedExternally
    var q: String?
        get() = definedExternally
        set(value) = definedExternally
    var dp: String?
        get() = definedExternally
        set(value) = definedExternally
    var dq: String?
        get() = definedExternally
        set(value) = definedExternally
    var qi: String?
        get() = definedExternally
        set(value) = definedExternally
    var oth: Array<RsaOtherPrimesInfo>?
        get() = definedExternally
        set(value) = definedExternally
    var k: String?
        get() = definedExternally
        set(value) = definedExternally
}

external interface RsaOtherPrimesInfo {
    var r: String?
        get() = definedExternally
        set(value) = definedExternally
    var d: String?
        get() = definedExternally
        set(value) = definedExternally
    var t: String?
        get() = definedExternally
        set(value) = definedExternally
}

external interface SubtleCryptoDeriveKeyAlgorithm {
    var name: String
    var salt: ArrayBuffer?
        get() = definedExternally
        set(value) = definedExternally
    var iterations: Number?
        get() = definedExternally
        set(value) = definedExternally
    var hash: dynamic /* String? | SubtleCryptoHashAlgorithm? */
        get() = definedExternally
        set(value) = definedExternally
    var `$public`: CryptoKey?
        get() = definedExternally
        set(value) = definedExternally
    var info: ArrayBuffer?
        get() = definedExternally
        set(value) = definedExternally
}

external interface SubtleCryptoEncryptAlgorithm {
    var name: String
    var iv: ArrayBuffer?
        get() = definedExternally
        set(value) = definedExternally
    var additionalData: ArrayBuffer?
        get() = definedExternally
        set(value) = definedExternally
    var tagLength: Number?
        get() = definedExternally
        set(value) = definedExternally
    var counter: ArrayBuffer?
        get() = definedExternally
        set(value) = definedExternally
    var length: Number?
        get() = definedExternally
        set(value) = definedExternally
    var label: ArrayBuffer?
        get() = definedExternally
        set(value) = definedExternally
}

external interface SubtleCryptoGenerateKeyAlgorithm {
    var name: String
    var hash: dynamic /* String? | SubtleCryptoHashAlgorithm? */
        get() = definedExternally
        set(value) = definedExternally
    var modulusLength: Number?
        get() = definedExternally
        set(value) = definedExternally
    var publicExponent: ArrayBuffer?
        get() = definedExternally
        set(value) = definedExternally
    var length: Number?
        get() = definedExternally
        set(value) = definedExternally
    var namedCurve: String?
        get() = definedExternally
        set(value) = definedExternally
}

external interface SubtleCryptoHashAlgorithm {
    var name: String
}

external interface SubtleCryptoImportKeyAlgorithm {
    var name: String
    var hash: dynamic /* String? | SubtleCryptoHashAlgorithm? */
        get() = definedExternally
        set(value) = definedExternally
    var length: Number?
        get() = definedExternally
        set(value) = definedExternally
    var namedCurve: String?
        get() = definedExternally
        set(value) = definedExternally
    var compressed: Boolean?
        get() = definedExternally
        set(value) = definedExternally
}

external interface SubtleCryptoSignAlgorithm {
    var name: String
    var hash: dynamic /* String? | SubtleCryptoHashAlgorithm? */
        get() = definedExternally
        set(value) = definedExternally
    var dataLength: Number?
        get() = definedExternally
        set(value) = definedExternally
    var saltLength: Number?
        get() = definedExternally
        set(value) = definedExternally
}

external interface CryptoKeyKeyAlgorithm {
    var name: String
}

external interface CryptoKeyAesKeyAlgorithm {
    var name: String
    var length: Number
}

external interface CryptoKeyHmacKeyAlgorithm {
    var name: String
    var hash: CryptoKeyKeyAlgorithm
    var length: Number
}

external interface CryptoKeyRsaKeyAlgorithm {
    var name: String
    var modulusLength: Number
    var publicExponent: ArrayBuffer
    var hash: CryptoKeyKeyAlgorithm?
        get() = definedExternally
        set(value) = definedExternally
}

external interface CryptoKeyEllipticKeyAlgorithm {
    var name: String
    var namedCurve: String
}

external interface CryptoKeyArbitraryKeyAlgorithm {
    var name: String
    var hash: CryptoKeyKeyAlgorithm?
        get() = definedExternally
        set(value) = definedExternally
    var namedCurve: String?
        get() = definedExternally
        set(value) = definedExternally
    var length: Number?
        get() = definedExternally
        set(value) = definedExternally
}

external open class DigestStream : WritableStream<dynamic /* ArrayBuffer | ArrayBufferView */> {
    constructor(algorithm: String)
    constructor(algorithm: SubtleCryptoHashAlgorithm)
    open var digest: Promise<ArrayBuffer>
}

external open class TextDecoder(decoder: String = definedExternally, options: TextDecoderConstructorOptions = definedExternally) {
    open fun decode(input: ArrayBuffer = definedExternally, options: TextDecoderDecodeOptions = definedExternally): String
    open fun decode(): String
    open fun decode(input: ArrayBuffer = definedExternally): String
    open fun decode(input: ArrayBufferView = definedExternally, options: TextDecoderDecodeOptions = definedExternally): String
    open fun decode(input: ArrayBufferView = definedExternally): String
    open var encoding: String
    open var fatal: Boolean
    open var ignoreBOM: Boolean
}

external open class TextEncoder {
    open fun encode(input: String = definedExternally): Uint8Array
    open fun encodeInto(input: String, buffer: Uint8Array): TextEncoderEncodeIntoResult
    open var encoding: String
}

external interface TextDecoderConstructorOptions {
    var fatal: Boolean
    var ignoreBOM: Boolean
}

external interface TextDecoderDecodeOptions {
    var stream: Boolean
}

external interface TextEncoderEncodeIntoResult {
    var read: Number
    var written: Number
}

external open class FormData {
    open fun append(name: String, value: String)
    open fun append(name: String, value: Blob, filename: String = definedExternally)
    open fun append(name: String, value: Blob)
    open fun delete(name: String)
    open fun get(name: String): dynamic /* String? | File? */
    open fun getAll(name: String): Array<String>
    open fun has(name: String): Boolean
    open fun set(name: String, value: String)
    open fun set(name: String, value: Blob, filename: String = definedExternally)
    open fun set(name: String, value: Blob)
    open fun entries(): Array<dynamic /* JsTuple<key> */>
}

external interface ContentOptions {
    var html: Boolean?
        get() = definedExternally
        set(value) = definedExternally
}

external open class HTMLRewriter {
    open fun on(selector: String, handlers: HTMLRewriterElementContentHandlers): HTMLRewriter
    open fun onDocument(handlers: HTMLRewriterDocumentContentHandlers): HTMLRewriter
    open fun transform(response: Response): Response
}

external interface HTMLRewriterElementContentHandlers {
    val element: ((element: Element) -> dynamic)?
    val comments: ((comment: Comment) -> dynamic)?
    val text: ((element: Text) -> dynamic)?
}

external interface HTMLRewriterDocumentContentHandlers {
    val doctype: ((doctype: Doctype) -> dynamic)?
    val comments: ((comment: Comment) -> dynamic)?
    val text: ((text: Text) -> dynamic)?
    val end: ((end: DocumentEnd) -> dynamic)?
}

external interface Doctype {
    var name: String?
    var publicId: String?
    var systemId: String?
}

external interface Element {
    var tagName: String
    var attributes: Array<Array<String>>
    var removed: Boolean
    var namespaceURI: String
    fun getAttribute(name: String): String?
    fun hasAttribute(name: String): Boolean
    fun setAttribute(name: String, value: String): Element
    fun removeAttribute(name: String): Element
    fun before(content: String, options: ContentOptions = definedExternally): Element
    fun after(content: String, options: ContentOptions = definedExternally): Element
    fun prepend(content: String, options: ContentOptions = definedExternally): Element
    fun append(content: String, options: ContentOptions = definedExternally): Element
    fun replace(content: String, options: ContentOptions = definedExternally): Element
    fun remove(): Element
    fun removeAndKeepContent(): Element
    fun setInnerContent(content: String, options: ContentOptions = definedExternally): Element
    fun onEndTag(handler: (tag: EndTag) -> Any)
}

external interface EndTag {
    var name: String
    fun before(content: String, options: ContentOptions = definedExternally): EndTag
    fun after(content: String, options: ContentOptions = definedExternally): EndTag
    fun remove(): EndTag
}

external interface Comment {
    var text: String
    var removed: Boolean
    fun before(content: String, options: ContentOptions = definedExternally): Comment
    fun after(content: String, options: ContentOptions = definedExternally): Comment
    fun replace(content: String, options: ContentOptions = definedExternally): Comment
    fun remove(): Comment
}

external interface Text {
    var text: String
    var lastInTextNode: Boolean
    var removed: Boolean
    fun before(content: String, options: ContentOptions = definedExternally): Text
    fun after(content: String, options: ContentOptions = definedExternally): Text
    fun replace(content: String, options: ContentOptions = definedExternally): Text
    fun remove(): Text
}

external interface DocumentEnd {
    fun append(content: String, options: ContentOptions = definedExternally): DocumentEnd
}

external open class FetchEvent(type: String, init: EventInit = definedExternally) : ExtendableEvent {
    open var request: Request__0
    open fun respondWith(promise: Response)
    open fun respondWith(promise: Promise<Response>)
    open fun passThroughOnException()
}

external open class Headers {
    constructor(init: Headers = definedExternally)
    constructor()
    constructor(init: Iterable<Iterable<String>> = definedExternally)
    constructor(init: Record<String, String> = definedExternally)
    open fun get(name: String): String?
    open fun getAll(name: String): Array<String>
    open fun has(name: String): Boolean
    open fun set(name: String, value: String)
    open fun append(name: String, value: String)
    open fun delete(name: String)
    open fun <This> forEach(callback: (self: This, value: String, key: String, parent: Headers) -> Unit, thisArg: This = definedExternally)
    open fun entries(): JsIterable.Iterator<dynamic>
}

external open class Body {
    open var body: ReadableStream<Uint8Array>?
    open var bodyUsed: Boolean
    open fun arrayBuffer(): Promise<ArrayBuffer>
    open fun text(): Promise<String>
    open fun <T> json(): Promise<T>
    open fun formData(): Promise<FormData>
    open fun blob(): Promise<Blob>
}

external open class Response : Body {
    constructor(body: ReadableStream<Uint8Array>? = definedExternally, init: ResponseInit = definedExternally)
    constructor()
    constructor(body: ReadableStream<Uint8Array>? = definedExternally)
    constructor(body: String? = definedExternally, init: ResponseInit = definedExternally)
    constructor(body: String? = definedExternally)
    constructor(body: ArrayBuffer? = definedExternally, init: ResponseInit = definedExternally)
    constructor(body: ArrayBuffer? = definedExternally)
    constructor(body: ArrayBufferView? = definedExternally, init: ResponseInit = definedExternally)
    constructor(body: ArrayBufferView? = definedExternally)
    constructor(body: Blob? = definedExternally, init: ResponseInit = definedExternally)
    constructor(body: Blob? = definedExternally)
    constructor(body: URLSearchParams? = definedExternally, init: ResponseInit = definedExternally)
    constructor(body: URLSearchParams? = definedExternally)
    constructor(body: FormData? = definedExternally, init: ResponseInit = definedExternally)
    constructor(body: FormData? = definedExternally)
    open fun clone(): Response
    open var status: Number
    open var statusText: String
    open var headers: Headers
    open var ok: Boolean
    open var redirected: Boolean
    open var url: String
    open var webSocket: WebSocket?
    open var cf: Any

    companion object {
        fun redirect(url: String, status: Number = definedExternally): Response
        fun json(Any: Any, maybeInit: ResponseInit = definedExternally): Response
        fun json(Any: Any, maybeInit: Response = definedExternally): Response
    }
}

external interface ResponseInit {
    var status: Number?
        get() = definedExternally
        set(value) = definedExternally
    var statusText: String?
        get() = definedExternally
        set(value) = definedExternally
    var headers: dynamic /* Headers? | Iterable<Iterable<String>>? | Record<String, String>? */
        get() = definedExternally
        set(value) = definedExternally
    var cf: Any?
        get() = definedExternally
        set(value) = definedExternally
    var webSocket: WebSocket?
        get() = definedExternally
        set(value) = definedExternally
    var encodeBody: String? /* "automatic" | "manual" */
        get() = definedExternally
        set(value) = definedExternally
}

external open class Request<CfHostMetadata, Cf> : Body {
    constructor(input: Request<Any /* IncomingRequestCfPropertiesBase & IncomingRequestCfPropertiesBotManagementEnterprise & IncomingRequestCfPropertiesCloudflareForSaaSEnterprise<Any> & IncomingRequestCfPropertiesGeographicInformation & IncomingRequestCfPropertiesCloudflareAccessOrApiShield | RequestInitCfProperties */, Any /* IncomingRequestCfPropertiesBase & IncomingRequestCfPropertiesBotManagementEnterprise & IncomingRequestCfPropertiesCloudflareForSaaSEnterprise<dynamic /* IncomingRequestCfPropertiesBase & IncomingRequestCfPropertiesBotManagementEnterprise & IncomingRequestCfPropertiesCloudflareForSaaSEnterprise<Any> & IncomingRequestCfPropertiesGeographicInformation & IncomingRequestCfPropertiesCloudflareAccessOrApiShield | RequestInitCfProperties */> & IncomingRequestCfPropertiesGeographicInformation & IncomingRequestCfPropertiesCloudflareAccessOrApiShield | RequestInitCfProperties */>, init: RequestInit<Cf> = definedExternally)
    constructor(input: Request<Any /* IncomingRequestCfPropertiesBase & IncomingRequestCfPropertiesBotManagementEnterprise & IncomingRequestCfPropertiesCloudflareForSaaSEnterprise<Any> & IncomingRequestCfPropertiesGeographicInformation & IncomingRequestCfPropertiesCloudflareAccessOrApiShield | RequestInitCfProperties */, Any /* IncomingRequestCfPropertiesBase & IncomingRequestCfPropertiesBotManagementEnterprise & IncomingRequestCfPropertiesCloudflareForSaaSEnterprise<dynamic /* IncomingRequestCfPropertiesBase & IncomingRequestCfPropertiesBotManagementEnterprise & IncomingRequestCfPropertiesCloudflareForSaaSEnterprise<Any> & IncomingRequestCfPropertiesGeographicInformation & IncomingRequestCfPropertiesCloudflareAccessOrApiShield | RequestInitCfProperties */> & IncomingRequestCfPropertiesGeographicInformation & IncomingRequestCfPropertiesCloudflareAccessOrApiShield | RequestInitCfProperties */>)
    constructor(input: String, init: RequestInit<Cf> = definedExternally)
    constructor(input: String)
    constructor(input: URL, init: RequestInit<Cf> = definedExternally)
    constructor(input: URL)
    open fun clone(): Request<CfHostMetadata, Cf>
    open var method: String
    open var url: String
    open var headers: Headers
    open var redirect: String
    open var fetcher: Fetcher?
    open var signal: AbortSignal
    open var cf: Cf
    open var integrity: String
    open var keepalive: Boolean
}

external open class Request__0 : Request<Any, dynamic /* IncomingRequestCfPropertiesBase & IncomingRequestCfPropertiesBotManagementEnterprise & IncomingRequestCfPropertiesCloudflareForSaaSEnterprise<Any> & IncomingRequestCfPropertiesGeographicInformation & IncomingRequestCfPropertiesCloudflareAccessOrApiShield | RequestInitCfProperties */>

external interface RequestInit<Cf> {
    var method: String?
        get() = definedExternally
        set(value) = definedExternally
    var headers: dynamic /* Headers? | Iterable<Iterable<String>>? | Record<String, String>? */
        get() = definedExternally
        set(value) = definedExternally
    var body: dynamic /* ReadableStream<Uint8Array>? | String? | ArrayBuffer? | ArrayBufferView? | Blob? | URLSearchParams? | FormData? */
        get() = definedExternally
        set(value) = definedExternally
    var redirect: String?
        get() = definedExternally
        set(value) = definedExternally
    var fetcher: Fetcher?
        get() = definedExternally
        set(value) = definedExternally
    var cf: Cf?
        get() = definedExternally
        set(value) = definedExternally
    var integrity: String?
        get() = definedExternally
        set(value) = definedExternally
    var signal: AbortSignal?
        get() = definedExternally
        set(value) = definedExternally
}

external interface RequestInit__0 : RequestInit<dynamic /* IncomingRequestCfPropertiesBase & IncomingRequestCfPropertiesBotManagementEnterprise & IncomingRequestCfPropertiesCloudflareForSaaSEnterprise<Any> & IncomingRequestCfPropertiesGeographicInformation & IncomingRequestCfPropertiesCloudflareAccessOrApiShield | RequestInitCfProperties */>

external open class Fetcher {
    open fun fetch(input: Request<Any, Any /* IncomingRequestCfPropertiesBase & IncomingRequestCfPropertiesBotManagementEnterprise & IncomingRequestCfPropertiesCloudflareForSaaSEnterprise<CfHostMetadata> & IncomingRequestCfPropertiesGeographicInformation & IncomingRequestCfPropertiesCloudflareAccessOrApiShield | RequestInitCfProperties */>, init: RequestInit__0 = definedExternally): Promise<Response>
    open fun fetch(input: Request<Any, Any /* IncomingRequestCfPropertiesBase & IncomingRequestCfPropertiesBotManagementEnterprise & IncomingRequestCfPropertiesCloudflareForSaaSEnterprise<CfHostMetadata> & IncomingRequestCfPropertiesGeographicInformation & IncomingRequestCfPropertiesCloudflareAccessOrApiShield | RequestInitCfProperties */>): Promise<Response>
    open fun fetch(input: String, init: RequestInit__0 = definedExternally): Promise<Response>
    open fun fetch(input: String): Promise<Response>
    open fun fetch(input: URL, init: RequestInit__0 = definedExternally): Promise<Response>
    open fun fetch(input: URL): Promise<Response>
    open fun connect(address: SocketAddress, options: SocketOptions = definedExternally): Socket
    open fun connect(address: SocketAddress): Socket
    open fun connect(address: String, options: SocketOptions = definedExternally): Socket
    open fun connect(address: String): Socket
}

external interface FetcherPutOptions {
    var expiration: Number?
        get() = definedExternally
        set(value) = definedExternally
    var expirationTtl: Number?
        get() = definedExternally
        set(value) = definedExternally
}

external interface KVNamespaceListKey<Metadata, Key : String> {
    var name: Key
    var expiration: Number?
        get() = definedExternally
        set(value) = definedExternally
    var metadata: Metadata?
        get() = definedExternally
        set(value) = definedExternally
}

external interface KVNamespace<Key : String> {
    fun get(key: Key, options: KVNamespaceGetOptionsPartial<Nothing?> = definedExternally): Promise<String?>
    fun get(key: Key): dynamic /* Promise */
    fun get(key: Key, type: String /* "text" | "arrayBuffer" | "stream" */): dynamic /* Promise */
    fun <ExpectedValue> get(key: Key, type: String /* "json" */): Promise<ExpectedValue?>
    fun get(key: Key, options: KVNamespaceGetOptions<String /* "text" | "arrayBuffer" | "stream" */> = definedExternally): dynamic /* Promise */
    fun <ExpectedValue> get(key: Key, options: KVNamespaceGetOptions<String /* "json" */> = definedExternally): Promise<ExpectedValue?>
    fun <ExpectedValue> get(key: Key): Promise<ExpectedValue?>
    fun list(options: KVNamespaceListOptions = definedExternally): Promise<dynamic /* `T$7`<Metadata, Key> | `T$8`<Metadata, Key> */>
    fun put(key: Key, value: String, options: KVNamespacePutOptions = definedExternally): Promise<Unit>
    fun put(key: Key, value: String): Promise<Unit>
    fun put(key: Key, value: ArrayBuffer, options: KVNamespacePutOptions = definedExternally): Promise<Unit>
    fun put(key: Key, value: ArrayBuffer): Promise<Unit>
    fun put(key: Key, value: ArrayBufferView, options: KVNamespacePutOptions = definedExternally): Promise<Unit>
    fun put(key: Key, value: ArrayBufferView): Promise<Unit>
    fun put(key: Key, value: ReadableStream<Uint8Array>, options: KVNamespacePutOptions = definedExternally): Promise<Unit>
    fun put(key: Key, value: ReadableStream<Uint8Array>): Promise<Unit>
    fun <Metadata> getWithMetadata(key: Key, options: KVNamespaceGetOptionsPartial<Nothing?> = definedExternally): Promise<KVNamespaceGetWithMetadataResult<String, Metadata>>
    fun <Metadata> getWithMetadata(key: Key): Promise<KVNamespaceGetWithMetadataResult<String, Metadata>>
    fun <Metadata> getWithMetadata(key: Key, type: String /* "text" | "arrayBuffer" | "stream" */): dynamic /* Promise */
    fun <ExpectedValue, Metadata> getWithMetadata(key: Key, type: String /* "json" */): Promise<KVNamespaceGetWithMetadataResult<ExpectedValue, Metadata>>
    fun <Metadata> getWithMetadata(key: Key, options: KVNamespaceGetOptions<String /* "text" | "arrayBuffer" | "stream" */>): dynamic /* Promise */
    fun <ExpectedValue, Metadata> getWithMetadata(key: Key, options: KVNamespaceGetOptions<String /* "json" */>): Promise<KVNamespaceGetWithMetadataResult<ExpectedValue, Metadata>>
    fun delete(key: Key): Promise<Unit>
}

external interface KVNamespaceListOptions {
    var limit: Number?
        get() = definedExternally
        set(value) = definedExternally
    var prefix: String?
        get() = definedExternally
        set(value) = definedExternally
    var cursor: String?
        get() = definedExternally
        set(value) = definedExternally
}

external interface KVNamespaceGetOptions<Type> {
    var type: Type
    var cacheTtl: Number?
        get() = definedExternally
        set(value) = definedExternally
}

external interface KVNamespaceGetOptionsPartial<Type> {
    var type: Type?
        get() = definedExternally
        set(value) = definedExternally
    var cacheTtl: Number?
        get() = definedExternally
        set(value) = definedExternally
}

external interface KVNamespacePutOptions {
    var expiration: Number?
        get() = definedExternally
        set(value) = definedExternally
    var expirationTtl: Number?
        get() = definedExternally
        set(value) = definedExternally
    var metadata: Any?
        get() = definedExternally
        set(value) = definedExternally
}

external interface KVNamespaceGetWithMetadataResult<Value, Metadata> {
    var value: Value?
    var metadata: Metadata?
}

external interface Queue<Body> {
    fun send(message: Body): Promise<Unit>
    fun sendBatch(messages: Iterable<MessageSendRequest<Body>>): Promise<Unit>
}

external interface QueueSendOptions

external interface MessageSendRequest<Body> {
    var body: Body
}

external interface Message<Body> {
    var id: String
    var timestamp: Date
    var body: Body
    fun retry()
    fun ack()
}

external interface QueueEvent<Body> : ExtendableEvent {
    var messages: Array<Message<Body>>
    var queue: String
    fun retryAll()
    fun ackAll()
}

external interface QueueEvent__0 : QueueEvent<Any>

external interface MessageBatch<Body> {
    var messages: Array<Message<Body>>
    var queue: String
    fun retryAll()
    fun ackAll()
}

typealias R2Error = Error

external interface R2ListOptions {
    var limit: Number?
        get() = definedExternally
        set(value) = definedExternally
    var prefix: String?
        get() = definedExternally
        set(value) = definedExternally
    var cursor: String?
        get() = definedExternally
        set(value) = definedExternally
    var delimiter: String?
        get() = definedExternally
        set(value) = definedExternally
    var startAfter: String?
        get() = definedExternally
        set(value) = definedExternally
}

external open class R2Bucket {
    open fun head(key: String): Promise<R2Object?>
    open fun get(key: String, options: R2GetOptions /* R2GetOptions & `T$0` */): Promise<Any?>
    open fun get(key: String): Promise<R2ObjectBody?>
    open fun put(key: String, value: ReadableStream<Uint8Array>?, options: R2PutOptions /* R2PutOptions & `T$0` */ = definedExternally): Promise<Any?>
    open fun put(key: String, value: ReadableStream<Uint8Array>?): Promise<Any?> /* Promise | Promise */
    open fun put(key: String, value: ArrayBuffer?, options: R2PutOptions /* R2PutOptions & `T$0` */ = definedExternally): Promise<Any?>
    open fun put(key: String, value: ArrayBuffer?): Promise<Any?>
    open fun put(key: String, value: ArrayBufferView?, options: R2PutOptions /* R2PutOptions & `T$0` */ = definedExternally): Promise<Any?>
    open fun put(key: String, value: ArrayBufferView?): Promise<Any?>
    open fun put(key: String, value: String?, options: R2PutOptions /* R2PutOptions & `T$0` */ = definedExternally): Promise<Any?>
    open fun put(key: String, value: String?): Promise<Any?>
    open fun put(key: String, value: Blob?, options: R2PutOptions /* R2PutOptions & `T$0` */ = definedExternally): Promise<Any?>
    open fun put(key: String, value: Blob?): Promise<Any?>
    open fun createMultipartUpload(key: String, options: R2MultipartOptions = definedExternally): Promise<R2MultipartUpload>
    open fun resumeMultipartUpload(key: String, uploadId: String): R2MultipartUpload
    open fun delete(keys: String): Promise<Unit>
    open fun delete(keys: Array<String>): Promise<Unit>
    open fun list(options: R2ListOptions = definedExternally): Promise<`T$12` /* `T$12` & dynamic */>
}

external interface R2MultipartUpload {
    var key: String
    var uploadId: String
    fun uploadPart(partNumber: Number, value: ReadableStream<Uint8Array>): Promise<R2UploadedPart>
    fun uploadPart(partNumber: Number, value: ArrayBuffer): Promise<R2UploadedPart>
    fun uploadPart(partNumber: Number, value: ArrayBufferView): Promise<R2UploadedPart>
    fun uploadPart(partNumber: Number, value: String): Promise<R2UploadedPart>
    fun uploadPart(partNumber: Number, value: Blob): Promise<R2UploadedPart>
    fun abort(): Promise<Unit>
    fun complete(uploadedParts: Array<R2UploadedPart>): Promise<R2Object>
}

external interface R2UploadedPart {
    var partNumber: Number
    var etag: String
}

external open class R2Object {
    open var key: String
    open var version: String
    open var size: Number
    open var etag: String
    open var httpEtag: String
    open var checksums: R2Checksums
    open var uploaded: Date
    open var httpMetadata: R2HTTPMetadata
    open var customMetadata: Record<String, String>
    open var range: dynamic /* `T$9` | `T$10` | `T$11` */
    open fun writeHttpMetadata(headers: Headers)
}

external interface R2ObjectBody : R2Object {
    val body: ReadableStream<Uint8Array>?
        get() = definedExternally
    val bodyUsed: Boolean?
        get() = definedExternally
    fun arrayBuffer(): Promise<ArrayBuffer>
    fun text(): Promise<String>
    fun<T> json(): Promise<T>
    fun blob(): Promise<Blob>
}

external interface R2Conditional {
    var etagMatches: String?
        get() = definedExternally
        set(value) = definedExternally
    var etagDoesNotMatch: String?
        get() = definedExternally
        set(value) = definedExternally
    var uploadedBefore: Date?
        get() = definedExternally
        set(value) = definedExternally
    var uploadedAfter: Date?
        get() = definedExternally
        set(value) = definedExternally
    var secondsGranularity: Boolean?
        get() = definedExternally
        set(value) = definedExternally
}

external interface R2GetOptions {
    var onlyIf: dynamic /* R2Conditional? | Headers? */
        get() = definedExternally
        set(value) = definedExternally
    var range: dynamic /* `T$9`? | `T$10`? | `T$11`? | Headers? */
        get() = definedExternally
        set(value) = definedExternally
}

external interface R2PutOptions {
    var onlyIf: dynamic /* R2Conditional? | Headers? */
        get() = definedExternally
        set(value) = definedExternally
    var httpMetadata: dynamic /* R2HTTPMetadata? | Headers? */
        get() = definedExternally
        set(value) = definedExternally
    var customMetadata: Record<String, String>?
        get() = definedExternally
        set(value) = definedExternally
    var md5: dynamic /* ArrayBuffer? | String? */
        get() = definedExternally
        set(value) = definedExternally
    var sha1: dynamic /* ArrayBuffer? | String? */
        get() = definedExternally
        set(value) = definedExternally
    var sha256: dynamic /* ArrayBuffer? | String? */
        get() = definedExternally
        set(value) = definedExternally
    var sha384: dynamic /* ArrayBuffer? | String? */
        get() = definedExternally
        set(value) = definedExternally
    var sha512: dynamic /* ArrayBuffer? | String? */
        get() = definedExternally
        set(value) = definedExternally
}

external interface R2MultipartOptions {
    var httpMetadata: dynamic /* R2HTTPMetadata? | Headers? */
        get() = definedExternally
        set(value) = definedExternally
    var customMetadata: Record<String, String>?
        get() = definedExternally
        set(value) = definedExternally
}

external interface R2Checksums {
    var md5: ArrayBuffer?
        get() = definedExternally
        set(value) = definedExternally
    var sha1: ArrayBuffer?
        get() = definedExternally
        set(value) = definedExternally
    var sha256: ArrayBuffer?
        get() = definedExternally
        set(value) = definedExternally
    var sha384: ArrayBuffer?
        get() = definedExternally
        set(value) = definedExternally
    var sha512: ArrayBuffer?
        get() = definedExternally
        set(value) = definedExternally
    fun toJSON(): R2StringChecksums
}

external interface R2StringChecksums {
    var md5: String?
        get() = definedExternally
        set(value) = definedExternally
    var sha1: String?
        get() = definedExternally
        set(value) = definedExternally
    var sha256: String?
        get() = definedExternally
        set(value) = definedExternally
    var sha384: String?
        get() = definedExternally
        set(value) = definedExternally
    var sha512: String?
        get() = definedExternally
        set(value) = definedExternally
}

external interface R2HTTPMetadata {
    var contentType: String?
        get() = definedExternally
        set(value) = definedExternally
    var contentLanguage: String?
        get() = definedExternally
        set(value) = definedExternally
    var contentDisposition: String?
        get() = definedExternally
        set(value) = definedExternally
    var contentEncoding: String?
        get() = definedExternally
        set(value) = definedExternally
    var cacheControl: String?
        get() = definedExternally
        set(value) = definedExternally
    var cacheExpiry: Date?
        get() = definedExternally
        set(value) = definedExternally
}

external interface `T$12` {
    var objects: Array<R2Object>
    var delimitedPrefixes: Array<String>
}

external open class ScheduledEvent(type: String, init: EventInit = definedExternally) : ExtendableEvent {
    open var scheduledTime: Number
    open var cron: String
    open fun noRetry()
}

external interface ScheduledController {
    var scheduledTime: Number
    var cron: String
    fun noRetry()
}

external interface QueuingStrategy<T> {
    var highWaterMark: dynamic /* Number? | Any? */
        get() = definedExternally
        set(value) = definedExternally
    var size: ((chunk: T) -> dynamic)?
        get() = definedExternally
        set(value) = definedExternally
}

external interface QueuingStrategy__0 : QueuingStrategy<Any>

external interface UnderlyingSink<W> {
    var type: String?
        get() = definedExternally
        set(value) = definedExternally
    var start: ((controller: WritableStreamDefaultController) -> dynamic)?
        get() = definedExternally
        set(value) = definedExternally
    var write: ((chunk: W, controller: WritableStreamDefaultController) -> dynamic)?
        get() = definedExternally
        set(value) = definedExternally
    var abort: ((reason: Any) -> dynamic)?
        get() = definedExternally
        set(value) = definedExternally
    var close: (() -> dynamic)?
        get() = definedExternally
        set(value) = definedExternally
}

external interface UnderlyingSink__0 : UnderlyingSink<Any>

external interface UnderlyingByteSource {
    var type: String /* "bytes" */
    var autoAllocateChunkSize: Number?
        get() = definedExternally
        set(value) = definedExternally
    var start: ((controller: ReadableByteStreamController) -> dynamic)?
        get() = definedExternally
        set(value) = definedExternally
    var pull: ((controller: ReadableByteStreamController) -> dynamic)?
        get() = definedExternally
        set(value) = definedExternally
    var cancel: ((reason: Any) -> dynamic)?
        get() = definedExternally
        set(value) = definedExternally
}

external interface UnderlyingSource<R> {
    var type: String? /* "" */
        get() = definedExternally
        set(value) = definedExternally
    var start: ((controller: ReadableStreamDefaultController<R>) -> dynamic)?
        get() = definedExternally
        set(value) = definedExternally
    var pull: ((controller: ReadableStreamDefaultController<R>) -> dynamic)?
        get() = definedExternally
        set(value) = definedExternally
    var cancel: ((reason: Any) -> dynamic)?
        get() = definedExternally
        set(value) = definedExternally
}

external interface Transformer<I, O> {
    var readableType: String?
        get() = definedExternally
        set(value) = definedExternally
    var writableType: String?
        get() = definedExternally
        set(value) = definedExternally
    var start: ((controller: TransformStreamDefaultController<O>) -> dynamic)?
        get() = definedExternally
        set(value) = definedExternally
    var transform: ((chunk: I, controller: TransformStreamDefaultController<O>) -> dynamic)?
        get() = definedExternally
        set(value) = definedExternally
    var flush: ((controller: TransformStreamDefaultController<O>) -> dynamic)?
        get() = definedExternally
        set(value) = definedExternally
}

external interface StreamPipeOptions {
    var preventClose: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var preventAbort: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var preventCancel: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var signal: AbortSignal?
        get() = definedExternally
        set(value) = definedExternally
}

@Suppress("NESTED_CLASS_IN_EXTERNAL_INTERFACE")
external interface ReadableStream<R> {
    var locked: Boolean
    fun cancel(reason: Any = definedExternally): Promise<Unit>
    fun getReader(): ReadableStreamDefaultReader<R>
    fun getReader(options: ReadableStreamGetReaderOptions): ReadableStreamBYOBReader
    fun <T> pipeThrough(transform: ReadableWritablePair<T, R>, options: StreamPipeOptions = definedExternally): ReadableStream<T>
    fun pipeTo(destination: WritableStream<R>, options: StreamPipeOptions = definedExternally): Promise<Unit>
    fun tee(): dynamic /* JsTuple<ReadableStream<R>, ReadableStream<R>> */
    fun values(options: ReadableStreamValuesOptions = definedExternally): AsyncIterable.Iterator<R>

    companion object {
        var prototype: ReadableStream<Uint8Array>
    }
}

external open class ReadableStreamDefaultReader<R>(stream: ReadableStream<Uint8Array>) {
    open var closed: Promise<Unit>
    open fun cancel(reason: Any = definedExternally): Promise<Unit>
    open fun read(): Promise<dynamic /* `T$15`<R> | `T$16` */>
    open fun releaseLock()
}

external open class ReadableStreamBYOBReader(stream: ReadableStream<Uint8Array>) {
    open var closed: Promise<Unit>
    open fun cancel(reason: Any = definedExternally): Promise<Unit>
    open fun <T : ArrayBufferView> read(view: T): Promise<dynamic /* `T$15`<T> | `T$16` */>
    open fun releaseLock()
    open fun <T : ArrayBufferView> readAtLeast(minElements: Number, view: T): Promise<dynamic /* `T$15`<T> | `T$16` */>
}

external interface ReadableStreamGetReaderOptions {
    var mode: String /* "byob" */
}

external interface ReadableStreamBYOBRequest {
    var view: Uint8Array?
    fun respond(bytesWritten: Number)
    fun respondWithNewView(view: ArrayBuffer)
    fun respondWithNewView(view: ArrayBufferView)
    var atLeast: Number?
}

external interface ReadableStreamDefaultController<R> {
    var desiredSize: Number?
    fun close()
    fun enqueue(chunk: R = definedExternally)
    fun error(reason: Any)
}

external interface ReadableByteStreamController {
    var byobRequest: ReadableStreamBYOBRequest?
    var desiredSize: Number?
    fun close()
    fun enqueue(chunk: ArrayBuffer)
    fun enqueue(chunk: ArrayBufferView)
    fun error(reason: Any)
}

external interface WritableStreamDefaultController {
    var signal: AbortSignal
    fun error(reason: Any = definedExternally)
}

external interface TransformStreamDefaultController<O>

external interface ReadableWritablePair<R, W> {
    var writable: WritableStream<W>
    var readable: ReadableStream<R>
}

external open class WritableStream<W>(underlyingSink: UnderlyingSink__0 = definedExternally, queuingStrategy: QueuingStrategy__0 = definedExternally) {
    open var locked: Boolean
    open fun abort(reason: Any = definedExternally): Promise<Unit>
    open fun close(): Promise<Unit>
    open fun getWriter(): WritableStreamDefaultWriter<W>
}

external open class WritableStream__0 : WritableStream<Any>

external open class WritableStreamDefaultWriter<W>(stream: WritableStream__0) {
    open var closed: Promise<Unit>
    open var ready: Promise<Unit>
    open var desiredSize: Number?
    open fun abort(reason: Any = definedExternally): Promise<Unit>
    open fun close(): Promise<Unit>
    open fun write(chunk: W = definedExternally): Promise<Unit>
    open fun releaseLock()
}

external open class TransformStream<I, O>(transformer: Transformer<I, O> = definedExternally, writableStrategy: QueuingStrategy<I> = definedExternally, readableStrategy: QueuingStrategy<O> = definedExternally) {
    open var readable: ReadableStream<O>
    open var writable: WritableStream<I>
}

external open class FixedLengthStream : IdentityTransformStream {
    constructor(expectedLength: Number, queuingStrategy: IdentityTransformStreamQueuingStrategy = definedExternally)
    constructor(expectedLength: Number)
    constructor(expectedLength: Any, queuingStrategy: IdentityTransformStreamQueuingStrategy = definedExternally)
    constructor(expectedLength: Any)
}

external open class IdentityTransformStream(queuingStrategy: IdentityTransformStreamQueuingStrategy = definedExternally) : TransformStream<dynamic /* ArrayBuffer | ArrayBufferView */, Uint8Array>

external interface IdentityTransformStreamQueuingStrategy {
    var highWaterMark: dynamic /* Number? | Any? */
        get() = definedExternally
        set(value) = definedExternally
}

external interface ReadableStreamValuesOptions {
    var preventCancel: Boolean?
        get() = definedExternally
        set(value) = definedExternally
}

external open class CompressionStream(format: String /* "gzip" | "deflate" | "deflate-raw" */) : TransformStream<dynamic /* ArrayBuffer | ArrayBufferView */, Uint8Array>

external open class DecompressionStream(format: String /* "gzip" | "deflate" | "deflate-raw" */) : TransformStream<dynamic /* ArrayBuffer | ArrayBufferView */, Uint8Array>

external open class TextEncoderStream : TransformStream<String, Uint8Array>

external open class TextDecoderStream(label: String = definedExternally, options: TextDecoderStreamTextDecoderStreamInit = definedExternally) : TransformStream<dynamic /* ArrayBuffer | ArrayBufferView */, String>

external interface TextDecoderStreamTextDecoderStreamInit {
    var fatal: Boolean?
        get() = definedExternally
        set(value) = definedExternally
}

external open class ByteLengthQueuingStrategy(init: QueuingStrategyInit) : QueuingStrategy<ArrayBufferView>

external open class CountQueuingStrategy(init: QueuingStrategyInit) : QueuingStrategy__0

external interface QueuingStrategyInit {
    var highWaterMark: Number
}

external open class TailEvent(type: String, init: EventInit = definedExternally) : ExtendableEvent {
    open var events: Array<TraceItem>
    open var traces: Array<TraceItem>
}

external interface TraceItem {
    var event: dynamic /* TraceItemFetchEventInfo? | TraceItemScheduledEventInfo? | TraceItemAlarmEventInfo? | TraceItemQueueEventInfo? | TraceItemEmailEventInfo? | TraceItemCustomEventInfo? */
        get() = definedExternally
        set(value) = definedExternally
    var eventTimestamp: Number?
    var logs: Array<TraceLog>
    var exceptions: Array<TraceException>
    var diagnosticsChannelEvents: Array<TraceDiagnosticChannelEvent>
    var scriptName: String?
    var dispatchNamespace: String?
        get() = definedExternally
        set(value) = definedExternally
    var scriptTags: Array<String>?
        get() = definedExternally
        set(value) = definedExternally
    var outcome: String
}

external interface TraceItemAlarmEventInfo {
    var scheduledTime: Date
}

external interface TraceItemCustomEventInfo

external interface TraceItemScheduledEventInfo {
    var scheduledTime: Number
    var cron: String
}

external interface TraceItemQueueEventInfo {
    var queue: String
    var batchSize: Number
}

external interface TraceItemEmailEventInfo {
    var mailFrom: String
    var rcptTo: String
    var rawSize: Number
}

external interface TraceItemFetchEventInfo {
    var response: TraceItemFetchEventInfoResponse?
        get() = definedExternally
        set(value) = definedExternally
    var request: TraceItemFetchEventInfoRequest
}

external interface TraceItemFetchEventInfoRequest {
    var cf: Any?
        get() = definedExternally
        set(value) = definedExternally
    var headers: Record<String, String>
    var method: String
    var url: String
    fun getUnredacted(): TraceItemFetchEventInfoRequest
}

external interface TraceItemFetchEventInfoResponse {
    var status: Number
}

external interface TraceLog {
    var timestamp: Number
    var level: String
    var message: Any
}

external interface TraceException {
    var timestamp: Number
    var message: String
    var name: String
}

external interface TraceDiagnosticChannelEvent {
    var timestamp: Number
    var channel: String
    var message: Any
}

external interface TraceMetrics {
    var cpuTime: Number
    var wallTime: Number
}

external interface UnsafeTraceMetrics {
    fun fromTrace(item: TraceItem): TraceMetrics
}

external open class URL {
    constructor(url: String, base: String = definedExternally)
    constructor(url: String)
    constructor(url: String, base: URL = definedExternally)
    constructor(url: URL, base: String = definedExternally)
    constructor(url: URL)
    constructor(url: URL, base: URL = definedExternally)
    open var href: String
    open var origin: String
    open var protocol: String
    open var username: String
    open var password: String
    open var host: String
    open var hostname: String
    open var port: String
    open var pathname: String
    open var search: String
    open var searchParams: URLSearchParams
    open var hash: String
    override fun toString(): String
    open fun toJSON(): String
}

external open class URLSearchParams {
    constructor(init: URLSearchParams = definedExternally)
    constructor()
    constructor(init: String = definedExternally)
    constructor(init: Record<String, String> = definedExternally)
    constructor(init: Array<Any /* JsTuple<key, String, value, String> */> = definedExternally)
    open fun append(name: String, value: String)
    open fun delete(name: String)
    open fun get(name: String): String?
    open fun getAll(name: String): Array<String>
    open fun has(name: String): Boolean
    open fun set(name: String, value: String)
    open fun sort()
    open fun entries(): JsIterable.Iterator<dynamic /* JsTuple<key> */> { definedExternally }
}

external open class URLPattern {
    constructor(input: String = definedExternally, baseURL: String = definedExternally)
    constructor()
    constructor(input: String = definedExternally)
    constructor(input: URLPatternURLPatternInit = definedExternally, baseURL: String = definedExternally)
    constructor(input: URLPatternURLPatternInit = definedExternally)
    open fun test(input: String = definedExternally, baseURL: String = definedExternally): Boolean
    open fun test(): Boolean
    open fun test(input: String = definedExternally): Boolean
    open fun test(input: URLPatternURLPatternInit = definedExternally, baseURL: String = definedExternally): Boolean
    open fun test(input: URLPatternURLPatternInit = definedExternally): Boolean
    open fun exec(input: String = definedExternally, baseURL: String = definedExternally): URLPatternURLPatternResult?
    open fun exec(): URLPatternURLPatternResult?
    open fun exec(input: String = definedExternally): URLPatternURLPatternResult?
    open fun exec(input: URLPatternURLPatternInit = definedExternally, baseURL: String = definedExternally): URLPatternURLPatternResult?
    open fun exec(input: URLPatternURLPatternInit = definedExternally): URLPatternURLPatternResult?
}

external interface URLPatternURLPatternInit {
    var protocol: String?
        get() = definedExternally
        set(value) = definedExternally
    var username: String?
        get() = definedExternally
        set(value) = definedExternally
    var password: String?
        get() = definedExternally
        set(value) = definedExternally
    var hostname: String?
        get() = definedExternally
        set(value) = definedExternally
    var port: String?
        get() = definedExternally
        set(value) = definedExternally
    var pathname: String?
        get() = definedExternally
        set(value) = definedExternally
    var search: String?
        get() = definedExternally
        set(value) = definedExternally
    var hash: String?
        get() = definedExternally
        set(value) = definedExternally
    var baseURL: String?
        get() = definedExternally
        set(value) = definedExternally
}

external interface URLPatternURLPatternComponentResult {
    var input: String
    var groups: Record<String, String>
}

external interface URLPatternURLPatternResult {
    var inputs: Array<dynamic /* String | URLPatternURLPatternInit */>
    var protocol: URLPatternURLPatternComponentResult
    var username: URLPatternURLPatternComponentResult
    var password: URLPatternURLPatternComponentResult
    var hostname: URLPatternURLPatternComponentResult
    var port: URLPatternURLPatternComponentResult
    var pathname: URLPatternURLPatternComponentResult
    var search: URLPatternURLPatternComponentResult
    var hash: URLPatternURLPatternComponentResult
}

external open class CloseEvent(type: String, initializer: CloseEventInit) : Event {
    open var code: Number
    open var reason: String
    open var wasClean: Boolean
}

external interface CloseEventInit {
    var code: Number?
        get() = definedExternally
        set(value) = definedExternally
    var reason: String?
        get() = definedExternally
        set(value) = definedExternally
    var wasClean: Boolean?
        get() = definedExternally
        set(value) = definedExternally
}

external open class MessageEvent(type: String, initializer: MessageEventInit) : Event {
    open var data: dynamic /* ArrayBuffer | String */
}

external interface MessageEventInit {
    var data: dynamic /* ArrayBuffer | String */
        get() = definedExternally
        set(value) = definedExternally
}

external interface ErrorEvent : Event {
    var filename: String
    var message: String
    var lineno: Number
    var colno: Number
    var error: Any
}

external interface WebSocketEventMap {
    var close: CloseEvent
    var message: MessageEvent
    var open: Event
    var error: ErrorEvent
}

external open class WebSocket : EventTarget<Record<String, Event>> {
    constructor(url: String, protocols: Array<String> = definedExternally)
    constructor(url: String)
    constructor(url: String, protocols: String = definedExternally)
    open fun accept()
    open fun send(message: ArrayBuffer)
    open fun send(message: ArrayBufferView)
    open fun send(message: String)
    open fun close(code: Number = definedExternally, reason: String = definedExternally)
    open fun serializeAttachment(attachment: Any)
    open fun deserializeAttachment(): Any?
    open var readyState: Number
    open var url: String?
    open var protocol: String?
    open var extensions: String?

    companion object {
        var READY_STATE_CONNECTING: Number
        var READY_STATE_OPEN: Number
        var READY_STATE_CLOSING: Number
        var READY_STATE_CLOSED: Number
    }
}

external object WebSocketPair {
}

external interface Socket

external interface SocketOptions {
    var secureTransport: String?
        get() = definedExternally
        set(value) = definedExternally
    var allowHalfOpen: Boolean
}

external interface SocketAddress {
    var hostname: String
    var port: Number
}

external interface TlsOptions {
    var expectedServerHostname: String?
        get() = definedExternally
        set(value) = definedExternally
}

external interface BasicImageTransformations {
    var width: Number?
        get() = definedExternally
        set(value) = definedExternally
    var height: Number?
        get() = definedExternally
        set(value) = definedExternally
    var fit: String? /* "scale-down" | "contain" | "cover" | "crop" | "pad" */
        get() = definedExternally
        set(value) = definedExternally
    var gravity: dynamic /* "left" | "right" | "top" | "bottom" | "center" | "auto" | BasicImageTransformationsGravityCoordinates? */
        get() = definedExternally
        set(value) = definedExternally
    var background: String?
        get() = definedExternally
        set(value) = definedExternally
    var rotate: Number? /* 0 | 90 | 180 | 270 | 360 */
        get() = definedExternally
        set(value) = definedExternally
}

external interface BasicImageTransformationsGravityCoordinates {
    var x: Number
    var y: Number
}

external interface RequestInitCfProperties : Record<String, Any> {
    var cacheEverything: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var cacheKey: String?
        get() = definedExternally
        set(value) = definedExternally
    var cacheTags: Array<String>?
        get() = definedExternally
        set(value) = definedExternally
    var cacheTtl: Number?
        get() = definedExternally
        set(value) = definedExternally
    var cacheTtlByStatus: Record<String, Number>?
        get() = definedExternally
        set(value) = definedExternally
    var scrapeShield: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var apps: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var image: RequestInitCfPropertiesImage?
        get() = definedExternally
        set(value) = definedExternally
    var minify: RequestInitCfPropertiesImageMinify?
        get() = definedExternally
        set(value) = definedExternally
    var mirage: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var polish: String? /* "lossy" | "lossless" | "off" */
        get() = definedExternally
        set(value) = definedExternally
    var resolveOverride: String?
        get() = definedExternally
        set(value) = definedExternally
}

external interface RequestInitCfPropertiesImageDraw : BasicImageTransformations {
    var url: String
    var opacity: Number?
        get() = definedExternally
        set(value) = definedExternally
    var repeat: dynamic /* Boolean? | "x" | "y" */
        get() = definedExternally
        set(value) = definedExternally
    var top: Number?
        get() = definedExternally
        set(value) = definedExternally
    var left: Number?
        get() = definedExternally
        set(value) = definedExternally
    var bottom: Number?
        get() = definedExternally
        set(value) = definedExternally
    var right: Number?
        get() = definedExternally
        set(value) = definedExternally
}

external interface `T$1` {
    var left: Number?
        get() = definedExternally
        set(value) = definedExternally
    var top: Number?
        get() = definedExternally
        set(value) = definedExternally
    var right: Number?
        get() = definedExternally
        set(value) = definedExternally
    var bottom: Number?
        get() = definedExternally
        set(value) = definedExternally
}

external interface RequestInitCfPropertiesImage : BasicImageTransformations {
    @nativeGetter
    operator fun get(key: String): String? /* "share-publicly" */
    @nativeSetter
    operator fun set(key: String, value: String? /* "share-publicly" */)
    var dpr: Number?
        get() = definedExternally
        set(value) = definedExternally
    var trim: `T$1`?
        get() = definedExternally
        set(value) = definedExternally
    var quality: Number?
        get() = definedExternally
        set(value) = definedExternally
    var format: String? /* "avif" | "webp" | "json" | "jpeg" | "png" */
        get() = definedExternally
        set(value) = definedExternally
    var anim: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var metadata: String? /* "keep" | "copyright" | "none" */
        get() = definedExternally
        set(value) = definedExternally
    var sharpen: Number?
        get() = definedExternally
        set(value) = definedExternally
    var blur: Number?
        get() = definedExternally
        set(value) = definedExternally
    var draw: Array<RequestInitCfPropertiesImageDraw>?
        get() = definedExternally
        set(value) = definedExternally
    var border: dynamic /* `T$2`? | `T$3`? */
        get() = definedExternally
        set(value) = definedExternally
    var brightness: Number?
        get() = definedExternally
        set(value) = definedExternally
    var contrast: Number?
        get() = definedExternally
        set(value) = definedExternally
    var gamma: Number?
        get() = definedExternally
        set(value) = definedExternally
    var compression: String? /* "fast" */
        get() = definedExternally
        set(value) = definedExternally
}

external interface RequestInitCfPropertiesImageMinify {
    var javascript: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var css: Boolean?
        get() = definedExternally
        set(value) = definedExternally
    var html: Boolean?
        get() = definedExternally
        set(value) = definedExternally
}

external interface IncomingRequestCfPropertiesBase : Record<String, Any> {
    var asn: Number
    var asOrganization: String
    var clientAcceptEncoding: String?
        get() = definedExternally
        set(value) = definedExternally
    var clientTcpRtt: Number?
        get() = definedExternally
        set(value) = definedExternally
    var colo: String
    var edgeRequestKeepAliveStatus: Number /* 0 | 1 | 2 | 3 | 4 | 5 */
    var httpProtocol: String
    var requestPriority: String
    var tlsVersion: String
    var tlsCipher: String
    var tlsExportedAuthenticator: IncomingRequestCfPropertiesExportedAuthenticatorMetadata?
        get() = definedExternally
        set(value) = definedExternally
}

external interface IncomingRequestCfPropertiesBotManagementBase {
    var score: Number
    var verifiedBot: Boolean
    var corporateProxy: Boolean
    var staticResource: Boolean
    var detectionIds: Array<Number>
}

external interface IncomingRequestCfPropertiesBotManagement {
    var botManagement: IncomingRequestCfPropertiesBotManagementBase
    var clientTrustScore: Number
}

external interface IncomingRequestCfPropertiesBotManagementEnterprise : IncomingRequestCfPropertiesBotManagement {
    override var botManagement: IncomingRequestCfPropertiesBotManagementBase /* IncomingRequestCfPropertiesBotManagementBase & `T$4` */
}

external interface IncomingRequestCfPropertiesCloudflareForSaaSEnterprise<HostMetadata> {
    var hostMetadata: HostMetadata
}

external interface IncomingRequestCfPropertiesCloudflareAccessOrApiShield {
    var tlsClientAuth: dynamic /* IncomingRequestCfPropertiesTLSClientAuth | IncomingRequestCfPropertiesTLSClientAuthPlaceholder */
        get() = definedExternally
        set(value) = definedExternally
}

external interface IncomingRequestCfPropertiesExportedAuthenticatorMetadata {
    var clientHandshake: String
    var serverHandshake: String
    var clientFinished: String
    var serverFinished: String
}

external interface IncomingRequestCfPropertiesGeographicInformation {
    var country: String? /* "AD" | "AE" | "AF" | "AG" | "AI" | "AL" | "AM" | "AO" | "AQ" | "AR" | "AS" | "AT" | "AU" | "AW" | "AX" | "AZ" | "BA" | "BB" | "BD" | "BE" | "BF" | "BG" | "BH" | "BI" | "BJ" | "BL" | "BM" | "BN" | "BO" | "BQ" | "BR" | "BS" | "BT" | "BV" | "BW" | "BY" | "BZ" | "CA" | "CC" | "CD" | "CF" | "CG" | "CH" | "CI" | "CK" | "CL" | "CM" | "CN" | "CO" | "CR" | "CU" | "CV" | "CW" | "CX" | "CY" | "CZ" | "DE" | "DJ" | "DK" | "DM" | "DO" | "DZ" | "EC" | "EE" | "EG" | "EH" | "ER" | "ES" | "ET" | "FI" | "FJ" | "FK" | "FM" | "FO" | "FR" | "GA" | "GB" | "GD" | "GE" | "GF" | "GG" | "GH" | "GI" | "GL" | "GM" | "GN" | "GP" | "GQ" | "GR" | "GS" | "GT" | "GU" | "GW" | "GY" | "HK" | "HM" | "HN" | "HR" | "HT" | "HU" | "ID" | "IE" | "IL" | "IM" | "IN" | "IO" | "IQ" | "IR" | "IS" | "IT" | "JE" | "JM" | "JO" | "JP" | "KE" | "KG" | "KH" | "KI" | "KM" | "KN" | "KP" | "KR" | "KW" | "KY" | "KZ" | "LA" | "LB" | "LC" | "LI" | "LK" | "LR" | "LS" | "LT" | "LU" | "LV" | "LY" | "MA" | "MC" | "MD" | "ME" | "MF" | "MG" | "MH" | "MK" | "ML" | "MM" | "MN" | "MO" | "MP" | "MQ" | "MR" | "MS" | "MT" | "MU" | "MV" | "MW" | "MX" | "MY" | "MZ" | "NA" | "NC" | "NE" | "NF" | "NG" | "NI" | "NL" | "NO" | "NP" | "NR" | "NU" | "NZ" | "OM" | "PA" | "PE" | "PF" | "PG" | "PH" | "PK" | "PL" | "PM" | "PN" | "PR" | "PS" | "PT" | "PW" | "PY" | "QA" | "RE" | "RO" | "RS" | "RU" | "RW" | "SA" | "SB" | "SC" | "SD" | "SE" | "SG" | "SH" | "SI" | "SJ" | "SK" | "SL" | "SM" | "SN" | "SO" | "SR" | "SS" | "ST" | "SV" | "SX" | "SY" | "SZ" | "TC" | "TD" | "TF" | "TG" | "TH" | "TJ" | "TK" | "TL" | "TM" | "TN" | "TO" | "TR" | "TT" | "TV" | "TW" | "TZ" | "UA" | "UG" | "UM" | "US" | "UY" | "UZ" | "VA" | "VC" | "VE" | "VG" | "VI" | "VN" | "VU" | "WF" | "WS" | "YE" | "YT" | "ZA" | "ZM" | "ZW" | "T1" */
        get() = definedExternally
        set(value) = definedExternally
    var isEUCountry: String? /* "1" */
        get() = definedExternally
        set(value) = definedExternally
    var continent: String? /* "AF" | "AN" | "AS" | "EU" | "NA" | "OC" | "SA" */
        get() = definedExternally
        set(value) = definedExternally
    var city: String?
        get() = definedExternally
        set(value) = definedExternally
    var postalCode: String?
        get() = definedExternally
        set(value) = definedExternally
    var latitude: String?
        get() = definedExternally
        set(value) = definedExternally
    var longitude: String?
        get() = definedExternally
        set(value) = definedExternally
    var timezone: String?
        get() = definedExternally
        set(value) = definedExternally
    var region: String?
        get() = definedExternally
        set(value) = definedExternally
    var regionCode: String?
        get() = definedExternally
        set(value) = definedExternally
    var metroCode: String?
        get() = definedExternally
        set(value) = definedExternally
}

external interface IncomingRequestCfPropertiesTLSClientAuth {
    var certPresented: String /* "1" */
    var certVerified: Any
    var certRevoked: String /* "1" | "0" */
    var certIssuerDN: String
    var certSubjectDN: String
    var certIssuerDNRFC2253: String
    var certSubjectDNRFC2253: String
    var certIssuerDNLegacy: String
    var certSubjectDNLegacy: String
    var certSerial: String
    var certIssuerSerial: String
    var certSKI: String
    var certIssuerSKI: String
    var certFingerprintSHA1: String
    var certFingerprintSHA256: String
    var certNotBefore: String
    var certNotAfter: String
}

external interface IncomingRequestCfPropertiesTLSClientAuthPlaceholder {
    var certPresented: String /* "0" */
    var certVerified: String /* "NONE" */
    var certRevoked: String /* "0" */
    var certIssuerDN: String /* "" */
    var certSubjectDN: String /* "" */
    var certIssuerDNRFC2253: String /* "" */
    var certSubjectDNRFC2253: String /* "" */
    var certIssuerDNLegacy: String /* "" */
    var certSubjectDNLegacy: String /* "" */
    var certSerial: String /* "" */
    var certIssuerSerial: String /* "" */
    var certSKI: String /* "" */
    var certIssuerSKI: String /* "" */
    var certFingerprintSHA1: String /* "" */
    var certFingerprintSHA256: String /* "" */
    var certNotBefore: String /* "" */
    var certNotAfter: String /* "" */
}

external interface D1Result<T> {
    var results: Array<T>
    var success: Boolean
    var meta: Any
    var error: Any?
        get() = definedExternally
        set(value) = definedExternally
}

external interface D1ExecResult {
    var count: Number
    var duration: Number
}

external open class D1Database {
    open fun prepare(query: String): D1PreparedStatement
    open fun dump(): Promise<ArrayBuffer>
    open fun <T> batch(statements: Array<D1PreparedStatement>): Promise<Array<D1Result<T>>>
    open fun exec(query: String): Promise<D1ExecResult>
}

external open class D1PreparedStatement {
    open fun bind(vararg values: Any): D1PreparedStatement
    open fun <T> first(colName: String): Promise<T?>
    open fun <T: Any> first(): Promise<Record<String, T>?>
    open fun <T> run(): Promise<D1Result<T>>
    open fun <T> all(): Promise<D1Result<Array<T>>>
    open fun <T> raw(): Promise<Array<T>>
}

external interface EmailMessage {
    var from: String
    var to: String
}

external interface ForwardableEmailMessage : EmailMessage {
    var raw: ReadableStream<Uint8Array>
    var headers: Headers
    var rawSize: Number
    fun setReject(reason: String)
    fun forward(rcptTo: String, headers: Headers = definedExternally): Promise<Unit>
}

external interface SendEmail {
    fun send(message: EmailMessage): Promise<Unit>
}

external open class EmailEvent(type: String, init: EventInit = definedExternally) : ExtendableEvent {
    open var message: ForwardableEmailMessage
}

typealias EmailExportedHandler<Env> = (message: ForwardableEmailMessage, env: Env, ctx: ExecutionContext) -> dynamic

typealias Params<P> = Record<P, dynamic /* String | Array<String> */>

external interface `T$5` {
    var fetch: Any
}

external interface EventContext<Env, P : String, Data> {
    var request: Request__0
    var functionPath: String
    var waitUntil: (promise: Promise<Any>) -> Unit
    var passThroughOnException: () -> Unit
    var next: (input: dynamic /* Request__0 | String */, init: RequestInit__0) -> Promise<Response>
    var env: Env /* Env & `T$6` */
    var params: Params<P>
    var data: Data
}

typealias PagesFunction<Env, Params, Data> = (context: EventContext<Env, Params, Data>) -> dynamic

external interface EventPluginContext<Env, P : String, Data, PluginArgs> {
    var request: Request__0
    var functionPath: String
    var waitUntil: (promise: Promise<Any>) -> Unit
    var passThroughOnException: () -> Unit
    var next: (input: dynamic /* Request__0 | String */, init: RequestInit__0) -> Promise<Response>
    var env: Env /* Env & `T$6` */
    var params: Params<P>
    var data: Data
    var pluginArgs: PluginArgs
}

typealias PagesPluginFunction<Env, Params, Data, PluginArgs> = (context: EventPluginContext<Env, Params, Data, PluginArgs>) -> dynamic

external interface PubSubMessage {
    var mid: Number
    var broker: String
    var topic: String
    var clientId: String
    var jti: String?
        get() = definedExternally
        set(value) = definedExternally
    var receivedAt: Number
    var contentType: String
    var payloadFormatIndicator: Number
    var payload: dynamic /* String | Uint8Array */
        get() = definedExternally
        set(value) = definedExternally
}

external interface JsonWebKeyWithKid : JsonWebKey {
    var kid: String
}

external interface DynamicDispatchLimits {
    var cpuMs: Number?
        get() = definedExternally
        set(value) = definedExternally
    var subRequests: Number?
        get() = definedExternally
        set(value) = definedExternally
}

external interface DynamicDispatchOptions {
    var limits: DynamicDispatchLimits?
        get() = definedExternally
        set(value) = definedExternally
    var outbound: Json?
        get() = definedExternally
        set(value) = definedExternally
}

external interface DispatchNamespace {
    fun get(name: String, args: Json = definedExternally, options: DynamicDispatchOptions = definedExternally): Fetcher
}