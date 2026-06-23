package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.framework.kSerializer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.serialization.json.JsonElement
import kotlin.js.JsExport
import kotlin.js.JsName
import kotlin.js.Promise

internal external interface RawPartyServerConnection {
    val id: String
    val uri: String?
    val state: dynamic
    fun setState(state: dynamic): dynamic
    fun send(message: dynamic)
    fun close(code: Int = definedExternally, reason: String = definedExternally)
}

internal external interface RawPartyServerConnectionContext {
    val request: RawWorkerRequest
}

internal external interface RawPartyServerInstance {
    val name: String
    val env: dynamic
    val ctx: RawDurableObjectState
    fun broadcast(message: dynamic, without: Array<String> = definedExternally)
    fun getConnection(id: String): RawPartyServerConnection?
    fun getConnections(tag: String = definedExternally): dynamic
}

@JsExport
class PartyServerMessage internal constructor(
    private val raw: dynamic,
) {
    val isText: Boolean
        get() = jsTypeOf(raw) == "string"

    fun textOrNull(): String? =
        if (isText) raw.unsafeCast<String>() else null

    fun requireText(): String = textOrNull() ?: error("PartyServer message is not text")

    @JsExport.Ignore
    fun bytesOrNull(): ByteArray? = when {
        isArrayBuffer(raw) -> arrayBufferToByteArray(raw)
        isArrayBufferView(raw) -> typedArrayToByteArray(raw)
        else -> null
    }

    @JsExport.Ignore
    fun requireBytes(): ByteArray = bytesOrNull() ?: error("PartyServer message is not binary")

    @JsExport.Ignore
    fun jsonElementOrNull(): JsonElement? =
        textOrNull()?.let { encoded -> runCatching { json.parseToJsonElement(encoded) }.getOrNull() }

    fun jsonTextOrNull(): String? =
        textOrNull()?.takeIf { encoded -> runCatching { json.parseToJsonElement(encoded) }.isSuccess }

    @JsExport.Ignore
    inline fun <reified T> decodeOrNull(): T? =
        textOrNull()?.let { encoded -> runCatching { json.decodeFromString<T>(encoded) }.getOrNull() }

    @JsExport.Ignore
    fun raw(): Any = raw.unsafeCast<Any>()
}

@JsExport
class PartyServerConnection internal constructor(
    private val raw: RawPartyServerConnection,
) {
    val id: String
        get() = raw.id

    val uri: String?
        get() = raw.uri

    @JsExport.Ignore
    val stateElementOrNull: JsonElement?
        get() {
            val raw0 = raw.state
            return if (raw0 == null) null else dynamicToJsonElement(raw0)
        }

    fun stateJsonTextOrNull(): String? =
        stateElementOrNull?.toJsonText()

    @JsExport.Ignore
    inline fun <reified T> stateOrNull(): T? =
        stateElementOrNull?.let { state -> runCatching { json.decodeFromJsonElement(kSerializer<T>(), state) }.getOrNull() }

    @JsExport.Ignore
    fun clearState(): JsonElement? {
        val raw0 = raw.setState(null)
        return if (raw0 == null) null else dynamicToJsonElement(raw0)
    }

    fun clearStateJsonText(): String? =
        clearState()?.toJsonText()

    @JsExport.Ignore
    fun setState(state: JsonElement?): JsonElement? {
        val raw0 = raw.setState(state?.toDynamic())
        return if (raw0 == null) null else dynamicToJsonElement(raw0)
    }

    fun setStateJsonText(stateJson: String?): String? =
        setState(parseJsonTextOrNull(stateJson))?.toJsonText()

    @JsExport.Ignore
    inline fun <reified T> setState(value: T?): JsonElement? =
        setState(value?.let { json.encodeToJsonElement(kSerializer<T>(), it) })

    fun send(message: String) {
        raw.send(message)
    }

    @JsExport.Ignore
    fun send(message: ByteArray) {
        raw.send(message.toUint8Array())
    }

    @JsExport.Ignore
    fun sendJson(value: JsonElement) {
        raw.send(json.encodeToString(JsonElement.serializer(), value))
    }

    @PublishedApi
    @JsExport.Ignore
    internal fun sendEncodedJson(encoded: String) {
        raw.send(encoded)
    }

    fun sendJsonText(jsonText: String) {
        raw.send(jsonText)
    }

    @JsExport.Ignore
    inline fun <reified T> sendJson(value: T) {
        sendEncodedJson(json.encodeToString(kSerializer<T>(), value))
    }

    fun close(
        code: Int? = null,
        reason: String? = null,
    ) {
        when {
            code != null && reason != null -> raw.close(code, reason)
            code != null -> raw.close(code)
            else -> raw.close()
        }
    }
}

@JsExport
class PartyServerConnectionContext internal constructor(
    private val raw: RawPartyServerConnectionContext,
) {
    val request: CloudflareWorkerRequest = CloudflareWorkerRequest(raw.request)
}

