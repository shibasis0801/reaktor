package dev.shibasis.reaktor.service.contract

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.TraceContext
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.channels.Channel as Buffer

fun interface Publisher<M> {
    suspend fun publish(message: M)
}

class Envelope<M>(val message: M, val metadata: Metadata, val key: String?, val attempt: Int = 1) {
    fun next(): Envelope<M> = Envelope(message, metadata, key, attempt + 1)
}

class DeadLetter<M>(val envelope: Envelope<M>, val subscriber: String, val failure: Throwable)

sealed interface Settlement {
    data object Ack : Settlement
    data class Retry(val after: Duration) : Settlement
}

class Subscription internal constructor(val name: String, private val jobs: List<Job>, private val onCancel: () -> Unit) {
    fun cancel() {
        jobs.forEach { it.cancel() }
        onCancel()
    }
}

fun <M> Channel<M>.seal(message: M, metadata: Metadata): JsonElement = buildJsonObject {
    put(EnvelopeField, EnvelopeVersion)
    put("metadata", JsonObject(metadata.toMap().mapValues { JsonPrimitive(it.value) }))
    put("message", json.encodeToJsonElement(this@seal.message, message))
}

fun <M> Channel<M>.unseal(body: JsonElement, attempt: Int = 1): Envelope<M> {
    val sealed = (body as? JsonObject)?.takeIf { (it[EnvelopeField] as? JsonPrimitive)?.contentOrNull == EnvelopeVersion }
    val message = json.decodeFromJsonElement(this.message, sealed?.get("message") ?: body)
    val metadata = Metadata(sealed?.get("metadata")?.jsonObject?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty())
    return Envelope(message, metadata, keyOf(message), attempt)
}

private const val EnvelopeField = "envelope"
private const val EnvelopeVersion = "reaktor/1"

class Broker<M>(val channel: Channel<M>, private val scope: CoroutineScope) : Publisher<M> {
    val interceptors: Interceptors = Interceptors()

    var source: InterceptorSource = InterceptorSource.None
    var identity: () -> Map<String, Any?> = { emptyMap() }
    var deadLetter: suspend (DeadLetter<M>) -> Unit = { letter -> remember(letter) }
    var backoff: (attempt: Int) -> Duration = { attempt -> (50 * (1 shl (attempt - 1).coerceIn(0, 10))).milliseconds }

    private val keyed = channel.ordering is Ordering.PerKey<*>
    private val shared = buffer()
    private val lanes = MutableStateFlow(emptyList<Lane<M>>())
    private val dead = atomic(emptyList<DeadLetter<M>>())
    private val router = atomic<Job?>(null)
    private val turn = atomic(0)

    val deadLetters: List<DeadLetter<M>> get() = dead.value

    val subscribers: List<String> get() = lanes.value.map { it.name }

    val active: Flow<Boolean> get() = lanes.map { it.isNotEmpty() }.distinctUntilChanged()

    val link: Link = BrokerLink(this)

    fun chain(): List<Installed> = Interceptors.ordered(Interceptors.process.at(0), source.interceptors(), interceptors.at(0))

    override suspend fun publish(message: M) = link.publish(channel, message)

    suspend fun accept(message: M, metadata: Metadata) {
        val envelope = Envelope(message, metadata, channel.keyOf(message))
        if (channel.distribution == Distribution.Competing) offer(shared, envelope)
        else lanes.value.forEach { offer(it.intake, envelope) }
    }

    suspend fun receive(envelope: Envelope<M>, attributes: Map<String, Any?> = emptyMap()): Settlement {
        val current = lanes.value
        if (current.isEmpty()) return Settlement.Retry(backoff(envelope.attempt))
        val targets = if (channel.distribution == Distribution.FanOut) current
        else listOf(current[envelope.key?.let { partition(it, current.size) } ?: turn.getAndIncrement().mod(current.size)])
        return settle(targets, envelope, attributes)
    }

    fun subscribe(name: String, concurrency: Int = 1, handler: suspend (M) -> Unit): Subscription {
        require(concurrency >= 1) { "A subscription needs at least one worker" }
        val workers = if (channel.ordering == Ordering.Total) 1 else concurrency
        val competing = channel.distribution == Distribution.Competing
        val intake = if (competing && !keyed) shared else buffer()
        val lane = Lane(name, intake, handler)
        lanes.update { it + lane }
        if (competing && keyed) route()
        val jobs = if (keyed && workers > 1) {
            val partitions = List(workers) { buffer() }
            listOf(scope.launch { for (envelope in intake) partitions[partition(envelope.key, workers)].send(envelope) }) +
                partitions.map { partition -> scope.launch { for (envelope in partition) deliver(lane, envelope) } }
        } else List(workers) { scope.launch { for (envelope in intake) deliver(lane, envelope) } }
        return Subscription(name, jobs) { lanes.update { current -> current - lane } }
    }

