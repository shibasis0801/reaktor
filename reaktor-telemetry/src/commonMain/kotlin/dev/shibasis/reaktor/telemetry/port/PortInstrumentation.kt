package dev.shibasis.reaktor.telemetry.port

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.portgraph.attach.attach
import dev.shibasis.reaktor.portgraph.graph.PortGraph
import dev.shibasis.reaktor.portgraph.node.PortNode
import dev.shibasis.reaktor.portgraph.port.Port
import dev.shibasis.reaktor.portgraph.port.addInterceptor
import dev.shibasis.reaktor.portgraph.port.removeInterceptor
import dev.shibasis.reaktor.portgraph.traversal.Contains
import dev.shibasis.reaktor.portgraph.traversal.Step
import dev.shibasis.reaktor.portgraph.traversal.Visitable
import dev.shibasis.reaktor.portgraph.traversal.depthFirst
import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.tracing.Tracer

/** The compiled plan, kept so it can be reported and reversed. */
class TelemetryPlan internal constructor(
    private val interceptor: PortTelemetryInterceptor,
    val instrumented: List<Port<*>>,
    val skipped: List<Pair<Port<*>, String>>,
    private val subjects: Set<String> = emptySet(),
) {
    /** Detaching is exactly attach-then-detach on K2 — a capture can expire cleanly. */
    fun uninstall() {
        instrumented.forEach { it.removeInterceptor(interceptor) }
        interceptor.unbind(subjects)
    }

    /**
     * Combines two plans over different roots so a caller instrumenting several graphs can report
     * one coverage number. [uninstall] on the result removes only this plan's own interceptor;
     * keep the originals if the roots used different ones.
     */
    fun merge(other: TelemetryPlan) = TelemetryPlan(
        interceptor,
        instrumented + other.instrumented,
        skipped + other.skipped,
        subjects + other.subjects,
    )

    override fun toString() =
        "[TelemetryPlan] instrumented=${instrumented.size} skipped=${skipped.size}"
}

/**
 * Declares telemetry policy for an entity. Ports inherit from their owning node when they carry
 * no policy of their own.
 */
fun dev.shibasis.reaktor.portgraph.attach.Attachable.telemetry(facet: TelemetryFacet) =
    attach(TelemetryPolicy, facet)

@OptIn(ExperimentalApi::class)
fun instrumentPorts(
    root: Visitable,
    interceptor: PortTelemetryInterceptor,
    defaults: TelemetryFacet = TelemetryFacet(),
): TelemetryPlan {
    val subjects = when (root) {
        is Graph -> interceptor.bind(root.shape())
        is Node -> interceptor.bind(root.graph.shape())
        else -> emptySet()
    }
    val instrumented = mutableListOf<Port<*>>()
    val skipped = mutableListOf<Pair<Port<*>, String>>()
    root.depthFirst(Contains).forEach { step ->
        val port = (step as? Step.Enter)?.element as? Port<*> ?: return@forEach
        val facet = port.resolvedTelemetryFacet(defaults)
        when {
            facet.tracePolicy == TracePolicy.Off -> skipped += port to "trace policy is Off"
            facet.semantics == PortSemantics.Reference -> skipped += port to "reference handoff — the call happens off-port"
            else -> {
                port.addInterceptor(interceptor)
                instrumented += port
            }
        }
    }
    return TelemetryPlan(interceptor, instrumented, skipped, subjects)
}

/** Convenience for the common case: instrument a whole port graph with one tracer. */
@OptIn(ExperimentalApi::class)
fun PortGraph<*, *>.instrumentPorts(
    tracer: Tracer,
    activationId: String? = null,
    defaults: TelemetryFacet = TelemetryFacet(semantics = PortSemantics.Request),
): TelemetryPlan = instrumentPorts(this, PortTelemetryInterceptor(tracer, activationId, defaults), defaults)

/** Instrument a single node's ports, for a scoped capture command. */
@OptIn(ExperimentalApi::class)
fun PortNode<*>.instrumentPorts(
    tracer: Tracer,
    activationId: String? = null,
    defaults: TelemetryFacet = TelemetryFacet(semantics = PortSemantics.Request),
): TelemetryPlan = instrumentPorts(this, PortTelemetryInterceptor(tracer, activationId, defaults), defaults)
