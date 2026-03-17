package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.framework.kSerializer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.serialization.json.JsonElement
import kotlin.js.Promise
import kotlin.js.JsExport
import kotlin.js.JsName

internal external interface RawPartyConnection {
    val id: String
    val uri: String?
    val state: dynamic
    fun setState(state: dynamic): dynamic
    fun send(message: dynamic)
    fun close(code: Int = definedExternally, reason: String = definedExternally)
}

internal external interface RawPartyConnectionContext {
    val request: RawWorkerRequest
}

internal external interface RawPartyAssets {
    fun fetch(path: String): Promise<dynamic>
}

internal external interface RawPartyNamespace {
    fun get(id: String): RawPartyStub
}

internal external interface RawPartyStub {
    fun fetch(pathOrInit: dynamic = definedExternally, init: dynamic = definedExternally): Promise<RawWorkerResponse>
    fun socket(pathOrInit: dynamic = definedExternally, init: dynamic = definedExternally): Promise<dynamic>
}

internal external interface RawPartyContext {
    val parties: dynamic
    val assets: RawPartyAssets
}

internal external interface RawPartyLobby {
    val id: String
    val env: dynamic
    val parties: dynamic
    val assets: RawPartyAssets
}

internal external interface RawPartyFetchLobby {
    val env: dynamic
    val parties: dynamic
    val assets: RawPartyAssets
}

internal external interface RawPartyCron {
    val scheduledTime: Number
    val cron: String?
    val name: String?
    fun noRetry()
}

internal external interface RawPartySocket {
    fun send(message: dynamic)
    fun close(code: Int = definedExternally, reason: String = definedExternally)
}

internal external interface RawPartyFetchSocket : RawPartySocket {
    val request: RawWorkerRequest
}

internal external interface RawPartyRoom {
    val id: String
    val internalID: String
    val name: String
    val env: dynamic
    val storage: RawDurableObjectStorage
    val context: RawPartyContext
    fun blockConcurrencyWhile(callback: () -> Promise<Any?>): Promise<Any?>
    fun broadcast(message: dynamic, without: Array<String> = definedExternally)
    fun getConnection(id: String): RawPartyConnection?
    fun getConnections(tag: String = definedExternally): dynamic
}

