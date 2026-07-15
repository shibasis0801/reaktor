package dev.shibasis.reaktor.flow.graph.style

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tuning map for the Reaktor graph scene.
 *
 * Change only this object when you want to retune graph readability:
 * - node card sizing / title sizing / badge sizing -> [node]
 * - port rows / port text / handle inset -> [port]
 * - lane and graph packing -> [layout]
 * - graph region frame and label chip -> [region]
 * - toolbar / legend / minimap chrome -> [chrome]
 * - first-open framing and zoom behavior -> [viewport]
 *
 * References:
 * - Jetpack Compose custom layout + "Thinking in Compose": measurement should be explicit and
 *   stable, then converted to UI units at the render boundary.
 * - xyflow / React Flow: node bounds, handles, and fit-view math all operate in editor-space
 *   before being projected into browser/render units.
 * - Fluent / styled-system token layering: semantic style objects are easier to tune than
 *   scattered literals hidden inside components.
 */
data class ReaktorGraphStyle(
    val canvas: Canvas = Canvas(),
    val layout: Layout = Layout(),
    val node: Node = Node(),
    val port: Port = Port(),
    val region: Region = Region(),
    val chrome: Chrome = Chrome(),
    val viewport: Viewport = Viewport(),
    val widthPolicy: WidthPolicy = WidthPolicy(),
) {
    data class Canvas(
        val background: Color = Color(0xFF06080D),
        val panelChrome: Color = Color(0xD90A0D14),
        val panelSurface: Color = Color(0xFF0F131C),
        val border: Color = Color(0xFF232B3E),
        val mutedText: Color = Color(0xFFA1ABC0),
        val text: Color = Color(0xFFE6EAF2),
        val selected: Color = Color(0xFF4B7BFF),
        val rootBadge: Color = Color(0x244B7BFF),
        val rootBadgeText: Color = Color(0xFFCFD9FF),
        val regionLabelSurface: Color = Color(0xA00A0D14),
        val miniMapSurface: Color = Color(0xE30F131C),
        val monoFont: FontFamily = FontFamily.Monospace,
    )

    // Layout spacing is graph-space only:
    // - column/row gaps separate peer nodes
    // - compact gaps pack tighter local groups
    // - group gap separates major graph sections such as services -> routes
    data class Layout(
        val rootOriginPx: Double = 24.0,
        val columnGapPx: Double = 74.0,
        val rowGapPx: Double = 46.0,
        val compactColumnGapPx: Double = 52.0,
        val compactRowGapPx: Double = 34.0,
        val groupColumnGapPx: Double = 92.0,
    )

    data class Node(
        val minWidthPx: Double = 152.0,
        val titleHeightPx: Double = 25.0,
        val cornerRadiusPx: Double = 6.0,
        val verticalPaddingPx: Double = 6.0,
        val titlePaddingXPx: Double = 8.0,
        val titlePaddingYPx: Double = 5.0,
        val titleToBadgeGapPx: Double = 6.0,
        val bodyPaddingXPx: Double = 8.0,
        val titleFontPx: Double = 10.5,
        val titleCharWidthPx: Double = 5.8,
        val rootBadgeFontPx: Double = 9.0,
        val rootBadgePaddingXPx: Double = 5.0,
        val rootBadgePaddingYPx: Double = 2.0,
    )

    data class Port(
        val rowHeightPx: Double = 16.0,
        val columnGapPx: Double = 12.0,
        val gapPx: Double = 6.0,
        val dotSizePx: Double = 7.0,
        val fontPx: Double = 10.0,
        val charWidthPx: Double = 5.4,
        val insetPx: Double = 7.0,
        val navigationHandleOffset: Double = 0.5,
        val containmentHandleOffset: Double = 0.84,
        val previewRows: Int = 2,
    )

    // Region spacing is kept separate from node-to-node spacing:
    // - content padding moves content away from the region frame
    // - child-region gaps separate nested graphs from each other
    // - bounds insets expand the painted frame around already-placed content
    data class Region(
        val contentPaddingXPx: Double = 22.0,
        val contentPaddingTopPx: Double = 44.0,
        val contentPaddingBottomPx: Double = 22.0,
        val childRegionGapXPx: Double = 22.0,
        val childRegionGapYPx: Double = 8.0,
        val boundsInsetXPx: Double = 14.0,
        val boundsInsetTopPx: Double = 10.0,
        val boundsInsetBottomPx: Double = 14.0,
        val labelOffsetXPx: Double = 10.0,
        val labelOffsetYPx: Double = 8.0,
        val labelPaddingXPx: Double = 8.0,
        val labelPaddingYPx: Double = 4.0,
        val labelRadiusPx: Double = 6.0,
        val cornerRadiusPx: Double = 8.0,
        val dashOnPx: Double = 7.0,
        val dashOffPx: Double = 5.0,
        val strokeWidthPx: Double = 1.1,
        val selectedStrokeWidthPx: Double = 1.8,
        val fillAlpha: Float = 0.045f,
        val strokeAlpha: Float = 0.22f,
    )

    data class Chrome(
        val overlayPaddingPx: Double = 16.0,
        val panelRadiusPx: Double = 6.0,
        val borderWidthPx: Double = 1.0,
        val shellPaddingXPx: Double = 16.0,
        val shellPaddingYPx: Double = 12.0,
        val controlPaddingXPx: Double = 12.0,
        val controlPaddingYPx: Double = 8.0,
        val sectionGapPx: Double = 24.0,
        val itemGapPx: Double = 8.0,
        val microGapPx: Double = 4.0,
        val legendWidthPx: Double = 196.0,
        val indicatorSizePx: Double = 10.0,
        val miniMapWidthPx: Double = 212.0,
        val miniMapHeightPx: Double = 134.0,
        val miniMapInnerPaddingPx: Double = 10.0,
        val miniMapNodeMinSizePx: Double = 6.0,
        val miniMapNodeCornerPx: Double = 4.0,
        val miniMapViewportCornerPx: Double = 3.0,
        val miniMapViewportStrokePx: Double = 1.3,
        val miniMapEdgeStrokePx: Double = 1.0,
        val hiddenHandleSizePx: Double = 7.0,
        val editorPaddingPx: Double = 4.0,
        val titleFontPx: Double = 14.0,
        val bodyFontPx: Double = 10.5,
        val captionFontPx: Double = 9.5,
    )

    data class Viewport(
        val startupFrameDelayMillis: Long = 90L,
        /** Clear the graph lens strip and top-right minimap before framing nodes. */
        val chromeClearanceTopPx: Double = 160.0,
        /** Clear the bottom status strip and viewport controls before framing nodes. */
        val chromeClearanceBottomPx: Double = 72.0,
        /** Clear the vertical kind legend rendered over the lower-left viewport. */
        val chromeClearanceLeftPx: Double = 228.0,
        /** Optional consumer-owned right inset; top chrome is cleared vertically. */
        val chromeClearanceRightPx: Double = 0.0,
        val readablePaddingXPx: Double = 18.0,
        val readablePaddingYPx: Double = 18.0,
        val fitPaddingXPx: Double = 26.0,
        val fitPaddingYPx: Double = 26.0,
        val readableZoomBias: Double = 1.08,
        val readableMinZoom: Double = 0.62,
        val minZoom: Double = 0.16,
        val maxZoom: Double = 3.2,
        val zoomStep: Double = 1.18,
    )

    data class WidthPolicy(
        val dualColumnFactor: Double = 1.0,
        val routeFactor: Double = 1.02,
        val controllerFactor: Double = 1.01,
        val containerFactor: Double = 1.02,
        val basicFactor: Double = 1.0,
        val defaultFactor: Double = 1.0,
    )
}

