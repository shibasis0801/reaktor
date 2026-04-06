package dev.shibasis.reaktor.flow.graph.layout

// Layout metrics live in graph-space pixels, not dp. The Compose renderer converts them to dp
// only at the final render boundary so the measurement contract stays consistent with the editor
// viewport math and with React Flow's pixel-space layout model.
internal object GraphFlowMetrics {
    const val nodeMinWidth = 1200.0
    const val titleHeight = 144.0
    const val rowHeight = 120.0
    const val nodePaddingY = 48.0
    const val portInset = 18.0
    const val subgraphHeader = 60.0
    const val subgraphPadding = 60.0
    const val layerGap = 16.0
    const val attachmentGap = 12.0
    const val rowGap = 12.0
    const val attachmentRowGap = 8.0
    const val childGraphGap = 8.0
    const val routeColumnGap = 8.0
    const val serviceColumnGap = 8.0

    // Keep the render contract in graph-space pixels so layout and Compose rendering stay aligned.
    // The renderer converts these to dp/sp at the edge, similar to how React Flow measures in
    // editor-space and only applies browser/layout units at render time.
    const val nodeCornerRadius = 36.0
    const val titleHorizontalPadding = 72.0
    const val titleVerticalPadding = 30.0
    const val titleToBadgeGap = 32.0
    const val bodyHorizontalPadding = 52.0
    const val columnGap = 64.0
    const val portGap = 24.0
    const val portDotSize = 18.0
    const val titleFontSize = 48.0
    const val portFontSize = 32.0
    const val rootBadgeFontSize = 22.0
    const val rootBadgeHorizontalPadding = 16.0
    const val rootBadgeVerticalPadding = 8.0

    const val titleCharWidthEstimate = 28.0
    const val portCharWidthEstimate = 18.0
    const val regionInsetX = 28.0
    const val regionInsetTop = 18.0
    const val regionInsetBottom = 28.0
}
