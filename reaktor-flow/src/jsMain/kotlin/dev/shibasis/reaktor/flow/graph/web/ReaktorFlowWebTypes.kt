package dev.shibasis.reaktor.flow.graph.web

external interface JsReaktorPortData {
    var handleId: String
    var label: String
    var type: String
    var contractType: String
    var color: String
    var connected: Boolean
}

external interface JsReaktorNodeData {
    var nodeId: String
    var title: String
    var subtitle: String?
    var graphLabel: String
    var isRootNode: Boolean
    var kind: String
    var providerPorts: Array<JsReaktorPortData>
    var consumerPorts: Array<JsReaktorPortData>
    var providerCount: Int
    var consumerCount: Int
    var hiddenProviderCount: Int
    var hiddenConsumerCount: Int
}

external interface JsReaktorEdgeData {
    var kind: String
    var label: String?
}

external interface JsReaktorRegion {
    var id: String
    var label: String
    var x: Double
    var y: Double
    var width: Double
    var height: Double
    var color: String
    var depth: Int
}

external interface JsReaktorNodeVisuals {
    var minWidth: Double
    var titleHeight: Double
    var cornerRadius: Double
    var verticalPadding: Double
    var titlePaddingX: Double
    var titlePaddingY: Double
    var titleToBadgeGap: Double
    var bodyPaddingX: Double
    var titleFont: Double
    var rootBadgeFont: Double
    var rootBadgePaddingX: Double
    var rootBadgePaddingY: Double
}

external interface JsReaktorPortVisuals {
    var rowHeight: Double
    var columnGap: Double
    var gap: Double
    var dotSize: Double
    var font: Double
    var inset: Double
    var previewRows: Int
}

external interface JsReaktorRegionVisuals {
    var labelPaddingX: Double
    var labelPaddingY: Double
    var labelRadius: Double
    var cornerRadius: Double
}

external interface JsReaktorChromeVisuals {
    var panelRadius: Double
    var borderWidth: Double
    var hiddenHandleSize: Double
    var bodyFont: Double
    var captionFont: Double
}

external interface JsReaktorViewportVisuals {
    var minZoom: Double
    var maxZoom: Double
    var readableMinZoom: Double
    var readableZoomBias: Double
}

external interface JsReaktorGraphVisuals {
    var node: JsReaktorNodeVisuals
    var port: JsReaktorPortVisuals
    var region: JsReaktorRegionVisuals
    var chrome: JsReaktorChromeVisuals
    var viewport: JsReaktorViewportVisuals
}

external interface JsReaktorFlowData {
    var nodes: Array<dynamic>
    var edges: Array<dynamic>
    var regions: Array<JsReaktorRegion>
    var visuals: JsReaktorGraphVisuals
    var source: String
    var totalNodes: Int
    var totalEdges: Int
    var fitNodeIds: Array<String>
}