@JsExport
class PartyServerRoom internal constructor(
    private val raw: RawPartyServerInstance,
) {
    val id: String
        get() = raw.name

    val name: String
        get() = raw.name

    val cloudflare: CloudflareContext
        get() = CloudflareContext(raw.env.unsafeCast<CloudflareEnv>())

    val storage: DurableObjectStorage
        get() = DurableObjectStorage(raw.ctx.storage)

    @JsExport.Ignore
    fun rawRoom(): Any = raw.unsafeCast<Any>()

    @JsExport.Ignore
    fun rawDurableObjectStateOrNull(): DurableObjectState? =
        raw.asDynamic().ctx?.unsafeCast<RawDurableObjectState?>()?.let(::DurableObjectState)

    fun hasRawDurableObjectState(): Boolean = raw.asDynamic().ctx != null

    fun broadcast(message: String) {
        raw.broadcast(message)
    }

    @JsExport.Ignore
    fun broadcast(
        message: String,
        without: List<String> = emptyList(),
    ) {
        if (without.isEmpty()) {
            raw.broadcast(message)
        } else {
            raw.broadcast(message, without.toTypedArray())
        }
    }

    fun broadcastWithout(
        message: String,
        without: Array<String>,
    ) {
        raw.broadcast(message, without)
    }

    @JsExport.Ignore
    fun broadcast(
        message: ByteArray,
        without: List<String> = emptyList(),
    ) {
        val payload = message.toUint8Array()
        if (without.isEmpty()) {
            raw.broadcast(payload)
        } else {
            raw.broadcast(payload, without.toTypedArray())
        }
    }

    @JsExport.Ignore
    fun broadcastJson(
        value: JsonElement,
        without: List<String> = emptyList(),
    ) {
        broadcast(json.encodeToString(JsonElement.serializer(), value), without)
    }

    @PublishedApi
    @JsExport.Ignore
    internal fun broadcastEncodedJson(
        encoded: String,
        without: List<String>,
    ) {
        broadcast(encoded, without)
    }

    fun broadcastJsonText(
        jsonText: String,
        without: Array<String> = emptyArray(),
    ) {
        if (without.isEmpty()) {
            raw.broadcast(jsonText)
        } else {
            raw.broadcast(jsonText, without)
        }
    }

    @JsExport.Ignore
    inline fun <reified T> broadcastJson(
        value: T,
        without: List<String> = emptyList(),
    ) {
        broadcastEncodedJson(json.encodeToString(kSerializer<T>(), value), without)
    }

    fun connectionOrNull(id: String): PartyServerConnection? =
        raw.getConnection(id)?.let(::PartyServerConnection)

    fun connection(id: String): PartyServerConnection =
        connectionOrNull(id) ?: error("Missing PartyServer connection '$id'")

    @JsExport.Ignore
    fun connections(tag: String? = null): List<PartyServerConnection> {
        val iterable = if (tag == null) raw.getConnections() else raw.getConnections(tag)
        return iterableToList(iterable).map { value -> PartyServerConnection(value.unsafeCast<RawPartyServerConnection>()) }
    }

    fun connectionsArray(tag: String? = null): Array<PartyServerConnection> =
        connections(tag).toTypedArray()

    @JsExport.Ignore
    suspend fun <T> blockConcurrencyWhile(block: suspend () -> T): T {
        var result: Result<T>? = null
        raw.ctx.blockConcurrencyWhile {
            GlobalScope.promise<Any?> {
                result = runCatching { block() }
                null
            }
        }.await()
        return result!!.getOrThrow()
    }

    fun blockConcurrencyWhileAsync(block: () -> Promise<Any?>): Promise<Any?> =
        raw.ctx.blockConcurrencyWhile(block)
}

@JsExport
data class PartyServerOptions(
    val hibernate: Boolean = false,
) {
    internal fun toJsObject(): Any {
        val options = js("({})")
        options.hibernate = hibernate
        return options.unsafeCast<Any>()
    }
}

@JsExport
fun partyServerOptions(hibernate: Boolean = false): Any = PartyServerOptions(hibernate).toJsObject()

@JsExport
fun partyServerRequest(value: Any): CloudflareWorkerRequest =
    CloudflareWorkerRequest(value.unsafeCast<RawWorkerRequest>())

@JsExport.Ignore
fun partyServerExecutionContext(value: Any): WorkerExecutionContext =
    value.unsafeCast<WorkerExecutionContext>()

@OptIn(DelicateCoroutinesApi::class)
@JsExport
open class PartyServerDelegate(room: Any) {
    protected val room: PartyServerRoom = PartyServerRoom(room.unsafeCast<RawPartyServerInstance>())

    protected open val serverOptions: PartyServerOptions? = null

    @JsName("options")
    val options: Any?
        get() = serverOptions?.toJsObject()

