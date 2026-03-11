package dev.shibasis.reaktor.telemetry

import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.context.Context
import io.opentelemetry.kotlin.tracing.model.Span
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch

/**
 * Holds the telemetry state for a single [Graph].
 *
 * @property graph The instrumented graph
 * @property rootSpan The root OTel span representing the graph's lifetime
 * @property nodeSpans Map of node ID → child spans for lifecycle tracking
 */
@OptIn(ExperimentalApi::class)
class GraphTelemetry(
        val graph: Graph,
        val rootSpan: Span,
        val nodeSpans: MutableMap<Uuid, Span> = mutableMapOf()
)

/**
 * Instruments this [Graph] for telemetry using OpenTelemetry Kotlin.
 *
 * **Non-invasive** — zero changes to Graph.kt, Node.kt, or any existing framework code. Works by
 * observing existing StateFlows and event listeners:
 *
 * 1. **Lifecycle** — collects `graph.lifecycle` StateFlow, records each transition as a span event
 * 2. **Navigation** — collects `backStack.top` StateFlow, extracts route pattern from
 * ```
 *    `BackStackEntry.edge.end.pattern`, fires both a telemetry span event and an
 *    analytics `screen_view` event via `Feature.Analytics`
 * ```
 * 3. **Port events** — attaches `addPortEventListener` on each node to capture
 * ```
 *    Connected/Disconnected as span events
 * ```
 * 4. **Child graphs** — recurses into `ContainerNode.graphs` with parent context
 *
 * @param parentContext Optional OTel [Context] from a parent graph for trace hierarchy
 * @return [GraphTelemetry] handle, or null if `Feature.Telemetry` is not set
 */
@OptIn(ExperimentalApi::class)
fun Graph.instrument(parentContext: Context? = null): GraphTelemetry? {
    val adapter = Feature.Telemetry ?: return null
    val tracer = adapter.tracer

    val rootSpan =
            tracer.startSpan(
                    name = "graph:${label.ifEmpty { id.toString() }}",
                    parentContext = parentContext
            )

    val gt = GraphTelemetry(this, rootSpan)

    // 1. Lifecycle observation
    coroutineScope.launch {
        lifecycle.collect { state ->
            rootSpan.addEvent("lifecycle:${state::class.simpleName.orEmpty()}")
        }
    }

    // 2. Navigation tracking
    coroutineScope.launch {
        backStack.top.collect { entry ->
            val route = entry?.edge?.end?.pattern?.toString() ?: return@collect
            rootSpan.addEvent("navigation:$route")
            Feature.Analytics?.logEvent(
                    "screen_view",
                    mapOf("screen_name" to route, "graph" to label)
            )
        }
    }

    // 3. Port event listeners on existing nodes
    nodes.forEach { node ->
        node.addPortEventListener { event ->
            rootSpan.addEvent(
                    "port:${event::class.simpleName.orEmpty()}:" +
                            "${event.port.key.key}@${node.label.ifEmpty { node.id.toString() }}"
            )
        }
    }

    // 4. Recurse into child graphs
    nodes.filterIsInstance<ContainerNode>().forEach { container ->
        container.graphs.forEach { childGraph -> childGraph.instrument(parentContext) }
    }

    return gt
}
