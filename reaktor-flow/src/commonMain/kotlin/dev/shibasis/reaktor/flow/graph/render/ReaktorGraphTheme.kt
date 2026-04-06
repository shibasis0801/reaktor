package dev.shibasis.reaktor.flow.graph.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import dev.shibasis.composeflow.compose.primitives.EdgeRenderStyle
import dev.shibasis.composeflow.compose.primitives.HandleRenderStyle
import dev.shibasis.composeflow.compose.primitives.NodeRenderStyle
import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.Handle
import dev.shibasis.composeflow.model.HandleType
import dev.shibasis.composeflow.model.Node
import dev.shibasis.reaktor.flow.graph.layout.GraphFlowMetrics
import dev.shibasis.reaktor.flow.graph.model.ReaktorEdgeKind
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphEdgeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind

internal val GraphCanvasChrome = Color(0xCC1C2338)
internal val GraphCanvasBorder = Color(0xFF2F3A56)
internal val GraphCanvasMuted = Color(0xFF93A0BB)
internal val GraphCanvasText = Color(0xFFF1F5FF)
internal val GraphCanvasSelected = Color(0xFFE8A33D)
internal val GraphCanvasBackground = Color(0xFF08101C)
internal val GraphCanvasMono = FontFamily.Monospace
internal val GraphCanvasRootBadge = Color(0x332F8BFF)
internal val GraphCanvasRootBadgeText = Color(0xFFC9D8FF)
internal val ReaktorDefaultNodeWidthPx: Double = GraphFlowMetrics.nodeMinWidth
internal val ReaktorDefaultNodeHeightPx: Double =
    GraphFlowMetrics.titleHeight + (GraphFlowMetrics.rowHeight * 4) + (GraphFlowMetrics.nodePaddingY * 2)

internal object ReaktorGraphChromeTokens {
    val zeroElevation = 0.dp
    val overlayPadding = 12.dp
    val toolbarRadius = 10.dp
    val toolbarHorizontalPadding = 12.dp
    val toolbarVerticalPadding = 9.dp
    val toolbarButtonRadius = 9.dp
    val toolbarButtonHorizontalPadding = 10.dp
    val toolbarButtonVerticalPadding = 6.dp
    val toolbarButtonSpacing = 6.dp
    val toolbarMetadataSpacing = 2.dp
    val toolbarSectionSpacing = 12.dp
    val legendWidth = 176.dp
    val legendRadius = 10.dp
    val legendHorizontalPadding = 12.dp
    val legendVerticalPadding = 10.dp
    val legendItemSpacing = 6.dp
    val legendItemRadius = 10.dp
    val legendItemBorderWidth = 1.dp
    val legendDotGap = 8.dp
    val legendIndicatorSize = 9.dp
    val legendItemHorizontalPadding = 10.dp
    val legendItemVerticalPadding = 7.dp
    val miniMapWidth = 140.dp
    val miniMapHeight = 88.dp
    val miniMapRadius = 10.dp
    val miniMapInnerPadding = 10.dp
    const val miniMapNodeMinSizePx = 6f
    const val miniMapNodeCornerPx = 4f
    const val miniMapViewportCornerPx = 3f
    const val miniMapViewportStrokePx = 1.3f
    const val miniMapEdgeStrokePx = 1f
    const val regionLabelOffsetXPx = 16.0
    const val regionLabelOffsetYPx = 12.0
    val regionLabelRadius = 7.dp
    val regionLabelHorizontalPadding = 14.dp
    val regionLabelVerticalPadding = 8.dp
    val regionCornerRadiusPx = 14f
    val regionDashOnPx = 10f
    val regionDashOffPx = 6f
    val regionSelectedStrokePx = 1.8f
    val regionStrokePx = 1.1f
    const val regionFillAlpha = 0.045f
    const val regionStrokeAlpha = 0.22f
    val rootBadgeHorizontalPadding = 12.dp
    val rootBadgeVerticalPadding = 6.dp
    val rootBadgeRadius = 999.dp
    val viewportBadgeRadius = 999.dp
    val viewportBadgeHorizontalPadding = 10.dp
    val viewportBadgeVerticalPadding = 6.dp
    val rootBadgeFontSize = 16.sp
    val titleFontSize = 20.sp
    val sectionTitleFontSize = 18.sp
    val bodyFontSize = 16.sp
    val viewportFontSize = 15.sp
    val buttonFontSize = 11.sp
    val editorPadding = 4.dp
    val panelSurface = Color(0xFF11182A)
    val miniMapSurface = Color(0xDD0D1020)
    val regionLabelSurface = Color(0x88121A2C)
    val regionSelectedLabelSurface = Color(0xCC162846)
    val legendItemSurface = Color(0x5011182A)
    val hiddenHandleBorder = Color(0xFF09101D)
}

internal object ReaktorGraphViewportTokens {
    const val startupFrameDelayMillis = 120L
    const val readablePaddingXPx = 20.0
    const val readablePaddingYPx = 18.0
    const val fitPaddingXPx = 28.0
    const val fitPaddingYPx = 28.0
    const val readableZoomBias = 1.10
    const val readableMinZoom = 0.74
    const val fitMinZoom = 0.22
    const val maxZoom = 2.4
    const val minZoom = 0.22
    const val zoomStep = 1.12
}

internal data class ReaktorNodeRenderMetrics(
    val cornerRadius: Dp,
    val titleHeight: Dp,
    val rowHeight: Dp,
    val titleHorizontalPadding: Dp,
    val titleTextPadding: Dp,
    val titleToBadgeGap: Dp,
    val bodyHorizontalPadding: Dp,
    val bodyVerticalPadding: Dp,
    val columnGap: Dp,
    val portGap: Dp,
    val portDotSize: Dp,
    val titleFontSize: TextUnit,
    val portFontSize: TextUnit,
    val rootBadgeFontSize: TextUnit,
    val defaultNodeWidth: Dp,
    val defaultNodeHeight: Dp,
)

