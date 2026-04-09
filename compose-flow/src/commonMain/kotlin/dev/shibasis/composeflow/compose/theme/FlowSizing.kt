package dev.shibasis.composeflow.compose.theme

import androidx.compose.ui.unit.dp

// References:
// - "Thinking in Compose" for state/event separation
// - xyflow/react viewport helpers for wheel-to-zoom behavior
internal object FlowSizing {
    val defaultNodeWidth = 180.dp
    val defaultNodeHeight = 96.dp
    val nodeCornerRadius = 16.dp
    val nodeBorderWidth = 1.dp
    val nodePadding = 12.dp
    val defaultLabelSpacing = 6.dp

    val handleBorderWidth = 2.dp

    val controlsPadding = 12.dp
    val controlsContainerRadius = 12.dp
    val controlButtonRadius = 8.dp
    val controlButtonHorizontalPadding = 10.dp
    val controlButtonVerticalPadding = 6.dp
    val controlButtonSpacing = 2.dp
    val controlGroupPadding = 8.dp
    val viewportLabelSpacing = 8.dp
    val controlsElevation = 2.dp

    val minimapPadding = 8.dp
    val minimapWidth = 144.dp
    val minimapHeight = 92.dp
    val minimapRadius = 12.dp
    val minimapInnerPadding = 8.dp
    val minimapElevation = 2.dp
    const val minimapDefaultNodeWidthPx = 180.0
    const val minimapDefaultNodeHeightPx = 96.0

    const val minimapEdgeStrokePx = 1f
    const val minimapNodeMinSizePx = 8f
    const val minimapNodeCornerPx = 6f
    const val defaultEdgeStrokePx = 2f
    const val animatedEdgeStrokePx = 3f
    const val selectedEdgeStrokePx = 3f
    const val bezierControlRatio = 0.34f
    const val bezierControlBiasPx = 34f
    const val bezierMinControlPx = 22f
    const val bezierMaxControlPx = 144f
    const val bezierAxisAlignedThresholdPx = 72f
    const val bezierAxisAlignedFactor = 0.58f
    const val bezierVerticalCollinearThresholdPx = 56f
    const val bezierVerticalCollinearBiasPx = 34f
    const val defaultMarkerWidthPx = 12.0
    const val markerHalfAngleRadians = kotlin.math.PI / 7.0

    const val dotGridStepPx = 28f
    const val lineGridStepPx = 36f
    const val crossGridStepPx = 32f
    const val crossGridMajorMultiplier = 5f
    const val dotGridRadiusPx = 1.2f
    const val minorGridStrokePx = 1f
    const val majorGridStrokePx = 1.3f

    const val nodeDragThresholdSquared = 9f
    const val wheelPanFactor = 5.0
    const val wheelZoomSensitivity = 0.06
    const val wheelZoomFactorMin = 0.88
    const val wheelZoomFactorMax = 1.16
    const val pinchZoomSensitivity = 2.0
    const val controlZoomFactor = 1.15
}
