package dev.shibasis.reaktor.flow.graph.render

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphEdgeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorPortData

internal enum class ReaktorGraphAccessibilityKind {
    Node,
    Port,
    Connection,
}

internal data class ReaktorGraphAccessibilityItem(
    val kind: ReaktorGraphAccessibilityKind,
    val label: String,
)

internal fun graphNodeAccessibilityLabel(data: ReaktorGraphNodeData): String =
    "Graph node: ${data.title}; kind ${data.kind.label}; graph ${data.graphLabel}"

internal fun graphPortAccessibilityLabel(
    node: ReaktorGraphNodeData,
    port: ReaktorPortData,
    direction: String,
): String =
    "Graph port: ${node.title}; $direction ${port.label}; type ${port.type}; " +
        if (port.connected) "connected" else "open"

private fun graphConnectionAccessibilityLabel(
    flow: ReaktorFlowGraph,
    sourceId: String,
    targetId: String,
    edgeData: ReaktorGraphEdgeData?,
    fallbackLabel: String?,
): String {
    val source = flow.nodes.firstOrNull { it.id == sourceId }
        ?.data as? ReaktorGraphNodeData
    val target = flow.nodes.firstOrNull { it.id == targetId }
        ?.data as? ReaktorGraphNodeData
    val sourceLabel = source?.title ?: sourceId
    val targetLabel = target?.title ?: targetId
    val kind = edgeData?.kind?.label ?: "Connection"
    val contract = (edgeData?.label ?: fallbackLabel)?.takeIf(String::isNotBlank)
    return buildString {
        append("Graph connection: ")
        append(sourceLabel)
        append(" to ")
        append(targetLabel)
        append("; kind ")
        append(kind)
        contract?.let {
            append("; ")
            append(it)
        }
    }
}

/** A stable semantic inventory derived from the same flow that is drawn on the canvas. */
internal fun reaktorGraphAccessibilityItems(flow: ReaktorFlowGraph): List<ReaktorGraphAccessibilityItem> =
    buildList {
        flow.nodes.filterNot { it.hidden }.forEach { node ->
            val data = node.data as? ReaktorGraphNodeData ?: return@forEach
            add(
                ReaktorGraphAccessibilityItem(
                    kind = ReaktorGraphAccessibilityKind.Node,
                    label = graphNodeAccessibilityLabel(data),
                ),
            )
            data.consumerPorts.forEach { port ->
                add(
                    ReaktorGraphAccessibilityItem(
                        kind = ReaktorGraphAccessibilityKind.Port,
                        label = graphPortAccessibilityLabel(data, port, "consumer"),
                    ),
                )
            }
            data.providerPorts.forEach { port ->
                add(
                    ReaktorGraphAccessibilityItem(
                        kind = ReaktorGraphAccessibilityKind.Port,
                        label = graphPortAccessibilityLabel(data, port, "provider"),
                    ),
                )
            }
        }
        flow.edges.filterNot { it.hidden }.forEach { edge ->
            add(
                ReaktorGraphAccessibilityItem(
                    kind = ReaktorGraphAccessibilityKind.Connection,
                    label = graphConnectionAccessibilityLabel(
                        flow = flow,
                        sourceId = edge.source,
                        targetId = edge.target,
                        edgeData = edge.data as? ReaktorGraphEdgeData,
                        fallbackLabel = edge.label,
                    ),
                ),
            )
        }
    }

/**
 * Canvas wires are drawn in one Canvas and therefore have no natural semantics node. This
 * nonvisual overlay publishes one independently addressable item per connection. Nodes and ports
 * keep semantics on their visible cards/rows so assistive technology never encounters a duplicate
 * hidden inventory. The overlay has no background and cannot alter layout or canvas interaction.
 */
@Composable
internal fun BoxScope.ReaktorGraphAccessibilityOverlay(flow: ReaktorFlowGraph) {
    val items = remember(flow) { reaktorGraphAccessibilityItems(flow) }
    items
        .filter { it.kind == ReaktorGraphAccessibilityKind.Connection }
        .forEachIndexed { index, item ->
            Spacer(
                Modifier
                    .align(Alignment.TopStart)
                    .size(1.dp)
                    .testTag("reaktor-graph-${item.kind.name.lowercase()}-$index")
                    .clearAndSetSemantics {
                        contentDescription = item.label
                    },
            )
        }
}
