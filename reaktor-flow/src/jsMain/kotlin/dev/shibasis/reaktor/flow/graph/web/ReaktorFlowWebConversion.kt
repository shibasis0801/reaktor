package dev.shibasis.reaktor.flow.graph.web

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.shibasis.composeflow.react.toReactFlow
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphEdgeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphRegion
import dev.shibasis.reaktor.flow.graph.model.ReaktorPortData

private fun Color.toHex(): String {
    val argb = toArgb()
    return "#${(argb and 0xFFFFFF).toString(16).padStart(6, '0')}"
}

private inline fun <T : Any> jso(builder: T.() -> Unit): T =
    (js("({})") as T).apply(builder)

fun ReaktorPortData.toJs(): JsReaktorPortData = jso {
    handleId = this@toJs.handleId
    label = this@toJs.label
    type = this@toJs.type
    color = this@toJs.color.toHex()
    connected = this@toJs.connected
}

fun ReaktorGraphNodeData.toJs(): JsReaktorNodeData = jso {
    nodeId = this@toJs.nodeId
    title = this@toJs.title
    subtitle = this@toJs.subtitle
    graphLabel = this@toJs.graphLabel
    isRootNode = this@toJs.isRootNode
    kind = this@toJs.kind.label
    providerPorts = this@toJs.providerPorts.map { it.toJs() }.toTypedArray()
    consumerPorts = this@toJs.consumerPorts.map { it.toJs() }.toTypedArray()
    providerCount = this@toJs.providerCount
    consumerCount = this@toJs.consumerCount
    hiddenProviderCount = this@toJs.hiddenProviderCount
    hiddenConsumerCount = this@toJs.hiddenConsumerCount
}

fun ReaktorGraphEdgeData.toJs(): JsReaktorEdgeData = jso {
    kind = this@toJs.kind.label
    label = this@toJs.label
}

fun ReaktorGraphRegion.toJs(): JsReaktorRegion = jso {
    id = this@toJs.id
    label = this@toJs.label
    x = this@toJs.x
    y = this@toJs.y
    width = this@toJs.width
    height = this@toJs.height
    color = this@toJs.color.toHex()
    depth = this@toJs.depth
}

fun ReaktorFlowGraph.toReactFlowData(): JsReaktorFlowData = jso {
    nodes = this@toReactFlowData.nodes.map { node ->
        val jsNode = node.toReactFlow()
        val nodeData = node.data
        if (nodeData is ReaktorGraphNodeData) {
            jsNode.asDynamic().data = nodeData.toJs()
        }
        jsNode
    }.toTypedArray()

    edges = this@toReactFlowData.edges.map { edge ->
        val jsEdge = edge.toReactFlow()
        val edgeData = edge.data
        if (edgeData is ReaktorGraphEdgeData) {
            jsEdge.asDynamic().data = edgeData.toJs()
        }
        jsEdge
    }.toTypedArray()

    regions = this@toReactFlowData.regions.map { it.toJs() }.toTypedArray()
}
