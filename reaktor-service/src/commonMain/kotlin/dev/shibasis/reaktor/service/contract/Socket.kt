package dev.shibasis.reaktor.service.contract

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.io.network.websocket.ConnectionState
import dev.shibasis.reaktor.io.network.websocket.WebSocket
import io.ktor.websocket.readText
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.getAndUpdate
import kotlinx.atomicfu.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel as Buffer

@Serializable
sealed class SocketFrame {
    abstract val id: Long

    @Serializable
    @SerialName("ask")
    data class Ask(override val id: Long, val operation: String, val body: JsonElement = JsonNull, val metadata: Map<String, String> = emptyMap()) : SocketFrame()

    @Serializable
    @SerialName("answer")
    data class Answer(override val id: Long, val status: Int, val body: JsonElement = JsonNull, val retryAfter: Long? = null) : SocketFrame()

    @Serializable
    @SerialName("open")
    data class Open(override val id: Long, val operation: String, val body: JsonElement = JsonNull, val metadata: Map<String, String> = emptyMap()) : SocketFrame()

    @Serializable
    @SerialName("item")
    data class Item(override val id: Long, val body: JsonElement) : SocketFrame()

    @Serializable
    @SerialName("end")
    data class End(override val id: Long, val status: Int = 200, val body: JsonElement = JsonNull) : SocketFrame()

    @Serializable
    @SerialName("cancel")
    data class Cancel(override val id: Long) : SocketFrame()

    @Serializable
    @SerialName("subscribe")
    data class Subscribe(override val id: Long, val channel: String) : SocketFrame()

    @Serializable
    @SerialName("publish")
    data class Publish(override val id: Long, val channel: String, val message: JsonElement, val metadata: Map<String, String> = emptyMap()) : SocketFrame()

    @Serializable
    @SerialName("deliver")
    data class Deliver(override val id: Long, val message: JsonElement, val metadata: Map<String, String> = emptyMap()) : SocketFrame()

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        fun decode(text: String): SocketFrame = json.decodeFromString(serializer(), text)
    }
}

interface SocketTransport {
    val incoming: Flow<String>
    val connection: StateFlow<Int>
    suspend fun send(text: String)
}

fun WebSocket.transport(scope: CoroutineScope): SocketTransport = object : SocketTransport {
    override val incoming: Flow<String> = receiver.textFrames.map { it.readText() }
    override val connection: StateFlow<Int> = state.filterIsInstance<ConnectionState.Open>().runningFold(0) { opened, _ -> opened + 1 }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    override suspend fun send(text: String) {
        sender.send(text).getOrElse { throw Unavailable("The socket is not open", 503, cause = it) }
    }
}

interface Feed {
    fun <M> feed(channel: Channel<M>): Flow<Envelope<M>>
}