val DefaultReaktorGraphStyle = ReaktorGraphStyle()

data class PxAxisInsets(
    val horizontal: Double,
    val vertical: Double,
)

data class PxAxisOffset(
    val x: Double,
    val y: Double,
)

fun Density.dpOf(value: Double): Dp = value.toFloat().toDp()

fun Density.spOf(value: Double): TextUnit = value.toFloat().toSp()

fun ReaktorGraphStyle.defaultNodeWidth(): Double = node.minWidthPx

fun ReaktorGraphStyle.defaultNodeHeight(): Double =
    node.titleHeightPx + (port.rowHeightPx * port.previewRows) + (node.verticalPaddingPx * 2.0)

fun ReaktorGraphStyle.legendItemSurface(): Color = canvas.panelSurface.copy(alpha = 0.32f)

fun ReaktorGraphStyle.regionSelectedLabelSurface(): Color = canvas.regionLabelSurface.copy(alpha = 0.9f)

fun ReaktorGraphStyle.hiddenHandleBorder(): Color = canvas.background

fun ReaktorGraphStyle.readablePadding(): PxAxisInsets = PxAxisInsets(
    horizontal = viewport.readablePaddingXPx,
    vertical = viewport.readablePaddingYPx,
)

fun ReaktorGraphStyle.fitPadding(): PxAxisInsets = PxAxisInsets(
    horizontal = viewport.fitPaddingXPx,
    vertical = viewport.fitPaddingYPx,
)

fun ReaktorGraphStyle.regionLabelOffset(): PxAxisOffset = PxAxisOffset(
    x = region.labelOffsetXPx,
    y = region.labelOffsetYPx,
)
