package dev.shibasis.reaktor.service

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random

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
        const val SessionAttribute = "reaktor.application_session"
        const val EnvironmentAttribute = "reaktor.environment"
        const val BuildAttribute = "reaktor.build"
        const val NodeAttribute = "reaktor.node"
        const val GraphAttribute = "reaktor.graph_digest"
        const val PortAttribute = "reaktor.port.id"
        const val EdgeAttribute = "reaktor.edge.id"
        const val ActivationAttribute = "reaktor.activation.id"
        const val AccessAttribute = "reaktor.access"

        fun sessionFromBaggage(value: String?): String? {
            if (value == null || value.length > 512) return null
            val matches = value.split(',').map(String::trim).filter { it.substringBefore('=').trim() == "reaktor-session" }
            if (matches.size != 1) return null
            return matches.single().substringAfter('=', "").takeIf { Regex("[A-Za-z0-9_-]{1,96}").matches(it) }
        }
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
    @SerialName("application_session") val applicationSession: String = "",
    val environment: String = "",
    val build: String = "",
    val node: String = "",
    @SerialName("graph_digest") val graphDigest: String = "",
    val port: String = "",
    val edge: String = "",
    val activation: String = "",
    val application: String = "",
    val installation: String = "",
    val platform: String = "",
    val access: String = "",
)

fun interface SpanSink {
    fun record(span: ServiceSpan)
}

class SpanBuffer(private val capacity: Int = 1024) : SpanSink {
    private val lock = SynchronizedObject()
    private val spans = ArrayList<ServiceSpan>()
    private var droppedCount: Long = 0

    init { require(capacity > 0) }

    val dropped: Long get() = synchronized(lock) { droppedCount }
    val pending: Int get() = synchronized(lock) { spans.size }

    override fun record(span: ServiceSpan) = synchronized(lock) {
        if (spans.size < capacity) spans += span else droppedCount += 1
    }

    fun snapshot(): List<ServiceSpan> = synchronized(lock) { spans.toList() }

    fun acknowledge(delivered: List<ServiceSpan>) = synchronized(lock) {
        spans.removeAll(delivered.toSet())
        Unit
    }

    fun drain(): List<ServiceSpan> = synchronized(lock) { spans.toList().also { spans.clear() } }
}

private fun randomHex(bytes: Int): String =
    Random.nextBytes(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