@Composable
internal fun rememberReaktorNodeRenderMetrics(): ReaktorNodeRenderMetrics {
    val density = LocalDensity.current
    return remember(density) {
        ReaktorNodeRenderMetrics(
            cornerRadius = with(density) { GraphFlowMetrics.nodeCornerRadius.toFloat().toDp() },
            titleHeight = with(density) { GraphFlowMetrics.titleHeight.toFloat().toDp() },
            rowHeight = with(density) { GraphFlowMetrics.rowHeight.toFloat().toDp() },
            titleHorizontalPadding = with(density) { GraphFlowMetrics.titleHorizontalPadding.toFloat().toDp() },
            titleTextPadding = with(density) { GraphFlowMetrics.titleVerticalPadding.toFloat().toDp() },
            titleToBadgeGap = with(density) { GraphFlowMetrics.titleToBadgeGap.toFloat().toDp() },
            bodyHorizontalPadding = with(density) { GraphFlowMetrics.bodyHorizontalPadding.toFloat().toDp() },
            bodyVerticalPadding = with(density) { GraphFlowMetrics.nodePaddingY.toFloat().toDp() },
            columnGap = with(density) { GraphFlowMetrics.columnGap.toFloat().toDp() },
            portGap = with(density) { GraphFlowMetrics.portGap.toFloat().toDp() },
            portDotSize = with(density) { GraphFlowMetrics.portDotSize.toFloat().toDp() },
            titleFontSize = with(density) { GraphFlowMetrics.titleFontSize.toFloat().toSp() },
            portFontSize = with(density) { GraphFlowMetrics.portFontSize.toFloat().toSp() },
            rootBadgeFontSize = with(density) { GraphFlowMetrics.rootBadgeFontSize.toFloat().toSp() },
            defaultNodeWidth = with(density) { ReaktorDefaultNodeWidthPx.toFloat().toDp() },
            defaultNodeHeight = with(density) { ReaktorDefaultNodeHeightPx.toFloat().toDp() },
        )
    }
}

@Composable
internal fun rememberRootBadgePadding() = rememberReaktorNodeRenderMetrics().let { metrics ->
    RootBadgePadding(
        horizontal = with(LocalDensity.current) { GraphFlowMetrics.rootBadgeHorizontalPadding.toFloat().toDp() },
        vertical = with(LocalDensity.current) { GraphFlowMetrics.rootBadgeVerticalPadding.toFloat().toDp() },
    )
}

internal data class RootBadgePadding(
    val horizontal: Dp,
    val vertical: Dp,
)

internal fun graphNodeRenderStyle(
    node: Node,
    highlightedKind: ReaktorNodeKind?,
): NodeRenderStyle {
    val data = node.data as? ReaktorGraphNodeData ?: return NodeRenderStyle()
    val matchesKind = highlightedKind == null || data.kind == highlightedKind
    return NodeRenderStyle(
        alpha = if (matchesKind) 1f else 0.12f,
        scale = 1f,
        backgroundColor = data.kind.bodyColor.copy(alpha = if (matchesKind) 0.92f else 0.44f),
        borderColor = when {
            node.selected -> GraphCanvasSelected
            matchesKind -> data.kind.borderColor
            else -> data.kind.borderColor.copy(alpha = 0.30f)
        },
    )
}

internal fun graphEdgeRenderStyle(
    edge: Edge,
    nodeKinds: Map<String, ReaktorNodeKind>,
    highlightedKind: ReaktorNodeKind?,
): EdgeRenderStyle {
    val data = edge.data as? ReaktorGraphEdgeData ?: return EdgeRenderStyle()
    val sourceKind = nodeKinds[edge.source]
    val targetKind = nodeKinds[edge.target]
    val matchesKind = highlightedKind == null || sourceKind == highlightedKind || targetKind == highlightedKind
    return EdgeRenderStyle(
        alpha = if (matchesKind) 0.86f else 0.08f,
        color = data.kind.color.copy(alpha = if (matchesKind) 0.92f else 0.35f),
        width = when (data.kind) {
            ReaktorEdgeKind.Navigation -> 2.3f
            ReaktorEdgeKind.Attachment -> 2.1f
            ReaktorEdgeKind.Data -> 1.9f
            ReaktorEdgeKind.Containment -> 1.8f
        },
    )
}

internal fun graphHandleRenderStyle(
    node: Node,
    handle: Handle,
    highlightedKind: ReaktorNodeKind?,
): HandleRenderStyle {
    val data = node.data as? ReaktorGraphNodeData ?: return HandleRenderStyle()
    val matchesKind = highlightedKind == null || data.kind == highlightedKind
    val color = when (handle.id) {
        "__nav__", "navBinding", "routeBinding" -> ReaktorEdgeKind.Navigation.color
        "__contains__" -> ReaktorEdgeKind.Containment.color
        else -> when (handle.type) {
            HandleType.Source -> data.providerPorts.firstOrNull { it.handleId == handle.id }?.color
            HandleType.Target -> data.consumerPorts.firstOrNull { it.handleId == handle.id }?.color
        } ?: GraphCanvasMuted
    }
    return HandleRenderStyle(
        fillColor = color,
        borderColor = ReaktorGraphChromeTokens.hiddenHandleBorder,
        alpha = if (matchesKind) 0f else 0f,
        size = 11.dp,
    )
}
