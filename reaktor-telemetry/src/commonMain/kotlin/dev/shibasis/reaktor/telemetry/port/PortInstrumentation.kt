package dev.shibasis.reaktor.telemetry.port

import dev.shibasis.reaktor.portgraph.attach.attach
import dev.shibasis.reaktor.portgraph.graph.PortGraph
import dev.shibasis.reaktor.portgraph.node.PortNode
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.Port
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.addInterceptor
import dev.shibasis.reaktor.portgraph.port.removeInterceptor
import dev.shibasis.reaktor.portgraph.visitor.DepthFirstTraverser
import dev.shibasis.reaktor.portgraph.visitor.ExitScope
import dev.shibasis.reaktor.portgraph.visitor.PortGraphVisitor
import dev.shibasis.reaktor.portgraph.visitor.StructuralSelector
import dev.shibasis.reaktor.portgraph.visitor.Visitable
import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.tracing.Tracer

/**
 * D6 — compiling the instrumentation plan.
 *
 * The visitor decides *what* is instrumented by walking the declared graph. K1 decides *when* a
 * call is observed. Those are separate jobs: this pass runs once per revision and installs
 * nothing at call time.
 */
@OptIn(ExperimentalApi::class)
class TelemetryPlanVisitor(
    private val interceptor: PortTelemetryInterceptor,
    private val defaults: TelemetryFacet = TelemetryFacet(),
) : PortGraphVisitor() {

    private val _instrumented = mutableListOf<Port<*>>()
    private val _skipped = mutableListOf<Pair<Port<*>, String>>()

    /** Ports this pass attached to, in visit order. */
    val instrumented: List<Port<*>> get() = _instrumented

    /** Ports deliberately left alone, each with the reason — the honest half of the plan. */
    val skipped: List<Pair<Port<*>, String>> get() = _skipped

    override fun visitConsumerPort(port: ConsumerPort<*>): ExitScope = install(port)

    override fun visitProviderPort(port: ProviderPort<*>): ExitScope = install(port)

    private fun install(port: Port<*>): ExitScope {
        val facet = port.resolvedTelemetryFacet(defaults)
        when {
            facet.tracePolicy == TracePolicy.Off ->
                _skipped += port to "trace policy is Off"
            facet.semantics == PortSemantics.Reference ->
                _skipped += port to "reference handoff — the call happens off-port"
            else -> {
                port.addInterceptor(interceptor)
                _instrumented += port
            }
        }
        return NoOpExit
    }
}

/** The compiled plan, kept so it can be reported and reversed. */
class TelemetryPlan internal constructor(
    private val interceptor: PortTelemetryInterceptor,
    val instrumented: List<Port<*>>,
    val skipped: List<Pair<Port<*>, String>>,
) {
    /** Detaching is exactly attach-then-detach on K2 — a capture can expire cleanly. */
    fun uninstall() = instrumented.forEach { it.removeInterceptor(interceptor) }

    /**
     * Combines two plans over different roots so a caller instrumenting several graphs can report
     * one coverage number. [uninstall] on the result removes only this plan's own interceptor;
     * keep the originals if the roots used different ones.
     */
    fun merge(other: TelemetryPlan) = TelemetryPlan(
        interceptor,
        instrumented + other.instrumented,
        skipped + other.skipped,
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

/** Walks [root] and installs [interceptor] wherever policy allows. */
@OptIn(ExperimentalApi::class)
fun instrumentPorts(
    root: Visitable,
    interceptor: PortTelemetryInterceptor,
    defaults: TelemetryFacet = TelemetryFacet(),
): TelemetryPlan {
    val visitor = TelemetryPlanVisitor(interceptor, defaults)
    DepthFirstTraverser.traverse(root, StructuralSelector, visitor)
    return TelemetryPlan(interceptor, visitor.instrumented.toList(), visitor.skipped.toList())
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