@JsExport
class PartyKitMessage internal constructor(
    private val raw: dynamic,
) {
    val isText: Boolean
        get() = jsTypeOf(raw) == "string"

    fun textOrNull(): String? =
        if (isText) raw.unsafeCast<String>() else null

    fun requireText(): String = textOrNull() ?: error("PartyKit message is not text")

    @JsExport.Ignore
    fun bytesOrNull(): ByteArray? = when {
        isArrayBuffer(raw) -> arrayBufferToByteArray(raw)
        isArrayBufferView(raw) -> typedArrayToByteArray(raw)
        else -> null
    }

    @JsExport.Ignore
    fun requireBytes(): ByteArray = bytesOrNull() ?: error("PartyKit message is not binary")

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
class PartyKitConnection internal constructor(
    private val raw: RawPartyConnection,
) {
    val id: String
        get() = raw.id

    val uri: String?
        get() = raw.uri

    @JsExport.Ignore
    val stateElementOrNull: JsonElement?
        get() = raw.state?.let(::dynamicToJsonElement)

    fun stateJsonTextOrNull(): String? =
        stateElementOrNull?.toJsonText()

    @JsExport.Ignore
    inline fun <reified T> stateOrNull(): T? =
        stateElementOrNull?.let { state -> runCatching { json.decodeFromJsonElement(kSerializer<T>(), state) }.getOrNull() }

    @JsExport.Ignore
    fun clearState(): JsonElement? =
        raw.setState(null)?.let(::dynamicToJsonElement)

    fun clearStateJsonText(): String? =
        clearState()?.toJsonText()

    @JsExport.Ignore
    fun setState(state: JsonElement?): JsonElement? =
        raw.setState(state?.toDynamic())?.let(::dynamicToJsonElement)

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
class PartyKitConnectionContext internal constructor(
    private val raw: RawPartyConnectionContext,
) {
    val request: CloudflareWorkerRequest = CloudflareWorkerRequest(raw.request)
}

@JsExport
class PartyKitAssets internal constructor(
    private val raw: RawPartyAssets,
) {
    @JsExport.Ignore
    suspend fun fetch(path: String): CloudflareResponse? {
        val response = raw.fetch(path).await()
        return if (response == null) null else CloudflareResponse(response.unsafeCast<RawWorkerResponse>())
    }

    fun fetchAsync(path: String): Promise<CloudflareResponse?> =
        promiseOf { fetch(path) }
}

@JsExport
class PartyKitPartyStub internal constructor(
    private val raw: RawPartyStub,
) {
    @JsExport.Ignore
    suspend fun fetch(
        path: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): CloudflareResponse =
        CloudflareResponse(raw.fetch(path, requestInit(method, body, headers)).await())

    fun fetchAsync(
        path: String,
        method: String = "GET",
        body: String? = null,
        headers: Any? = null,
    ): Promise<CloudflareResponse> = promiseOf {
        fetch(path, method, body, anyToStringMap(headers))
    }

    @JsExport.Ignore
    suspend fun text(
        path: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String = fetch(path, method, body, headers).text()

    fun textAsync(
        path: String,
        method: String = "GET",
        body: String? = null,
        headers: Any? = null,
    ): Promise<String> = promiseOf {
        text(path, method, body, anyToStringMap(headers))
    }

    fun jsonTextAsync(
        path: String,
        method: String = "GET",
        body: String? = null,
        headers: Any? = null,
    ): Promise<String> = promiseOf {
        fetch(path, method, body, anyToStringMap(headers)).jsonElement().toJsonText()
    }

    @JsExport.Ignore
    suspend fun jsonElement(
        path: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): JsonElement = fetch(path, method, body, headers).jsonElement()

    @JsExport.Ignore
    suspend inline fun <reified T> getJson(path: String): T =
        fetch(path).decode()

    @JsExport.Ignore
    suspend fun socket(path: String = "/"): PartyKitSocket {
        val opened = raw.socket(path).await()
        return PartyKitSocket(opened.unsafeCast<RawPartySocket>())
    }

    fun socketAsync(path: String = "/"): Promise<PartyKitSocket> =
        promiseOf { socket(path) }
}

@JsExport
class PartyKitPartyNamespace internal constructor(
    private val raw: RawPartyNamespace,
) {
    fun room(id: String): PartyKitPartyStub = PartyKitPartyStub(raw.get(id))
}

@JsExport
class PartyKitParties internal constructor(
    private val raw: dynamic,
) {
    fun namespaceOrNull(name: String): PartyKitPartyNamespace? =
        raw[name]?.unsafeCast<RawPartyNamespace?>()?.let(::PartyKitPartyNamespace)

    fun namespace(name: String): PartyKitPartyNamespace =
        namespaceOrNull(name) ?: error("Missing PartyKit party namespace '$name'")

    operator fun get(name: String): PartyKitPartyNamespace = namespace(name)

    @JsExport.Ignore
    fun names(): List<String> =
        js("Object.keys(raw)").unsafeCast<Array<String>>().toList()

    fun namesArray(): Array<String> =
        js("Object.keys(raw)").unsafeCast<Array<String>>()
}

@JsExport
class PartyKitLobby internal constructor(
    private val raw: RawPartyLobby,
) {
    val id: String
        get() = raw.id

    val cloudflare: CloudflareContext
        get() = CloudflareContext(raw.env.unsafeCast<CloudflareEnv>())

    val parties: PartyKitParties = PartyKitParties(raw.parties)

    val assets: PartyKitAssets = PartyKitAssets(raw.assets)
}

@JsExport
class PartyKitFetchLobby internal constructor(
    private val raw: RawPartyFetchLobby,
) {
    val cloudflare: CloudflareContext
        get() = CloudflareContext(raw.env.unsafeCast<CloudflareEnv>())

    val parties: PartyKitParties = PartyKitParties(raw.parties)

    val assets: PartyKitAssets = PartyKitAssets(raw.assets)
}

@JsExport
open class PartyKitSocket internal constructor(
    private val raw: RawPartySocket,
) {
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
class PartyKitFetchSocket internal constructor(
    private val fetchRaw: RawPartyFetchSocket,
) : PartyKitSocket(fetchRaw) {
    val request: CloudflareWorkerRequest = CloudflareWorkerRequest(fetchRaw.request)
}

@JsExport
class PartyKitCron internal constructor(
    private val raw: RawPartyCron,
) {
    val scheduledTimeMillis: Long
        get() = raw.scheduledTime.toLong()

    val expression: String?
        get() = raw.cron

    val name: String?
        get() = raw.name

    fun noRetry() {
        raw.noRetry()
    }
}

@JsExport
class PartyKitRoom internal constructor(
    private val raw: RawPartyRoom,
) {
    val id: String
        get() = raw.id

    val internalId: String
        get() = raw.internalID

    val name: String
        get() = raw.name

    val cloudflare: CloudflareContext
        get() = CloudflareContext(raw.env.unsafeCast<CloudflareEnv>())

    val storage: DurableObjectStorage = DurableObjectStorage(raw.storage)

    val parties: PartyKitParties = PartyKitParties(raw.context.parties)

    val assets: PartyKitAssets = PartyKitAssets(raw.context.assets)

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

    fun connectionOrNull(id: String): PartyKitConnection? =
        raw.getConnection(id)?.let(::PartyKitConnection)

    fun connection(id: String): PartyKitConnection =
        connectionOrNull(id) ?: error("Missing PartyKit connection '$id'")

    @JsExport.Ignore
    fun connections(tag: String? = null): List<PartyKitConnection> {
        val iterable = if (tag == null) raw.getConnections() else raw.getConnections(tag)
        return iterableToList(iterable).map { value -> PartyKitConnection(value.unsafeCast<RawPartyConnection>()) }
    }

    fun connectionsArray(tag: String? = null): Array<PartyKitConnection> =
        connections(tag).toTypedArray()

    @JsExport.Ignore
    suspend fun <T> blockConcurrencyWhile(block: suspend () -> T): T {
        var result: Result<T>? = null
        raw.blockConcurrencyWhile {
            GlobalScope.promise<Any?> {
                result = runCatching { block() }
                null
            }
        }.await()
        return result!!.getOrThrow()
    }

    fun blockConcurrencyWhileAsync(block: () -> Promise<Any?>): Promise<Any?> =
        raw.blockConcurrencyWhile(block)
}

@JsExport
data class PartyKitServerOptions(
    val hibernate: Boolean = false,
) {
    internal fun toJsObject(): Any {
        val options = js("({})")
        options.hibernate = hibernate
        return options.unsafeCast<Any>()
    }
}

@JsExport
fun partyKitOptions(hibernate: Boolean = false): Any = PartyKitServerOptions(hibernate).toJsObject()

@JsExport
fun partyKitRequest(value: Any): CloudflareWorkerRequest =
    CloudflareWorkerRequest(value.unsafeCast<RawWorkerRequest>())

@JsExport
fun partyKitLobby(value: Any): PartyKitLobby =
    PartyKitLobby(value.unsafeCast<RawPartyLobby>())

@JsExport
fun partyKitFetchLobby(value: Any): PartyKitFetchLobby =
    PartyKitFetchLobby(value.unsafeCast<RawPartyFetchLobby>())

@JsExport
fun partyKitSocket(value: Any): PartyKitFetchSocket =
    PartyKitFetchSocket(value.unsafeCast<RawPartyFetchSocket>())

@JsExport
fun partyKitCron(value: Any): PartyKitCron =
    PartyKitCron(value.unsafeCast<RawPartyCron>())

@JsExport.Ignore
fun partyKitExecutionContext(value: Any): WorkerExecutionContext =
    value.unsafeCast<WorkerExecutionContext>()

@OptIn(DelicateCoroutinesApi::class)
@JsExport
open class PartyKitServer(room: Any) {
    protected val room: PartyKitRoom = PartyKitRoom(room.unsafeCast<RawPartyRoom>())

    protected open val serverOptions: PartyKitServerOptions? = null

    @JsName("options")
    val options: Any?
        get() = serverOptions?.toJsObject()

    protected open suspend fun handleConnectionTags(
        connection: PartyKitConnection,
        context: PartyKitConnectionContext,
    ): Array<String> = emptyArray()

    protected open suspend fun handleStart() {
    }

    protected open suspend fun handleConnect(
        connection: PartyKitConnection,
        context: PartyKitConnectionContext,
    ) {
    }

    protected open suspend fun handleMessage(
        message: PartyKitMessage,
        sender: PartyKitConnection,
    ) {
    }

    protected open suspend fun handleClose(connection: PartyKitConnection) {
    }

    protected open suspend fun handleError(
        connection: PartyKitConnection,
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
    fun onStart(): dynamic = GlobalScope.promise {
        handleStart()
    }

    @JsName("onConnect")
    fun onConnect(connectionValue: Any, contextValue: Any): dynamic = GlobalScope.promise {
        handleConnect(connection(connectionValue), context(contextValue))
    }

    @JsName("onMessage")
    fun onMessage(messageValue: Any, senderValue: Any): dynamic = GlobalScope.promise {
        handleMessage(PartyKitMessage(messageValue), connection(senderValue))
    }

    @JsName("onClose")
    fun onClose(connectionValue: Any): dynamic = GlobalScope.promise {
        handleClose(connection(connectionValue))
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

    protected fun connection(value: Any): PartyKitConnection =
        PartyKitConnection(value.unsafeCast<RawPartyConnection>())

    protected fun context(value: Any): PartyKitConnectionContext =
        PartyKitConnectionContext(value.unsafeCast<RawPartyConnectionContext>())

    protected fun lobby(value: Any): PartyKitLobby = partyKitLobby(value)

    protected fun fetchLobby(value: Any): PartyKitFetchLobby = partyKitFetchLobby(value)

    protected fun socket(value: Any): PartyKitFetchSocket = partyKitSocket(value)

    protected fun cron(value: Any): PartyKitCron = partyKitCron(value)

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
    null -> IllegalStateException("Unknown PartyKit error")
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