class SocketLink(
    private val socket: SocketTransport,
    private val workers: CoroutineScope,
    deadline: Duration = DefaultDeadline,
) : Link(deadline), Feed {
    override val transport: String get() = "socket"

    private val ids = atomic(0L)
    private val waiting = atomic(emptyMap<Long, Pending>())
    private val reader = workers.launch(start = CoroutineStart.LAZY) {
        launch { socket.incoming.collect { text -> runCatching { SocketFrame.decode(text) }.getOrNull()?.let { waiting.value[it.id]?.inbox?.trySend(it) } } }
        socket.connection.drop(1).collect { reopened(it) }
    }

    override suspend fun <In, Out> transmit(operation: Call<In, Out>, exchange: Exchange<In, Out>): Out {
        val answer = session({ id -> SocketFrame.Ask(id, operation.id, json.encodeToJsonElement(operation.request, exchange.request), exchange.metadata.toMap()) }) { it.receive() }
        if (answer !is SocketFrame.Answer || answer.status !in 200..299) throw failed(answer, operation.id)
        return try {
            json.decodeFromJsonElement(operation.response, answer.body)
        } catch (unreadable: IllegalArgumentException) {
            throw ContractDrift("${operation.id} answered in a shape this client cannot read", answer.status, unreadable)
        }
    }

    override fun <In, Out> open(operation: Stream<In, Out>, exchange: Exchange<In, Flow<Out>>): Flow<Out> = flow {
        session({ id -> SocketFrame.Open(id, operation.id, json.encodeToJsonElement(operation.request, exchange.request), exchange.metadata.toMap()) }) { inbox ->
            for (frame in inbox) when (frame) {
                is SocketFrame.Item -> emit(json.decodeFromJsonElement(operation.response, frame.body))
                is SocketFrame.End -> if (frame.status in 200..299) break else throw failed(frame, operation.id)
                else -> Unit
            }
        }
    }

    override suspend fun <M> deliver(channel: Channel<M>, exchange: Exchange<M, Unit>) {
        val answer = session({ id -> SocketFrame.Publish(id, channel.id, json.encodeToJsonElement(channel.message, exchange.request), exchange.metadata.toMap()) }) { it.receive() }
        if (answer !is SocketFrame.Answer || answer.status !in 200..299) throw failed(answer, channel.id)
    }

    override fun <M> feed(channel: Channel<M>): Flow<Envelope<M>> = flow {
        session({ id -> SocketFrame.Subscribe(id, channel.id) }, resubscribe = true) { inbox ->
            for (frame in inbox) when (frame) {
                is SocketFrame.Deliver -> {
                    val message = json.decodeFromJsonElement(channel.message, frame.message)
                    emit(Envelope(message, Metadata(frame.metadata), channel.keyOf(message)))
                }
                is SocketFrame.End -> if (frame.status in 200..299) break else throw failed(frame, channel.id)
                else -> Unit
            }
        }
    }

    fun <M> subscribe(channel: Channel<M>): Flow<M> = feed(channel).map { it.message }

    private suspend fun <T> session(frame: (Long) -> SocketFrame, resubscribe: Boolean = false, block: suspend (ReceiveChannel<SocketFrame>) -> T): T {
        reader.start()
        val id = ids.incrementAndGet()
        val opening = frame(id)
        val inbox = Buffer<SocketFrame>(Buffer.UNLIMITED)
        waiting.update { it + (id to Pending(inbox, opening.takeIf { resubscribe }, socket.connection.value)) }
        try {
            socket.send(opening.encode())
            return block(inbox)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { runCatching { socket.send(SocketFrame.Cancel(id).encode()) } }
            throw cancelled
        } finally {
            waiting.update { it - id }
        }
    }

    private fun reopened(connection: Int) {
        waiting.value.forEach { (id, pending) ->
            if (pending.connection >= connection) return@forEach
            val again = pending.resubscribe
            if (again == null) pending.inbox.close(OutcomeUnknown("The socket reconnected before exchange $id finished"))
            else workers.launch { runCatching { socket.send(again.encode()) } }
        }
    }

    private fun failed(frame: SocketFrame, subject: String): CallFailure = when (frame) {
        is SocketFrame.Answer -> failureOf(frame.status, frame.body, subject, frame.retryAfter?.seconds)
        is SocketFrame.End -> failureOf(frame.status, frame.body, subject)
        else -> ContractDrift("$subject answered with an unexpected ${frame::class.simpleName}", 502)
    }

    private class Pending(val inbox: SendChannel<SocketFrame>, val resubscribe: SocketFrame?, val connection: Int)
}

