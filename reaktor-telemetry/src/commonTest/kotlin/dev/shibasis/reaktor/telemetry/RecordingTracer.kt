package dev.shibasis.reaktor.telemetry

import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.attributes.MutableAttributeContainer
import io.opentelemetry.kotlin.context.Context
import io.opentelemetry.kotlin.tracing.Tracer
import io.opentelemetry.kotlin.tracing.data.EventData
import io.opentelemetry.kotlin.tracing.data.LinkData
import io.opentelemetry.kotlin.tracing.data.StatusData
import io.opentelemetry.kotlin.tracing.model.Span
import io.opentelemetry.kotlin.tracing.model.SpanContext
import io.opentelemetry.kotlin.tracing.model.SpanKind
import io.opentelemetry.kotlin.tracing.model.SpanRelationships
import io.opentelemetry.kotlin.tracing.model.TraceFlags
import io.opentelemetry.kotlin.tracing.model.TraceState

/** Captures what the interceptor emitted, so assertions are about spans rather than about a mock. */
@OptIn(ExperimentalApi::class)
class RecordingTracer : Tracer {
    val spans = mutableListOf<RecordedSpan>()

    override fun createSpan(
        name: String,
        parentContext: Context?,
        spanKind: SpanKind,
        startTimestamp: Long?,
        action: (SpanRelationships.() -> Unit)?,
    ): Span = RecordedSpan(name, spanKind).also { span -> action?.invoke(span); spans += span }

    override fun startSpan(
        name: String,
        parentContext: Context?,
        spanKind: SpanKind,
        startTimestamp: Long?,
        action: (SpanRelationships.() -> Unit)?,
    ): Span = createSpan(name, parentContext, spanKind, startTimestamp, action)
}

@OptIn(ExperimentalApi::class)
class RecordedSpan(
    private var spanName: String,
    private val kind: SpanKind,
) : Span {
    private val attrs = mutableMapOf<String, Any>()
    private val eventList = mutableListOf<EventData>()
    var ended = false
        private set
    var endCount = 0
        private set

    override val attributes: Map<String, Any> get() = attrs
    override var name: String
        get() = spanName
        set(value) { spanName = value }
    override var status: StatusData = StatusData.Unset
    override val spanKind: SpanKind get() = kind
    override val startTimestamp: Long get() = 0L
    override val events: List<EventData> get() = eventList
    override val links: List<LinkData> get() = emptyList()
    override val parent: SpanContext get() = StubSpanContext
    override val spanContext: SpanContext get() = StubSpanContext

    override fun end() { ended = true; endCount++ }
    override fun end(timestamp: Long) = end()
    override fun isRecording(): Boolean = !ended

    override fun setStringAttribute(key: String, value: String) { attrs[key] = value }
    override fun setBooleanAttribute(key: String, value: Boolean) { attrs[key] = value }
    override fun setLongAttribute(key: String, value: Long) { attrs[key] = value }
    override fun setDoubleAttribute(key: String, value: Double) { attrs[key] = value }
    override fun setStringListAttribute(key: String, value: List<String>) { attrs[key] = value }
    override fun setBooleanListAttribute(key: String, value: List<Boolean>) { attrs[key] = value }
    override fun setLongListAttribute(key: String, value: List<Long>) { attrs[key] = value }
    override fun setDoubleListAttribute(key: String, value: List<Double>) { attrs[key] = value }

    override fun addEvent(
        name: String,
        timestamp: Long?,
        attributes: (MutableAttributeContainer.() -> Unit)?,
    ) = Unit

    override fun addLink(
        spanContext: SpanContext,
        attributes: (MutableAttributeContainer.() -> Unit)?,
    ) = Unit
}

@OptIn(ExperimentalApi::class)
object StubSpanContext : SpanContext {
    override val traceId: String get() = "0".repeat(32)
    override val traceIdBytes: ByteArray get() = ByteArray(16)
    override val spanId: String get() = "0".repeat(16)
    override val spanIdBytes: ByteArray get() = ByteArray(8)
    override val traceFlags: TraceFlags get() = error("not needed by these tests")
    override val traceState: TraceState get() = error("not needed by these tests")
    override val isValid: Boolean get() = false
    override val isRemote: Boolean get() = false
}
