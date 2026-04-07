package dev.shibasis.reaktor.flow.graph.adapter

import dev.shibasis.reaktor.flow.graph.layout.DefaultGraphFlowMetrics
import dev.shibasis.reaktor.flow.graph.model.ReaktorPortData
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.ControllerNode
import dev.shibasis.reaktor.graph.core.node.Node as GraphNode
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.portgraph.port.Port
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import kotlin.math.max

private val metrics = DefaultGraphFlowMetrics
private const val DUAL_COLUMN_WIDTH_FACTOR = 1.10

// References:
// - Compose custom layout guidance: measurement should be a first-class contract, not an implicit
//   side effect of whatever modifiers happen to be applied in the renderer.
// - xyflow/React Flow source: node width/height and handle anchors are resolved in editor-space and
//   then consumed by rendering/viewport logic; that contract keeps fit/zoom and edge routing stable.
internal fun measureNodeWidth(node: GraphNode): Double {
    val consumers = node.consumerPorts.flattenedValues().toList()
    val providers = node.providerPorts.flattenedValues().toList()
    val titleLength = nodeTitle(node).length
    val maxLeftLabel = consumers.maxOfOrNull { pinLabel(it.key.key, it.type.type).length } ?: 0
    val maxRightLabel = providers.maxOfOrNull { pinLabel(it.key.key, it.type.type).length } ?: 0

    val leftColumnWidth = if (maxLeftLabel == 0) {
        0.0
    } else {
        maxLeftLabel * metrics.portCharWidthEstimate +
            metrics.portDotSize +
            metrics.portGap
    }
    val rightColumnWidth = if (maxRightLabel == 0) {
        0.0
    } else {
        maxRightLabel * metrics.portCharWidthEstimate +
            metrics.portDotSize +
            metrics.portGap
    }
    val bodyWidth = when {
        leftColumnWidth > 0.0 && rightColumnWidth > 0.0 ->
            leftColumnWidth + metrics.columnGap + rightColumnWidth
        else -> max(leftColumnWidth, rightColumnWidth)
    } + metrics.bodyPaddingX * 2.0

    val badgeAllowance = if (graphRootRoute(node.graph) == node) {
        (4 * metrics.titleCharWidthEstimate) +
            metrics.rootBadgePaddingX * 2.0 +
            metrics.titleToBadgeGap
    } else {
        0.0
    }
    val titleWidth =
        titleLength * metrics.titleCharWidthEstimate +
            metrics.titlePaddingX * 2.0 +
            badgeAllowance
    val columnFactor = if (leftColumnWidth > 0.0 && rightColumnWidth > 0.0) {
        DUAL_COLUMN_WIDTH_FACTOR
    } else {
        1.0
    }
    val baseWidth = max(bodyWidth * columnFactor, titleWidth)
    return max(metrics.nodeMinWidth, baseWidth * nodeWidthFactor(node))
}

internal fun measureNodeHeight(providerCount: Int, consumerCount: Int): Double {
    val rowCount = max(providerCount, consumerCount).coerceAtLeast(1)
    return metrics.titleHeight + rowCount * metrics.rowHeight + metrics.nodePaddingY * 2
}

internal fun visiblePorts(ports: List<Port<*>>): List<ReaktorPortData> =
    ports
        .filter { shouldDisplayPort(it.key.key) }
        .map { port ->
            ReaktorPortData(
                handleId = port.key.key.ifBlank { port.type.type },
                label = pinLabel(port.key.key, port.type.type),
                type = shortType(port.type.type),
                color = portColor(port.type.type, port.isConnected()),
                connected = port.isConnected(),
            )
        }
        .distinctBy(ReaktorPortData::handleId)

internal fun handleOffset(index: Int, count: Int): Double = when {
    count <= 0 -> 0.5
    else -> {
        val totalHeight = metrics.titleHeight + count * metrics.rowHeight + metrics.nodePaddingY * 2.0
        val rowCenter =
            metrics.titleHeight +
                metrics.nodePaddingY +
                index * metrics.rowHeight +
                metrics.rowHeight / 2.0
        rowCenter / totalHeight
    }
}

// Width policy belongs in measurement, not in metrics. Different graph products may want
// different semantic width biasing without changing the shared graph-space contract itself.
private fun nodeWidthFactor(node: GraphNode): Double = when (node) {
    is RouteNode<*, *> -> 1.24
    is ControllerNode<*> -> 1.18
    is ContainerNode -> 1.16
    is BasicNode -> 1.10
    else -> 1.12
}
