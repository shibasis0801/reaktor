package dev.shibasis.reaktor.flow.graph.render

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import dev.shibasis.composeflow.compose.primitives.EdgeRenderStyle
import dev.shibasis.composeflow.compose.primitives.HandleRenderStyle
import dev.shibasis.composeflow.compose.primitives.NodeRenderStyle
import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.Handle
import dev.shibasis.composeflow.model.HandleType
import dev.shibasis.composeflow.model.Node
import dev.shibasis.reaktor.flow.graph.model.ReaktorEdgeKind
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphEdgeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

// References:
// - Fluent UI / styled-system token layering: keep semantic chrome tokens small and let components
//   compose from them instead of introducing parallel ad hoc literals.
// - Compose internals/custom layout guidance: layout math belongs to a stable measurement contract;
//   this file only converts graph-space metrics into render units and supplies editor chrome colors.
internal val GraphCanvasChrome = Color(0xCC1C2338)
internal val GraphCanvasBorder = Color(0xFF2F3A56)
internal val GraphCanvasMuted = Color(0xFF93A0BB)
internal val GraphCanvasText = Color(0xFFF1F5FF)
internal val GraphCanvasSelected = Color(0xFFE8A33D)
internal val GraphCanvasBackground = Color(0xFF08101C)
internal val GraphCanvasMono = FontFamily.Monospace
internal val GraphCanvasRootBadge = Color(0x332F8BFF)
internal val GraphCanvasRootBadgeText = Color(0xFFC9D8FF)

internal data class DpAxisInsets(
    val horizontal: Dp,
    val vertical: Dp,
)

internal data class PxAxisInsets(
    val horizontal: Double,
    val vertical: Double,
)

internal data class PxAxisOffset(
    val x: Double,
    val y: Double,
)

// Keep one editor-chrome surface instead of separate chrome and viewport token objects. Graph
// sizing belongs to GraphFlowMetrics; editor chrome and framing policy belong here.
internal data class ReaktorGraphUiMetrics(
    val spacing: Dp = 12.dp,
    val radius: Dp = 10.dp,
    val borderWidth: Dp = 1.dp,
    val miniMapSize: DpSize = DpSize(width = 140.dp, height = 88.dp),
    val panelSurface: Color = Color(0xFF11182A),
    val miniMapSurface: Color = Color(0xDD0D1020),
    val regionLabelSurface: Color = Color(0x88121A2C),
    val startupFrameDelayMillis: Long = 120L,
    val readablePadding: PxAxisInsets = PxAxisInsets(horizontal = 20.0, vertical = 18.0),
    val fitPadding: PxAxisInsets = PxAxisInsets(horizontal = 28.0, vertical = 28.0),
    val readableZoomBias: Double = 1.10,
    val readableMinZoom: Double = 0.74,
    val maxZoom: Double = 2.4,
    val minZoom: Double = 0.22,
    val zoomStep: Double = 1.12,
){
    val overlayPadding: Dp get() = spacing
    val panelRadius: Dp get() = radius
    val shellPadding: DpAxisInsets get() = DpAxisInsets(horizontal = spacing, vertical = spacing * 0.75f)
    val controlPadding: DpAxisInsets get() = DpAxisInsets(horizontal = spacing * 0.8333333f, vertical = spacing * 0.5f)
    val sectionGap: Dp get() = spacing
    val itemGap: Dp get() = spacing * 0.5f
    val microGap: Dp get() = spacing * 0.1666667f
    val legendWidth: Dp get() = miniMapSize.width + spacing * 3
    val indicatorSize: Dp get() = spacing * 0.75f
    val miniMapInnerPadding: Dp get() = spacing * 0.8333333f
    val hiddenHandleSize: Dp get() = spacing * 0.9166667f
    val editorPadding: Dp get() = spacing * 0.3333333f
    val regionLabelPadding: DpAxisInsets get() = DpAxisInsets(horizontal = spacing * 1.1666667f, vertical = spacing * 0.6666667f)
    val regionSelectedLabelSurface: Color get() = regionLabelSurface.copy(alpha = 0.9f)
    val legendItemSurface: Color get() = panelSurface.copy(alpha = 0.32f)
    val hiddenHandleBorder: Color get() = GraphCanvasBackground
}

internal val GraphUi = ReaktorGraphUiMetrics()

internal fun Density.dpOf(value: Double): Dp = value.toFloat().toDp()

internal fun Density.spOf(value: Double): TextUnit = value.toFloat().toSp()

internal fun Density.regionLabelOffsetPx(ui: ReaktorGraphUiMetrics = GraphUi): PxAxisOffset =
    PxAxisOffset(
        x = ui.spacing.toPx().toDouble() * 1.3333333,
        y = ui.spacing.toPx().toDouble(),
    )

internal fun Density.regionCornerRadiusPx(ui: ReaktorGraphUiMetrics = GraphUi): Float =
    ui.radius.toPx() * 1.4f

internal fun Density.regionDashOnPx(ui: ReaktorGraphUiMetrics = GraphUi): Float =
    ui.spacing.toPx() * 0.8333333f

internal fun Density.regionDashOffPx(ui: ReaktorGraphUiMetrics = GraphUi): Float =
    ui.spacing.toPx() * 0.5f

internal fun Density.regionSelectedStrokePx(ui: ReaktorGraphUiMetrics = GraphUi): Float =
    max(ui.borderWidth.toPx(), 1f) * 1.8f

internal fun Density.regionStrokePx(ui: ReaktorGraphUiMetrics = GraphUi): Float =
    max(ui.borderWidth.toPx(), 1f) * 1.1f

internal fun Density.miniMapNodeMinSizePx(ui: ReaktorGraphUiMetrics = GraphUi): Float =
    ui.spacing.toPx() * 0.5f

internal fun Density.miniMapNodeCornerPx(ui: ReaktorGraphUiMetrics = GraphUi): Float =
    ui.radius.toPx() * 0.4f

internal fun Density.miniMapViewportCornerPx(ui: ReaktorGraphUiMetrics = GraphUi): Float =
    ui.radius.toPx() * 0.3f

internal fun Density.miniMapViewportStrokePx(ui: ReaktorGraphUiMetrics = GraphUi): Float =
    max(ui.borderWidth.toPx(), 1f) * 1.3f

internal fun Density.miniMapEdgeStrokePx(ui: ReaktorGraphUiMetrics = GraphUi): Float =
    max(ui.borderWidth.toPx(), 1f)

internal const val REGION_FILL_ALPHA = 0.045f
internal const val REGION_STROKE_ALPHA = 0.22f

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
        borderColor = GraphUi.hiddenHandleBorder,
        alpha = if (matchesKind) 0f else 0f,
        size = GraphUi.hiddenHandleSize,
    )
}
