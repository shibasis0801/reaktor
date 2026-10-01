package dev.shibasis.reaktor.service

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.time.Clock

@Serializable
data class TraceContext(val traceId: String, val spanId: String, val sampled: Boolean = true) {
    val traceparent: String get() = "00-$traceId-$spanId-${if (sampled) "01" else "00"}"

    fun child(): TraceContext = copy(spanId = randomHex(8))

    companion object {
        const val Header = "traceparent"

        private val Traceparent = Regex("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")

        fun root(): TraceContext = TraceContext(randomHex(16), randomHex(8))

        fun parse(value: String?): TraceContext? {
            val match = value?.trim()?.lowercase()?.let(Traceparent::matchEntire) ?: return null
            val (trace, span, flags) = match.destructured
            if (trace.all { it == '0' } || span.all { it == '0' }) return null
            return TraceContext(trace, span, flags.toInt(16) and 1 == 1)
        }
    }
}

class ServiceCall(
    val trace: TraceContext,
    val parentSpanId: String?,
    val attributes: Map<String, Any?>,
) : AbstractCoroutineContextElement(ServiceCall) {
    companion object Key : CoroutineContext.Key<ServiceCall> {
        const val Attribute = "reaktor.service.call"
        const val IslandAttribute = "reaktor.island"
    }
}

val Request.call: CoroutineContext
    get() = attributes[ServiceCall.Attribute] as? ServiceCall ?: EmptyCoroutineContext

@Serializable
data class ServiceSpan(
    @SerialName("trace_id") val traceId: String,
    @SerialName("span_id") val spanId: String,
    @SerialName("parent_id") val parentId: String = "",
    val phase: ServiceExecutionPhase,
    val island: String,
    val contract: String,
    val operation: String,
    @SerialName("started_ms") val startedMillis: Long,
    @SerialName("duration_ms") val durationMillis: Double,
    val status: Int,
)

fun interface SpanSink {
    fun record(span: ServiceSpan)
}

class SpanBuffer(private val capacity: Int = 1024) : SpanSink {
    private val lock = SynchronizedObject()
    private val spans = ArrayList<ServiceSpan>()

    var dropped: Long = 0
        private set

    override fun record(span: ServiceSpan) = synchronized(lock) {
        if (spans.size < capacity) spans += span else dropped += 1
    }

    fun drain(): List<ServiceSpan> = synchronized(lock) { spans.toList().also { spans.clear() } }
}

object TracePropagation : ServiceInterceptor {
    override val stages: Set<InterceptorStage> = setOf(InterceptorStage.CLIENT_APPLICATION)

    override suspend fun <In : Request, Out : Response> intercept(chain: ServiceChain<In, Out>): Out {
        currentCoroutineContext()[ServiceCall]?.let { chain.request.headers[TraceContext.Header] = it.trace.traceparent }
        return chain.proceed()
    }
}

class SpanRecorder(private val island: String, private val sink: SpanSink) : ServiceInterceptor {
    override val stages: Set<InterceptorStage> = setOf(InterceptorStage.CLIENT_APPLICATION, InterceptorStage.SERVER_APPLICATION)

    override suspend fun <In : Request, Out : Response> intercept(chain: ServiceChain<In, Out>): Out {
        val call = currentCoroutineContext()[ServiceCall] ?: return chain.proceed()
        val started = Clock.System.now()
        val outcome = runCatching { chain.proceed() }
        val finished = Clock.System.now()
        if (outcome.exceptionOrNull() !is CancellationException) {
            sink.record(
                ServiceSpan(
                    traceId = call.trace.traceId,
                    spanId = call.trace.spanId,
                    parentId = call.parentSpanId.orEmpty(),
                    phase = chain.phase,
                    island = call.attributes[ServiceCall.IslandAttribute] as? String ?: island,
                    contract = chain.handler.owner?.contract?.id.orEmpty(),
                    operation = chain.context.operation,
                    startedMillis = started.toEpochMilliseconds(),
                    durationMillis = (finished - started).inWholeMicroseconds / 1000.0,
                    status = outcome.fold({ it.transportStatusCode.code }, ::failureStatus),
                ),
            )
        }
        return outcome.getOrThrow()
    }

    private fun failureStatus(failure: Throwable): Int = when (failure) {
        is HttpFailure -> failure.statusCode.code
        is ServiceStatusException -> failure.status
        is ServiceUnanswered -> 504
        else -> 500
    }
}

internal fun Map<String, String>.header(name: String): String? =
    get(name) ?: entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

private fun randomHex(bytes: Int): String =
    Random.nextBytes(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
