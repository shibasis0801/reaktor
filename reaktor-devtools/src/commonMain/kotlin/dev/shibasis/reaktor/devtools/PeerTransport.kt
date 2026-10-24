package dev.shibasis.reaktor.devtools

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.service.contract.Call
import dev.shibasis.reaktor.service.contract.Exchange
import dev.shibasis.reaktor.service.contract.Link
import dev.shibasis.reaktor.service.contract.Served
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A duplex, message-framed link between an agent and the workbench.
 *
 * Deliberately not HTTP. A phone reached through `adb forward` has one socket and no reason to
 * carry a server; framing newline-delimited JSON over it costs nothing and keeps the agent's
 * footprint to the serializer the app already has. HTTP stays available for agents that are
 * genuinely servers — desktop and browser — through the same [DevToolsService] declarations.
 */
interface PeerChannel {
    val incoming: Flow<String>
    suspend fun send(message: String)
    suspend fun close()
}

/**
 * One message on a [PeerChannel].
 *
 * Requests and streamed facts share the link, which is why the envelope exists at all: a fact
 * arriving between a request and its reply must not be mistaken for the reply.
 */
@Serializable
sealed interface PeerEnvelope {
    val id: String

    @Serializable
    @SerialName("request")
    data class Call(
        override val id: String,
        val operation: String,
        val payload: String,
    ) : PeerEnvelope

    @Serializable
    @SerialName("reply")
    data class Reply(
        override val id: String,
        val payload: String,
        val failure: String? = null,
    ) : PeerEnvelope

    /** Carrier traffic — subscriptions, facts, commands — multiplexed onto the same link. */
    @Serializable
    @SerialName("carrier")
    data class Carrier(
        override val id: String,
        val frame: CarrierFrame,
    ) : PeerEnvelope
}

/**
 * Serves a contract over a channel.
 *
 * Calls are matched on their contract name, which [DevToolsService] pins to the route. An
 * unknown operation is answered with a failure rather than dropped, so a version mismatch reports
 * itself instead of hanging.
 */
class PeerServiceHost(
    private val served: Served<*>,
    private val channel: PeerChannel,
    private val scope: CoroutineScope,
    private val onCarrierFrame: suspend (CarrierFrame) -> Unit = {},
) {
    private val calls by lazy { served.calls.associateBy { it.name } }

    fun start(): Job = scope.launch {
        try {
            channel.incoming.collect { message ->
                val envelope = runCatching { json.decodeFromString<PeerEnvelope>(message) }.getOrNull()
                    ?: return@collect
                when (envelope) {
                    is PeerEnvelope.Call -> respond(envelope)
                    is PeerEnvelope.Carrier -> onCarrierFrame(envelope.frame)
                    is PeerEnvelope.Reply -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
        }
    }

    private suspend fun respond(call: PeerEnvelope.Call) {
        val operation = calls[call.operation]
        val reply = if (operation == null) {
            PeerEnvelope.Reply(call.id, "", "No handler for operation '${call.operation}'")
        } else {
            runCatching { invoke(operation, call.payload) }.fold(
                onSuccess = { PeerEnvelope.Reply(call.id, it) },
                onFailure = { PeerEnvelope.Reply(call.id, "", it.message ?: it.toString()) },
            )
        }
        channel.send(json.encodeToString<PeerEnvelope>(reply))
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun invoke(operation: Call<*, *>, payload: String): String {
        val typed = operation as Call<Any?, Any?>
        val response = served.call(typed, json.decodeFromString(typed.request, payload))
        return json.encodeToString(typed.response, response)
    }

    suspend fun emit(frame: CarrierFrame, id: String) =
        channel.send(json.encodeToString<PeerEnvelope>(PeerEnvelope.Carrier(id, frame)))
}

/**
 * Calls a remote contract over a channel.
 *
 * Replies are correlated by id because the link is shared with fact streams; a pending call that
 * never gets an answer fails when the channel closes rather than waiting forever.
 */
class PeerServiceClient(
    private val channel: PeerChannel,
    private val runner: CoroutineScope,
    private val onCarrierFrame: suspend (CarrierFrame) -> Unit = {},
) : Link() {
    override val transport: String get() = "peer"

    private val pending = mutableMapOf<String, CompletableDeferred<PeerEnvelope.Reply>>()
    private val lock = Mutex()
    private var counter = 0L

    fun start(): Job = runner.launch {
        try {
            channel.incoming.collect { message ->
                val envelope = runCatching { json.decodeFromString<PeerEnvelope>(message) }.getOrNull()
                    ?: return@collect
                when (envelope) {
                    is PeerEnvelope.Reply -> lock.withLock { pending.remove(envelope.id) }?.complete(envelope)
                    is PeerEnvelope.Carrier -> onCarrierFrame(envelope.frame)
                    is PeerEnvelope.Call -> Unit
                }
            }
        } finally {
            val outstanding = lock.withLock { pending.values.toList().also { pending.clear() } }
            outstanding.forEach {
                it.complete(PeerEnvelope.Reply("", "", "Channel closed before the reply arrived"))
            }
        }
    }

    override suspend fun <In, Out> transmit(operation: Call<In, Out>, exchange: Exchange<In, Out>): Out {
        val id = lock.withLock { (++counter).toString() }
        val deferred = CompletableDeferred<PeerEnvelope.Reply>()
        lock.withLock { pending[id] = deferred }
        channel.send(json.encodeToString<PeerEnvelope>(PeerEnvelope.Call(id, operation.name, json.encodeToString(operation.request, exchange.request))))
        val reply = deferred.await()
        reply.failure?.let { error(it) }
        return json.decodeFromString(operation.response, reply.payload)
    }

    suspend fun emit(frame: CarrierFrame) {
        val id = lock.withLock { (++counter).toString() }
        channel.send(json.encodeToString<PeerEnvelope>(PeerEnvelope.Carrier(id, frame)))
    }
}