class SocketSession(
    val name: String,
    private val served: List<Served<*>>,
    private val channels: List<Broker<*>>,
    private val scope: CoroutineScope,
    private val attributes: Map<String, Any?> = emptyMap(),
    private val send: suspend (String) -> Unit,
) {
    private val running = atomic(emptyMap<Long, () -> Unit>())

    val open: Int get() = running.value.size

    suspend fun receive(text: String) {
        val frame = try {
            SocketFrame.decode(text)
        } catch (unreadable: IllegalArgumentException) {
            return reply(SocketFrame.End(0, 400, Reply.of(400, "Unreadable frame: ${unreadable.message ?: "malformed"}").body))
        }
        when (frame) {
            is SocketFrame.Ask -> track(frame.id) { reply(answer(frame)) }
            is SocketFrame.Open -> track(frame.id) { stream(frame) }
            is SocketFrame.Publish -> track(frame.id) { reply(publish(frame)) }
            is SocketFrame.Subscribe -> subscribe(frame)
            is SocketFrame.Cancel -> cancel(frame.id)
            is SocketFrame.Answer, is SocketFrame.Item, is SocketFrame.End, is SocketFrame.Deliver -> Unit
        }
    }

    fun close() {
        running.getAndSet(emptyMap()).values.forEach { it() }
    }

    private suspend fun answer(frame: SocketFrame.Ask): SocketFrame.Answer {
        val (owner, operation) = find(frame.operation) ?: return refusal(frame.id, 404, "Nothing here serves ${frame.operation}")
        val outbound = owner.handle(operation, Inbound(headers = frame.metadata, body = frame.body.toString(), attributes = attributes))
        return SocketFrame.Answer(frame.id, outbound.status, json.parseToJsonElement(outbound.body), outbound.headers[Metadata.RetryAfter]?.toLongOrNull())
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun stream(frame: SocketFrame.Open) {
        val (owner, operation) = find(frame.operation) ?: return reply(SocketFrame.End(frame.id, 404, Reply.of(404, "Nothing here streams ${frame.operation}").body))
        val stream = operation as? Stream<Any?, Any?> ?: return reply(SocketFrame.End(frame.id, 405, Reply.of(405, "${operation.id} is not a stream").body))
        try {
            val request = json.decodeFromJsonElement(stream.request, frame.body)
            owner.stream(stream, request, Metadata(frame.metadata), attributes).collect {
                reply(SocketFrame.Item(frame.id, json.encodeToJsonElement(stream.response, it)))
            }
            reply(SocketFrame.End(frame.id))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val outcome = (if (failure is IllegalArgumentException) Rejected(400, failure.message ?: "Unreadable request") else failure).reply(stream.id)
            reply(SocketFrame.End(frame.id, outcome.status, outcome.body))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun publish(frame: SocketFrame.Publish): SocketFrame.Answer {
        val broker = channels.firstOrNull { it.channel.id == frame.channel } as Broker<Any?>?
            ?: return refusal(frame.id, 404, "Nothing here accepts ${frame.channel}")
        return try {
            broker.accept(json.decodeFromJsonElement(broker.channel.message, frame.message), Metadata(frame.metadata))
            SocketFrame.Answer(frame.id, 202)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val outcome = (if (failure is IllegalArgumentException) Rejected(400, failure.message ?: "Unreadable message") else failure).reply(frame.channel)
            SocketFrame.Answer(frame.id, outcome.status, outcome.body, outcome.retryAfter?.inWholeSeconds)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun subscribe(frame: SocketFrame.Subscribe) {
        cancel(frame.id)
        val broker = channels.firstOrNull { it.channel.id == frame.channel } as Broker<Any?>?
            ?: return reply(SocketFrame.End(frame.id, 404, Reply.of(404, "Nothing here publishes ${frame.channel}").body))
        val subscription = broker.subscribe("socket:$name:${frame.id}") { message ->
            reply(SocketFrame.Deliver(frame.id, json.encodeToJsonElement(broker.channel.message, message), exchangeOrNull()?.metadata?.toMap().orEmpty()))
        }
        running.update { it + (frame.id to { subscription.cancel() }) }
    }

    private fun cancel(id: Long) {
        running.getAndUpdate { it - id }[id]?.invoke()
    }

    private fun track(id: Long, block: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                running.update { it - id }
            }
        }
        running.update { it + (id to { job.cancel() }) }
        job.start()
    }

    private fun find(operation: String): Pair<Served<*>, Operation<*, *>>? =
        served.firstNotNullOfOrNull { owner -> owner.service.operations.firstOrNull { it.id == operation }?.let { owner to it } }

    private fun refusal(id: Long, status: Int, message: String) = SocketFrame.Answer(id, status, Reply.of(status, message).body)

    private suspend fun reply(frame: SocketFrame) = send(frame.encode())
}