    private fun route() {
        if (router.value != null) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            for (envelope in shared) {
                val current = lanes.first { it.isNotEmpty() }
                current[partition(envelope.key, current.size)].intake.send(envelope)
            }
        }
        if (router.compareAndSet(null, job)) job.start() else job.cancel()
    }

    private suspend fun deliver(lane: Lane<M>, envelope: Envelope<M>) {
        var current = envelope
        while (true) {
            when (val settled = settle(listOf(lane), current, emptyMap())) {
                Settlement.Ack -> return
                is Settlement.Retry -> {
                    delay(settled.after)
                    current = current.next()
                }
            }
        }
    }

    private suspend fun settle(targets: List<Lane<M>>, envelope: Envelope<M>, attributes: Map<String, Any?>): Settlement {
        var settlement: Settlement = Settlement.Ack
        for (lane in targets) {
            val failure = try {
                handle(lane, envelope, attributes)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failure
            } ?: continue
            val final = failure is Rejected || channel.delivery == Delivery.AtMostOnce || envelope.attempt >= channel.maxAttempts
            if (final) runCatching { deadLetter(DeadLetter(envelope, lane.name, failure)) }.onFailure { remember(DeadLetter(envelope, lane.name, it)) }
            else settlement = Settlement.Retry(maxOf(backoff(envelope.attempt), (failure as? Unavailable)?.retryAfter ?: Duration.ZERO))
        }
        return settlement
    }

    private suspend fun handle(lane: Lane<M>, envelope: Envelope<M>, inbound: Map<String, Any?>) {
        val parent = TraceContext.parse(envelope.metadata[Metadata.Traceparent])
        val session = ServiceCall.sessionFromBaggage(envelope.metadata[Metadata.Baggage])
        val attributes = identity() + inbound + (session?.let { mapOf(ServiceCall.SessionAttribute to it) } ?: emptyMap())
        val call = ServiceCall(parent?.child() ?: TraceContext.root(), parent?.spanId, attributes)
        val exchange = Exchange<M, Unit>(
            family = Family.Deliver,
            side = Side.Server,
            contract = channel.id,
            name = lane.name,
            operation = null,
            channel = channel,
            request = envelope.message,
            metadata = envelope.metadata.copy(),
            attributes = attributes.toMutableMap(),
            call = call,
            deadline = null,
        )
        exchange.attempt = envelope.attempt
        withContext(call + ExchangeScope(exchange)) {
            chain().proceed(exchange) { current -> lane.handler(current.request) }
        }
    }

    private suspend fun offer(buffer: Buffer<Envelope<M>>, envelope: Envelope<M>) {
        if (channel.overflow != Overflow.Reject) buffer.send(envelope)
        else if (!buffer.trySend(envelope).isSuccess) throw Unavailable("${channel.id} is full", 503)
    }

    private fun buffer(): Buffer<Envelope<M>> = Buffer(
        capacity = channel.capacity,
        onBufferOverflow = when (channel.overflow) {
            Overflow.DropOldest -> BufferOverflow.DROP_OLDEST
            Overflow.DropNewest -> BufferOverflow.DROP_LATEST
            Overflow.Suspend, Overflow.Reject -> BufferOverflow.SUSPEND
        },
    )

    private fun partition(key: String?, size: Int): Int = (key?.hashCode() ?: 0).mod(size)

    private fun remember(letter: DeadLetter<M>) {
        Logger.w(letter.failure) { "${channel.id} gave up on a message for ${letter.subscriber} after ${letter.envelope.attempt} attempts" }
        dead.update { (it + letter).takeLast(DeadLetterMemory) }
    }

    private class Lane<M>(val name: String, val intake: Buffer<Envelope<M>>, val handler: suspend (M) -> Unit)

    private companion object {
        const val DeadLetterMemory = 100
    }
}

private class BrokerLink(private val broker: Broker<*>) : Link() {
    override val transport: String get() = "local"

    init {
        scope = InterceptorSource { broker.source.interceptors() + broker.interceptors.at(0) }
        identity = { broker.identity() }
    }

    override suspend fun <In, Out> transmit(operation: Call<In, Out>, exchange: Exchange<In, Out>): Out =
        throw UnsupportedOperationException("A channel link carries messages, not calls")

    @Suppress("UNCHECKED_CAST")
    override suspend fun <M> deliver(channel: Channel<M>, exchange: Exchange<M, Unit>) {
        (broker as Broker<M>).accept(exchange.request, exchange.metadata.copy())
    }
}
