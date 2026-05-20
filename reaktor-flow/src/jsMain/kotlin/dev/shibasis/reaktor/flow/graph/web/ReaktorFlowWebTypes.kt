package dev.shibasis.reaktor.flow.graph.web

external interface JsReaktorPortData {
    var handleId: String
    var label: String
    var type: String
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

external interface JsReaktorFlowData {
    var nodes: Array<dynamic>
    var edges: Array<dynamic>
    var regions: Array<JsReaktorRegion>
}
