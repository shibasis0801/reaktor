package dev.shibasis.reaktor.telemetry.port

import dev.shibasis.reaktor.portgraph.attach.AttachmentKey
import dev.shibasis.reaktor.portgraph.attach.attachment
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.Port
import dev.shibasis.reaktor.portgraph.port.PortInterceptor
import dev.shibasis.reaktor.portgraph.port.PortInvocation
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.Unique
import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.tracing.Tracer
import io.opentelemetry.kotlin.tracing.data.StatusData
import io.opentelemetry.kotlin.tracing.model.SpanKind
import kotlin.time.TimeSource

/**
 * D4 — the `reaktor.*` semantic convention.
 *
 * Reaktor's contribution to OpenTelemetry is this attribute vocabulary. Everything else —
 * spans, metrics, context, propagation — is OTel's.
 *
 * Only the stable architectural identities belong on metrics. `activation.id` is per-process
 * and is a span attribute only; see the cardinality rules in the telemetry design.
 */
object ReaktorAttributes {
    const val GraphId = "reaktor.graph.id"
    const val NodeId = "reaktor.node.id"
    const val PortId = "reaktor.port.id"
    const val PortShape = "reaktor.port.shape"
    const val PortDirection = "reaktor.port.direction"
    const val EdgeId = "reaktor.edge.id"
    const val ContractId = "reaktor.contract.id"

    /**
     * The node's runtime identity — its `Uuid`, which is regenerated every process start.
     *
     * Span-only, and deliberately not [NodeId]: a per-run identifier under a name that promises
     * stability breaks correlation across restarts and is unbounded as a metric dimension. This is
     * the "runtime activation" half of a node's identity; [NodeId] is the architectural half.
     */
    const val NodeInstance = "reaktor.node.instance"

    /** Span-only. Never a metric dimension — it is unbounded across process restarts. */
    const val ActivationId = "reaktor.activation.id"

    /**
     * Elapsed time measured by the interceptor from a monotonic source.
     *
     * The span's own start and end come from the SDK clock, which reports nanosecond *units* at
     * wall-clock *resolution* — on a local port call that quantises to zero. This attribute is
     * the duration the call actually took.
     */
    const val DurationNanos = "reaktor.duration_ns"
}

/**
 * What kind of operation crosses this port. Attached rather than typed, so a plain
 * `ConsumerPort<Anything>` can declare its shape without the kernel gaining five port classes.
 */
enum class PortSemantics {
    /** A reference handoff. Never traced: the call happens on the returned object. */
    Reference,
    Request,
    Mailbox,
    Stream,
    State,
}

enum class TracePolicy {
    /** Take the setting from the owning node, then the graph. */
    Inherit,
    Off,
    /** Aggregate only — the interceptor runs but emits no span. */
    Metrics,
    Spans,
}

/** D5 — policy, never collected data. */
data class TelemetryFacet(
    val tracePolicy: TracePolicy = TracePolicy.Inherit,
    val semantics: PortSemantics = PortSemantics.Reference,
    /** Overrides the derived low-cardinality span name. */
    val operation: String? = null,
)

val TelemetryPolicy = AttachmentKey<TelemetryFacet>("reaktor.telemetry.policy")

/** Resolves the effective policy: port, then owning node, then the supplied default. */
fun Port<*>.resolvedTelemetryFacet(fallback: TelemetryFacet = TelemetryFacet()): TelemetryFacet {
    val own = attachment(TelemetryPolicy)
    val owner = (owner as? dev.shibasis.reaktor.portgraph.attach.Attachable)?.attachment(TelemetryPolicy)
    val merged = own ?: owner ?: fallback
    if (merged.tracePolicy != TracePolicy.Inherit) return merged
    val inherited = owner?.tracePolicy?.takeIf { it != TracePolicy.Inherit }
        ?: fallback.tracePolicy.takeIf { it != TracePolicy.Inherit }
        ?: TracePolicy.Spans
    return merged.copy(tracePolicy = inherited)
}

/**
 * The low-cardinality operation name. Derived from the port's declared key and contract, never
 * from a runtime value, so it is safe as a span name and as a metric dimension.
 */
fun Port<*>.telemetryOperationName(facet: TelemetryFacet = resolvedTelemetryFacet()): String =
    facet.operation ?: "${type.type}.${telemetryPortId()}"

/**
 * D7 — one completed span per port call.
 *
 * Installed through K1, so it sees every call that crosses the port and nothing that does not.
 * A `Reference`-shaped port is skipped: its call happens on the object the port handed out, and
 * tracing the handoff would say a call occurred when none did.
 */
