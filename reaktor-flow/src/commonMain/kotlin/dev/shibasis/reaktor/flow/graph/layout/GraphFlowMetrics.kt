package dev.shibasis.reaktor.flow.graph.layout

// Layout metrics live in graph-space pixels, not dp. Compose only converts them at the final
// render boundary so measurement, handles, regions, and viewport math all speak one coordinate
// system.
//
// References:
// - "Thinking in Compose" and the official custom layout docs: keep measurement explicit and
//   stable instead of spreading layout intent through modifiers.
// - xyflow/React Flow source: node sizing, handle anchors, and fit-view math operate in editor
//   space before browser/render units are applied.
internal data class GraphFlowMetrics(
    val rootOrigin: Double = 80.0,
    val nodeMinWidth: Double = 1200.0,
    val titleHeight: Double = 144.0,
    val rowHeight: Double = 120.0,
    val nodePaddingY: Double = 48.0,
    val subgraphInset: Double = 60.0,
    val gap: Double = 12.0,
    val portInset: Double = 18.0,
    val navigationHandleOffset: Double = 0.5,
    val containmentHandleOffset: Double = 0.84,
    val regionInsetX: Double = 28.0,
    val defaultPreviewRows: Int = 4,
) {
    val compactGap: Double get() = gap * (2.0 / 3.0)
    val majorGap: Double get() = gap * (4.0 / 3.0)
    val nodeCornerRadius: Double get() = titleHeight / 4.0
    val titlePaddingX: Double get() = titleHeight / 2.0
    val titlePaddingY: Double get() = rowHeight / 4.0
    val titleToBadgeGap: Double get() = gap * (8.0 / 3.0)
    val bodyPaddingX: Double get() = rowHeight * 0.43333333333333335
    val columnGap: Double get() = rowHeight * 0.5333333333333333
    val portGap: Double get() = rowHeight * 0.2
    val portDotSize: Double get() = rowHeight * 0.15
    val titleFontSize: Double get() = rowHeight / 3.0
    val portFontSize: Double get() = titleFontSize
    val rootBadgeFontSize: Double get() = titleFontSize * 0.55
    val rootBadgePaddingX: Double get() = portGap * (2.0 / 3.0)
    val rootBadgePaddingY: Double get() = gap * (2.0 / 3.0)
    val titleCharWidthEstimate: Double get() = titleFontSize * 0.5833333333333334
    val portCharWidthEstimate: Double get() = portFontSize * 0.5625
    val regionInsetTop: Double get() = portGap * 0.75
    val regionInsetBottom: Double get() = regionInsetX
    val defaultNodeWidth: Double get() = nodeMinWidth
    val defaultNodeHeight: Double get() = titleHeight + (rowHeight * defaultPreviewRows) + (nodePaddingY * 2.0)
}

internal val DefaultGraphFlowMetrics = GraphFlowMetrics()
