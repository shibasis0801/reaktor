package dev.shibasis.reaktor.flow.graph.adapter

import dev.shibasis.reaktor.flow.graph.model.ReaktorPortData
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.ControllerNode
import dev.shibasis.reaktor.graph.core.node.Node as GraphNode
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.portgraph.port.Port
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import kotlin.math.max

// References:
// - Compose custom layout guidance: measurement should be a first-class contract, not an implicit
//   side effect of whichever modifiers happen to be applied in the renderer.
// - xyflow/React Flow source: node width/height and handle anchors are resolved in editor-space and
//   then consumed by rendering/viewport logic; that contract keeps fit/zoom and edge routing stable.
internal fun measureNodeWidth(
    node: GraphNode,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
): Double {
    val consumers = node.consumerPorts.flattenedValues().toList()
    val providers = node.providerPorts.flattenedValues().toList()
    val titleLength = nodeTitle(node).length
    val maxLeftLabel = consumers.maxOfOrNull { pinLabel(it.key.key, it.type.type).length } ?: 0
    val maxRightLabel = providers.maxOfOrNull { pinLabel(it.key.key, it.type.type).length } ?: 0

    val leftColumnWidth = if (maxLeftLabel == 0) {
        0.0
    } else {
        maxLeftLabel * style.port.charWidthPx + style.port.dotSizePx + style.port.gapPx
    }
    val rightColumnWidth = if (maxRightLabel == 0) {
        0.0
    } else {
        maxRightLabel * style.port.charWidthPx + style.port.dotSizePx + style.port.gapPx
    }
    val bodyWidth = when {
        leftColumnWidth > 0.0 && rightColumnWidth > 0.0 ->
            leftColumnWidth + style.port.columnGapPx + rightColumnWidth
        else -> max(leftColumnWidth, rightColumnWidth)
    } + style.node.bodyPaddingXPx * 2.0

    val badgeAllowance = if (graphRootRoute(node.graph) == node) {
        (4 * style.node.titleCharWidthPx) +
            style.node.rootBadgePaddingXPx * 2.0 +
            style.node.titleToBadgeGapPx
    } else {
        0.0
    }
    val titleWidth =
        titleLength * style.node.titleCharWidthPx +
            style.node.titlePaddingXPx * 2.0 +
            badgeAllowance
    val columnFactor = if (leftColumnWidth > 0.0 && rightColumnWidth > 0.0) {
        style.widthPolicy.dualColumnFactor
    } else {
        1.0
    }
    val baseWidth = max(bodyWidth * columnFactor, titleWidth)
    return max(style.node.minWidthPx, baseWidth * nodeWidthFactor(node, style))
}

internal fun measureNodeHeight(
    providerCount: Int,
    consumerCount: Int,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
): Double {
    val rowCount = max(providerCount, consumerCount).coerceAtLeast(1)
    return style.node.titleHeightPx + rowCount * style.port.rowHeightPx + style.node.verticalPaddingPx * 2.0
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

internal fun handleOffset(
    index: Int,
    count: Int,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
): Double = when {
    count <= 0 -> 0.5
    else -> {
        val totalHeight = style.node.titleHeightPx + count * style.port.rowHeightPx + style.node.verticalPaddingPx * 2.0
        val rowCenter =
            style.node.titleHeightPx +
                style.node.verticalPaddingPx +
                index * style.port.rowHeightPx +
                style.port.rowHeightPx / 2.0
        rowCenter / totalHeight
    }
}

private fun nodeWidthFactor(
    node: GraphNode,
    style: ReaktorGraphStyle,
): Double = when (node) {
    is RouteNode<*, *> -> style.widthPolicy.routeFactor
    is ControllerNode<*> -> style.widthPolicy.controllerFactor
    is ContainerNode -> style.widthPolicy.containerFactor
    is BasicNode -> style.widthPolicy.basicFactor
    else -> style.widthPolicy.defaultFactor
}
