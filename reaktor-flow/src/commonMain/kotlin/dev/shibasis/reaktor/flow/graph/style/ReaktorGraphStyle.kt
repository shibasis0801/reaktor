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
        val background: Color = Color(0xFF08101C),
        val panelChrome: Color = Color(0xD9151D2B),
        val panelSurface: Color = Color(0xFF121A28),
        val border: Color = Color(0xFF2B3851),
        val mutedText: Color = Color(0xFF93A0BB),
        val text: Color = Color(0xFFF1F5FF),
        val selected: Color = Color(0xFFE8A33D),
        val rootBadge: Color = Color(0x4A2F4F82),
        val rootBadgeText: Color = Color(0xFFC9D8FF),
        val regionLabelSurface: Color = Color(0xA0121A29),
        val miniMapSurface: Color = Color(0xE30D1220),
        val monoFont: FontFamily = FontFamily.Monospace,
    )

    data class Layout(
        val rootOriginPx: Double = 72.0,
        val gapPx: Double = 14.0,
        val compactGapPx: Double = 10.0,
        val majorGapPx: Double = 18.0,
    )

    data class Node(
        val minWidthPx: Double = 720.0,
        val titleHeightPx: Double = 88.0,
        val cornerRadiusPx: Double = 20.0,
        val verticalPaddingPx: Double = 20.0,
        val titlePaddingXPx: Double = 34.0,
        val titlePaddingYPx: Double = 16.0,
        val titleToBadgeGapPx: Double = 18.0,
        val bodyPaddingXPx: Double = 24.0,
        val titleFontPx: Double = 34.0,
        val titleCharWidthPx: Double = 19.0,
        val rootBadgeFontPx: Double = 18.0,
        val rootBadgePaddingXPx: Double = 12.0,
        val rootBadgePaddingYPx: Double = 6.0,
    )

    data class Port(
        val rowHeightPx: Double = 62.0,
        val columnGapPx: Double = 28.0,
        val gapPx: Double = 16.0,
        val dotSizePx: Double = 14.0,
        val fontPx: Double = 27.0,
        val charWidthPx: Double = 15.5,
        val insetPx: Double = 14.0,
        val navigationHandleOffset: Double = 0.5,
        val containmentHandleOffset: Double = 0.84,
        val previewRows: Int = 4,
    )

    data class Region(
        val graphInsetPx: Double = 56.0,
        val insetXPx: Double = 24.0,
        val insetTopPx: Double = 14.0,
        val insetBottomPx: Double = 24.0,
        val labelOffsetXPx: Double = 14.0,
        val labelOffsetYPx: Double = 10.0,
        val labelPaddingXPx: Double = 12.0,
        val labelPaddingYPx: Double = 6.0,
        val labelRadiusPx: Double = 9.0,
        val cornerRadiusPx: Double = 14.0,
        val dashOnPx: Double = 8.0,
        val dashOffPx: Double = 6.0,
        val strokeWidthPx: Double = 1.1,
        val selectedStrokeWidthPx: Double = 1.8,
        val fillAlpha: Float = 0.045f,
        val strokeAlpha: Float = 0.22f,
    )

    data class Chrome(
        val overlayPaddingPx: Double = 16.0,
        val panelRadiusPx: Double = 12.0,
        val borderWidthPx: Double = 1.0,
        val shellPaddingXPx: Double = 16.0,
        val shellPaddingYPx: Double = 12.0,
        val controlPaddingXPx: Double = 12.0,
        val controlPaddingYPx: Double = 8.0,
        val sectionGapPx: Double = 14.0,
        val itemGapPx: Double = 8.0,
        val microGapPx: Double = 4.0,
        val legendWidthPx: Double = 216.0,
        val indicatorSizePx: Double = 9.0,
        val miniMapWidthPx: Double = 176.0,
        val miniMapHeightPx: Double = 112.0,
        val miniMapInnerPaddingPx: Double = 10.0,
        val miniMapNodeMinSizePx: Double = 6.0,
        val miniMapNodeCornerPx: Double = 4.0,
        val miniMapViewportCornerPx: Double = 3.0,
        val miniMapViewportStrokePx: Double = 1.3,
        val miniMapEdgeStrokePx: Double = 1.0,
        val hiddenHandleSizePx: Double = 11.0,
        val editorPaddingPx: Double = 4.0,
        val titleFontPx: Double = 32.0,
        val bodyFontPx: Double = 24.0,
        val captionFontPx: Double = 20.0,
    )

    data class Viewport(
        val startupFrameDelayMillis: Long = 120L,
        val readablePaddingXPx: Double = 18.0,
        val readablePaddingYPx: Double = 18.0,
        val fitPaddingXPx: Double = 26.0,
        val fitPaddingYPx: Double = 26.0,
        val readableZoomBias: Double = 1.04,
        val readableMinZoom: Double = 0.68,
        val minZoom: Double = 0.22,
        val maxZoom: Double = 2.4,
        val zoomStep: Double = 1.12,
    )

    data class WidthPolicy(
        val dualColumnFactor: Double = 1.10,
        val routeFactor: Double = 1.24,
        val controllerFactor: Double = 1.18,
        val containerFactor: Double = 1.16,
        val basicFactor: Double = 1.10,
        val defaultFactor: Double = 1.12,
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
