@file:Suppress("unused", "INTERFACE_WITH_SUPERCLASS", "PropertyName")

package dev.shibasis.reaktor.cloudflare

import js.core.ReadonlyRecord
import kotlin.js.Date
import kotlin.js.Promise
import org.w3c.dom.WebSocket
import org.w3c.fetch.Request

/** Interface implemented by durable object classes. */
external interface DurableObject {
    fun fetch(request: Request): WorkerResponse? = definedExternally
    fun alarm(): Any? = definedExternally
    fun webSocketMessage(ws: WebSocket, message: Any?): Any? = definedExternally
    fun webSocketClose(ws: WebSocket, code: Int, reason: String, wasClean: Boolean): Any? = definedExternally
    fun webSocketError(ws: WebSocket, error: Any?): Any? = definedExternally
}

/** Stub that can be used to call a durable object instance. */
external interface DurableObjectStub<T> : Fetcher {
    val id: DurableObjectId
    val name: String?
}

/** Identifier representing a durable object instance. */
external interface DurableObjectId {
    fun toString(): String
    fun equals(other: DurableObjectId): Boolean
    val name: String?
}

/** Namespace binding used to look up and create durable object instances. */
external interface DurableObjectNamespace<T> {
    fun newUniqueId(options: DurableObjectNamespaceNewUniqueIdOptions = definedExternally): DurableObjectId
    fun idFromName(name: String): DurableObjectId
    fun idFromString(id: String): DurableObjectId
    fun get(id: DurableObjectId, options: DurableObjectNamespaceGetDurableObjectOptions = definedExternally): DurableObjectStub<T>
    fun jurisdiction(jurisdiction: DurableObjectJurisdiction): DurableObjectNamespace<T>
}

/** Region constraints used when locating durable objects. */
typealias DurableObjectJurisdiction = String

/** Optional hints supplied when creating unique identifiers. */
external interface DurableObjectNamespaceNewUniqueIdOptions {
    var jurisdiction: DurableObjectJurisdiction?
}

/** Location hints supplied when fetching a durable object stub. */
external interface DurableObjectNamespaceGetDurableObjectOptions {
    var locationHint: DurableObjectLocationHint?
}

/** Region hint string. */
typealias DurableObjectLocationHint = String

/** Execution state made available to a durable object instance. */
external interface DurableObjectState {
    fun waitUntil(promise: Promise<Any?>)
    val id: DurableObjectId
    val storage: DurableObjectStorage
    fun <T> blockConcurrencyWhile(callback: () -> Promise<T>): Promise<T>
    fun acceptWebSocket(ws: WebSocket, tags: Array<String>? = definedExternally)
    fun getWebSockets(tag: String? = definedExternally): Array<WebSocket>
    fun setWebSocketAutoResponse(maybeReqResp: WebSocketRequestResponsePair? = definedExternally)
    fun getWebSocketAutoResponse(): WebSocketRequestResponsePair?
    fun getWebSocketAutoResponseTimestamp(ws: WebSocket): Date?
    fun setHibernatableWebSocketEventTimeout(timeoutMs: Double? = definedExternally)
    fun getHibernatableWebSocketEventTimeout(): Double?
    fun getTags(ws: WebSocket): Array<String>
}

/** Durable object storage transaction object. */
external interface DurableObjectTransaction {
    fun <T> get(key: String, options: DurableObjectGetOptions = definedExternally): Promise<T?>
    fun <T> get(keys: Array<String>, options: DurableObjectGetOptions = definedExternally): Promise<Map<String, T>>
    fun <T> list(options: DurableObjectListOptions = definedExternally): Promise<Map<String, T>>
    fun <T> put(key: String, value: T, options: DurableObjectPutOptions = definedExternally): Promise<Unit>
    fun <T> put(entries: ReadonlyRecord<String, T>, options: DurableObjectPutOptions = definedExternally): Promise<Unit>
    fun delete(key: String, options: DurableObjectPutOptions = definedExternally): Promise<Boolean>
    fun delete(keys: Array<String>, options: DurableObjectPutOptions = definedExternally): Promise<Double>
    fun rollback()
    fun getAlarm(options: DurableObjectGetAlarmOptions = definedExternally): Promise<Double?>
    fun setAlarm(scheduledTime: Any, options: DurableObjectSetAlarmOptions = definedExternally): Promise<Unit>
    fun deleteAlarm(options: DurableObjectSetAlarmOptions = definedExternally): Promise<Unit>
}

/** Persistent storage made available to durable objects. */
external interface DurableObjectStorage {
    fun <T> get(key: String, options: DurableObjectGetOptions = definedExternally): Promise<T?>
    fun <T> get(keys: Array<String>, options: DurableObjectGetOptions = definedExternally): Promise<Map<String, T>>
    fun <T> list(options: DurableObjectListOptions = definedExternally): Promise<Map<String, T>>
    fun <T> put(key: String, value: T, options: DurableObjectPutOptions = definedExternally): Promise<Unit>
    fun <T> put(entries: ReadonlyRecord<String, T>, options: DurableObjectPutOptions = definedExternally): Promise<Unit>
    fun delete(key: String, options: DurableObjectPutOptions = definedExternally): Promise<Boolean>
    fun delete(keys: Array<String>, options: DurableObjectPutOptions = definedExternally): Promise<Double>
    fun deleteAll(options: DurableObjectPutOptions = definedExternally): Promise<Unit>
    fun <T> transaction(closure: (DurableObjectTransaction) -> Promise<T>): Promise<T>
    fun getAlarm(options: DurableObjectGetAlarmOptions = definedExternally): Promise<Double?>
    fun setAlarm(scheduledTime: Any, options: DurableObjectSetAlarmOptions = definedExternally): Promise<Unit>
    fun deleteAlarm(options: DurableObjectSetAlarmOptions = definedExternally): Promise<Unit>
    fun sync(): Promise<Unit>
    fun <T> transactionSync(closure: () -> T): T
}

/** Range and prefix controls applied when listing keys. */
external interface DurableObjectListOptions {
    var start: String?
    var startAfter: String?
    var end: String?
    var prefix: String?
    var reverse: Boolean?
    var limit: Int?
    var allowConcurrency: Boolean?
    var noCache: Boolean?
}

/** Options controlling how reads interact with the concurrency model. */
external interface DurableObjectGetOptions {
    var allowConcurrency: Boolean?
    var noCache: Boolean?
}

/** Options controlling how alarms are retrieved. */
external interface DurableObjectGetAlarmOptions {
    var allowConcurrency: Boolean?
}

/** Options controlling how writes interact with the concurrency model. */
external interface DurableObjectPutOptions {
    var allowConcurrency: Boolean?
    var allowUnconfirmed: Boolean?
    var noCache: Boolean?
}

/** Options supplied to alarm manipulation helpers. */
external interface DurableObjectSetAlarmOptions {
    var allowConcurrency: Boolean?
    var allowUnconfirmed: Boolean?
}

/** Synthetic request/response pair for websocket auto responses. */
external open class WebSocketRequestResponsePair(request: String, response: String) {
    val request: String
    val response: String
}
