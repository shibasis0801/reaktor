@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.shibasis.reaktor.service.contract

import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.TraceContext
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

abstract class Link(val deadline: Duration = DefaultDeadline) {
    val interceptors: Interceptors = Interceptors()

    var scope: InterceptorSource = InterceptorSource.None
    var identity: () -> Map<String, Any?> = { emptyMap() }

    abstract val transport: String

    open val served: Served<*>? get() = null

    fun intercept(vararg interceptor: Interceptor): Link = apply { interceptors.add(*interceptor) }

    fun chain(): List<Installed> = Interceptors.ordered(Interceptors.process.at(0), scope.interceptors(), interceptors.at(0))

    @Suppress("UNCHECKED_CAST")
    open suspend fun <In, Out> call(operation: Call<In, Out>, request: In): Out = try {
        exchange(operation, request)
    } catch (failure: CallFailure) {
        failure.answer as Out? ?: throw failure
    }

    private suspend fun <In, Out> exchange(operation: Call<In, Out>, request: In): Out {
        val exchange = outgoing<In, Out>(Family.Call, operation, null, request, operation.deadline)
        return withContext(exchange.call + ExchangeScope(exchange)) {
            chain().proceed(exchange) { current ->
                val remaining = current.remaining
                if (remaining == Duration.ZERO) throw Unavailable("The deadline for ${current.subject} passed before it was sent", 504)
                remaining?.let { current.metadata[Metadata.Deadline] = it.inWholeMilliseconds.toString() }
                if (remaining == null) transmit(operation, current)
                else (withTimeoutOrNull(remaining) { Answer(transmit(operation, current)) }
                    ?: throw OutcomeUnknown("No answer for ${current.subject} within the deadline", cause = Unanswered(remaining))).value
            }
        }
    }

    open fun <In, Out> stream(operation: Stream<In, Out>, request: In): Flow<Out> = flow {
        val exchange = outgoing<In, Flow<Out>>(Family.Stream, operation, null, request, null)
        val opened = withContext(exchange.call + ExchangeScope(exchange)) {
            chain().proceed(exchange) { current -> open(operation, current) }
        }
        emitAll(opened)
    }

    suspend fun <M> publish(channel: Channel<M>, message: M) {
        val exchange = outgoing<M, Unit>(Family.Publish, null, channel, message, null)
        withContext(exchange.call + ExchangeScope(exchange)) {
            chain().proceed(exchange) { current ->
                current.remaining?.let { current.metadata[Metadata.Deadline] = it.inWholeMilliseconds.toString() }
                deliver(channel, current)
            }
        }
    }

    protected abstract suspend fun <In, Out> transmit(operation: Call<In, Out>, exchange: Exchange<In, Out>): Out

    protected open fun <In, Out> open(operation: Stream<In, Out>, exchange: Exchange<In, Flow<Out>>): Flow<Out> =
        throw UnsupportedOperationException("$transport links cannot open streams such as ${operation.id}")

    protected open suspend fun <M> deliver(channel: Channel<M>, exchange: Exchange<M, Unit>): Unit =
        throw UnsupportedOperationException("$transport links cannot publish to ${channel.id}")

    private suspend fun <In, R> outgoing(
        family: Family,
        operation: Operation<In, *>?,
        channel: Channel<In>?,
        request: In,
        own: Duration?,
    ): Exchange<In, R> {
        val context = currentCoroutineContext()
        val caller = context[ServiceCall]
        val inherited = context[ExchangeScope]?.exchange?.deadline
        val attributes = CallAttributes.ambient() + caller?.attributes.orEmpty() + context[CallAttributes]?.values.orEmpty() + identity()
        val call = ServiceCall(caller?.trace?.child() ?: TraceContext.root(), caller?.trace?.spanId, attributes)
        val deadline = if (family == Family.Stream) null else listOfNotNull(inherited, Clock.System.now() + (own ?: deadline)).min()
        val service = operation?.service
        val metadata = Metadata().apply {
            this[Metadata.Traceparent] = call.trace.traceparent
            (attributes[ServiceCall.SessionAttribute] as? String)?.takeIf { SessionValue.matches(it) }?.let { this[Metadata.Baggage] = "reaktor-session=$it" }
            this[Metadata.Environment] = (request as? Request)?.environment?.name ?: Request.defaultEnvironment.name
            service?.let { this[Metadata.Contract] = "${it.id}@${it.fingerprint}" }
            if (operation?.idempotency == Idempotency.Keyed) this[Metadata.IdempotencyKey] = Uuid.random().toString()
            (request as? Request)?.headers?.forEach { (name, value) -> this[name] = value }
        }
        return Exchange(
            family = family,
            side = Side.Client,
            contract = service?.id ?: channel?.id.orEmpty(),
            name = operation?.name ?: "publish",
            operation = operation,
            channel = channel,
            request = request,
            metadata = metadata,
            attributes = attributes.toMutableMap(),
            call = call,
            deadline = deadline,
        )
    }

    companion object {
        val DefaultDeadline: Duration = 25.seconds
        internal val SessionValue = Regex("[A-Za-z0-9_-]{1,96}")
    }
}

private class Answer<T>(val value: T)

class Unanswered(val within: Duration) : RuntimeException("No answer within $within")

class CallAttributes(val values: Map<String, Any?>) : AbstractCoroutineContextElement(CallAttributes) {
    companion object Key : CoroutineContext.Key<CallAttributes> {
        private val sources = atomic(emptyList<() -> Map<String, Any?>>())

        fun install(source: () -> Map<String, Any?>): () -> Unit {
            sources.update { it + source }
            return { sources.update { current -> current.filterNot { it === source } } }
        }

        internal fun ambient(): Map<String, Any?> = sources.value.fold(emptyMap()) { all, source -> all + source() }
    }
}