@OptIn(ExperimentalApi::class)
class PortTelemetryInterceptor(
    private val tracer: Tracer,
    private val activationId: String? = null,
    private val defaults: TelemetryFacet = TelemetryFacet(),
    /**
     * Where aggregate measures go. Shared across every interceptor in one installation, so the
     * figures cover the whole graph rather than one node.
     */
    val metrics: PortMetrics = PortMetrics(),
) : PortInterceptor {

    override fun intercept(invocation: PortInvocation, proceed: () -> Any?): Any? {
        val facet = invocation.port.resolvedTelemetryFacet(defaults)
        if (!shouldObserve(facet)) return proceed()
        val span = if (shouldTrace(facet)) startSpan(invocation, facet) else null
        val started = TimeSource.Monotonic.markNow()
        var failed = false
        return try {
            proceed().also { span?.status = StatusData.Ok }
        } catch (error: Throwable) {
            failed = true
            span?.status = StatusData.Error(error.message ?: error::class.simpleName.orEmpty())
            throw error
        } finally {
            val elapsed = started.elapsedNow().inWholeNanoseconds
            metrics.record(invocation.port, elapsed, failed, facet.semantics)
            span?.setLongAttribute(ReaktorAttributes.DurationNanos, elapsed)
            span?.end()
        }
    }

    override suspend fun interceptSuspend(
        invocation: PortInvocation,
        proceed: suspend () -> Any?,
    ): Any? {
        val facet = invocation.port.resolvedTelemetryFacet(defaults)
        if (!shouldObserve(facet)) return proceed()
        val span = if (shouldTrace(facet)) startSpan(invocation, facet) else null
        val started = TimeSource.Monotonic.markNow()
        var failed = false
        return try {
            proceed().also { span?.status = StatusData.Ok }
        } catch (error: Throwable) {
            failed = true
            span?.status = StatusData.Error(error.message ?: error::class.simpleName.orEmpty())
            throw error
        } finally {
            val elapsed = started.elapsedNow().inWholeNanoseconds
            metrics.record(invocation.port, elapsed, failed, facet.semantics)
            span?.setLongAttribute(ReaktorAttributes.DurationNanos, elapsed)
            span?.end()
        }
    }

    /**
     * Whether this call is observed at all.
     *
     * [TracePolicy.Metrics] is documented as "aggregate only — the interceptor runs but emits no
     * span", and until there was something to aggregate into it did nothing whatsoever: the
     * interceptor returned `proceed()` and recorded neither a span nor a measure. It now means
     * what it says.
     */
    private fun shouldObserve(facet: TelemetryFacet): Boolean =
        facet.tracePolicy != TracePolicy.Off && facet.semantics != PortSemantics.Reference

    private fun shouldTrace(facet: TelemetryFacet): Boolean =
        facet.tracePolicy == TracePolicy.Spans && facet.semantics != PortSemantics.Reference

    private fun startSpan(invocation: PortInvocation, facet: TelemetryFacet) =
        tracer.startSpan(
            name = invocation.port.telemetryOperationName(facet),
            spanKind = spanKindFor(facet.semantics),
        ) {
            val port = invocation.port
            setStringAttribute(ReaktorAttributes.PortId, port.telemetryPortId())
            setStringAttribute(ReaktorAttributes.ContractId, port.type.type)
            setStringAttribute(ReaktorAttributes.PortShape, facet.semantics.name.lowercase())
            setStringAttribute(
                ReaktorAttributes.PortDirection,
                when (port) {
                    is ConsumerPort<*> -> "consumer"
                    is ProviderPort<*> -> "provider"
                },
            )
            (port.owner as? Unique)?.let { node ->
                // Two identities, never merged into one attribute. A node that declares no label
                // has no stable architectural identity, and saying nothing is honest where
                // substituting its per-run Uuid would silently claim otherwise.
                if (node.label.isNotEmpty()) setStringAttribute(ReaktorAttributes.NodeId, node.label)
                setStringAttribute(ReaktorAttributes.NodeInstance, node.id.toString())
            }
            invocation.edge?.let { setStringAttribute(ReaktorAttributes.EdgeId, it.id.toString()) }
            activationId?.let { setStringAttribute(ReaktorAttributes.ActivationId, it) }
        }

    private fun spanKindFor(semantics: PortSemantics) = when (semantics) {
        PortSemantics.Mailbox -> SpanKind.PRODUCER
        PortSemantics.Stream -> SpanKind.CONSUMER
        else -> SpanKind.INTERNAL
    }
}

/**
 * A port's identity within its node.
 *
 * The key alone is blank for the common single-port-per-contract case, and a blank attribute is
 * indistinguishable from an unset one — it grouped every default port in the traces under "".
 * [DefaultPortKey] names that case instead of hiding it.
 */
fun Port<*>.telemetryPortId(): String = key.key.ifBlank { DefaultPortKey }

/** What a port registered with no explicit key is called in telemetry. */
const val DefaultPortKey = "default"