    protected open suspend fun handleConnectionTags(
        connection: PartyServerConnection,
        context: PartyServerConnectionContext,
    ): Array<String> = emptyArray()

    protected open suspend fun handleStart(props: Any?) {
    }

    protected open suspend fun handleConnect(
        connection: PartyServerConnection,
        context: PartyServerConnectionContext,
    ) {
    }

    protected open suspend fun handleMessage(
        message: PartyServerMessage,
        sender: PartyServerConnection,
    ) {
    }

    protected open suspend fun handleConnectionClose(
        connection: PartyServerConnection,
        code: Int,
        reason: String,
        wasClean: Boolean,
    ) {
        handleClose(connection)
    }

    protected open suspend fun handleClose(connection: PartyServerConnection) {
    }

    protected open suspend fun handleError(
        connection: PartyServerConnection,
        error: Throwable,
    ) {
    }

    protected open suspend fun handleRequest(request: CloudflareWorkerRequest): Any =
        workerResponse(
            body = "Not found",
            status = 404,
            headers = mapOf("Content-Type" to "text/plain; charset=utf-8"),
        )

    protected open suspend fun handleAlarm() {
    }

    @JsName("getConnectionTags")
    fun getConnectionTags(connectionValue: Any, contextValue: Any): dynamic = GlobalScope.promise {
        handleConnectionTags(connection(connectionValue), context(contextValue))
    }

    @JsName("onStart")
    fun onStart(props: Any? = null): dynamic = GlobalScope.promise {
        handleStart(props)
    }

    @JsName("onConnect")
    fun onConnect(connectionValue: Any, contextValue: Any): dynamic = GlobalScope.promise {
        handleConnect(connection(connectionValue), context(contextValue))
    }

    @JsName("onMessage")
    fun onMessage(connectionValue: Any, messageValue: Any): dynamic = GlobalScope.promise {
        handleMessage(PartyServerMessage(messageValue), connection(connectionValue))
    }

    @JsName("onClose")
    fun onClose(
        connectionValue: Any,
        code: Int = 0,
        reason: String = "",
        wasClean: Boolean = false,
    ): dynamic = GlobalScope.promise {
        handleConnectionClose(connection(connectionValue), code, reason, wasClean)
    }

    @JsName("onError")
    fun onError(connectionValue: Any, errorValue: Any): dynamic = GlobalScope.promise {
        handleError(connection(connectionValue), errorValue.asThrowable())
    }

    @JsName("onRequest")
    fun onRequest(requestValue: Any): dynamic = GlobalScope.promise {
        handleRequest(request(requestValue))
    }

    @JsName("onAlarm")
    fun onAlarm(): dynamic = GlobalScope.promise {
        handleAlarm()
    }

    protected fun connection(value: Any): PartyServerConnection =
        PartyServerConnection(value.unsafeCast<RawPartyServerConnection>())

    protected fun context(value: Any): PartyServerConnectionContext =
        PartyServerConnectionContext(value.unsafeCast<RawPartyServerConnectionContext>())

    protected fun request(value: Any): CloudflareWorkerRequest =
        CloudflareWorkerRequest(value.unsafeCast<RawWorkerRequest>())

    protected fun unauthorized(message: String = "Unauthorized"): Any =
        workerResponse(
            body = message,
            status = 401,
            headers = mapOf("Content-Type" to "text/plain; charset=utf-8"),
        )
}

private fun Any?.asThrowable(): Throwable = when (this) {
    is Throwable -> this
    null -> IllegalStateException("Unknown PartyServer error")
    else -> IllegalStateException(this.toString())
}

private fun isArrayBuffer(value: dynamic): Boolean =
    js("typeof ArrayBuffer !== 'undefined' && value instanceof ArrayBuffer") as Boolean

private fun isArrayBufferView(value: dynamic): Boolean =
    js("typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView(value)") as Boolean

private fun typedArrayToByteArray(value: dynamic): ByteArray {
    val view = js("new Uint8Array(value.buffer, value.byteOffset || 0, value.byteLength)")
    val bytes = ByteArray((view.length as Int))
    for (index in bytes.indices) {
        bytes[index] = (view[index] as Int).toByte()
    }
    return bytes
}

private fun arrayBufferToByteArray(buffer: dynamic): ByteArray {
    val view = js("new Uint8Array(buffer)")
    val bytes = ByteArray((view.length as Int))
    for (index in bytes.indices) {
        bytes[index] = (view[index] as Int).toByte()
    }
    return bytes
}

private fun ByteArray.toUint8Array(): dynamic {
    val length = size
    val view = js("new Uint8Array(length)")
    for (index in indices) {
        view[index] = this[index].toInt() and 0xFF
    }
    return view
}

private fun iterableToList(iterable: dynamic): List<dynamic> =
    js("Array.from(iterable)").unsafeCast<Array<dynamic>>().toList()
