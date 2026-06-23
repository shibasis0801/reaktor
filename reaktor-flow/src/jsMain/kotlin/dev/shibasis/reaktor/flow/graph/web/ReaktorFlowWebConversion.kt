package dev.shibasis.reaktor.flow.graph.web

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.shibasis.composeflow.react.toReactFlow
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorEdgeKind
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphEdgeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphRegion
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import dev.shibasis.reaktor.flow.graph.model.ReaktorPortData
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import kotlin.math.max
import kotlin.math.min

private fun Color.toHex(): String {
    val argb = toArgb()
    return "#${(argb and 0xFFFFFF).toString(16).padStart(6, '0')}"
}

private inline fun <T : Any> jso(builder: T.() -> Unit): T =
    (js("({})") as T).apply(builder)

fun ReaktorPortData.toJs(role: String = type): JsReaktorPortData = jso {
    handleId = this@toJs.handleId
    label = this@toJs.label
    type = role
    contractType = this@toJs.type
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
    providerPorts = this@toJs.providerPorts.map { it.toJs("provider") }.toTypedArray()
    consumerPorts = this@toJs.consumerPorts.map { it.toJs("consumer") }.toTypedArray()
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

fun ReaktorGraphStyle.Node.toJs(): JsReaktorNodeVisuals = jso {
    minWidth = this@toJs.minWidthPx
    titleHeight = this@toJs.titleHeightPx
    cornerRadius = this@toJs.cornerRadiusPx
    verticalPadding = this@toJs.verticalPaddingPx
    titlePaddingX = this@toJs.titlePaddingXPx
    titlePaddingY = this@toJs.titlePaddingYPx
    titleToBadgeGap = this@toJs.titleToBadgeGapPx
    bodyPaddingX = this@toJs.bodyPaddingXPx
    titleFont = this@toJs.titleFontPx
    rootBadgeFont = this@toJs.rootBadgeFontPx
    rootBadgePaddingX = this@toJs.rootBadgePaddingXPx
    rootBadgePaddingY = this@toJs.rootBadgePaddingYPx
}

fun ReaktorGraphStyle.Port.toJs(): JsReaktorPortVisuals = jso {
    rowHeight = this@toJs.rowHeightPx
    columnGap = this@toJs.columnGapPx
    gap = this@toJs.gapPx
    dotSize = this@toJs.dotSizePx
    font = this@toJs.fontPx
    inset = this@toJs.insetPx
    previewRows = this@toJs.previewRows
}

fun ReaktorGraphStyle.Region.toJs(): JsReaktorRegionVisuals = jso {
    labelPaddingX = this@toJs.labelPaddingXPx
    labelPaddingY = this@toJs.labelPaddingYPx
    labelRadius = this@toJs.labelRadiusPx
    cornerRadius = this@toJs.cornerRadiusPx
}

fun ReaktorGraphStyle.Chrome.toJs(): JsReaktorChromeVisuals = jso {
    panelRadius = this@toJs.panelRadiusPx
    borderWidth = this@toJs.borderWidthPx
    hiddenHandleSize = this@toJs.hiddenHandleSizePx
    bodyFont = this@toJs.bodyFontPx
    captionFont = this@toJs.captionFontPx
}

fun ReaktorGraphStyle.Viewport.toJs(): JsReaktorViewportVisuals = jso {
    minZoom = this@toJs.minZoom
    maxZoom = this@toJs.maxZoom
    readableMinZoom = this@toJs.readableMinZoom
    readableZoomBias = this@toJs.readableZoomBias
}

fun ReaktorGraphStyle.toJs(): JsReaktorGraphVisuals = jso {
    node = this@toJs.node.toJs()
    port = this@toJs.port.toJs()
    region = this@toJs.region.toJs()
    chrome = this@toJs.chrome.toJs()
    viewport = this@toJs.viewport.toJs()
}

fun ReaktorFlowGraph.toReactFlowData(): JsReaktorFlowData = jso {
    nodes = this@toReactFlowData.nodes.map { node ->
        val jsNode = node.toReactFlow()
        val nodeData = node.data
        if (nodeData is ReaktorGraphNodeData) {
            val data = nodeData.toJs()
            data.asDynamic().entityId = node.id
            jsNode.asDynamic().data = data
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
    visuals = this@toReactFlowData.style.toJs()
    source = "Reaktor graph"
    totalNodes = this@toReactFlowData.nodes.size
    totalEdges = this@toReactFlowData.edges.size
    fitNodeIds = emptyArray()
}

private fun ReaktorNodeKind.colorsToJs(): dynamic = jso<dynamic> {
    title = this@colorsToJs.titleColor.toHex()
    body = this@colorsToJs.bodyColor.toHex()
    border = this@colorsToJs.borderColor.toHex()
}

private fun ReaktorEdgeKind.filterType(): String = when (this) {
    ReaktorEdgeKind.Attachment -> "route"
    ReaktorEdgeKind.Navigation -> "route"
    ReaktorEdgeKind.Data -> "consumes"
    ReaktorEdgeKind.Containment -> "parent"
}

private fun ReaktorEdgeKind.edgeColor(): String = color.toHex()

private fun ReaktorEdgeKind.edgeStrokeWidth(): Double = when (this) {
    ReaktorEdgeKind.Navigation -> 2.2
    ReaktorEdgeKind.Attachment -> 1.8
    ReaktorEdgeKind.Data -> 1.5
    ReaktorEdgeKind.Containment -> 1.4
}

private fun ReaktorEdgeKind.edgeOpacity(): Double = when (this) {
    ReaktorEdgeKind.Navigation -> 0.9
    ReaktorEdgeKind.Attachment -> 0.76
    ReaktorEdgeKind.Data -> 0.62
    ReaktorEdgeKind.Containment -> 0.45
}

private fun visibleFocusIds(
    focus: String,
    selectedId: String?,
    nodes: List<dynamic>,
    edges: List<dynamic>,
): Set<String>? {
    if (focus == "none" || selectedId.isNullOrBlank()) return null
    val nodeIds = nodes.mapNotNull { it.id as? String }.toSet()
    if (selectedId !in nodeIds) return null

    val visible = linkedSetOf(selectedId)
    val rounds = when (focus) {
        "selected" -> 1
        "trace" -> 2
        "blast" -> 4
        "tests" -> 2
        else -> 0
    }

    repeat(rounds) {
        val before = visible.size
        edges.forEach { edge ->
            val source = edge.source as? String
            val target = edge.target as? String
            if (source != null && target != null) {
                if (source in visible) visible.add(target)
                if (target in visible) visible.add(source)
            }
        }
        if (visible.size == before) return@repeat
    }
    return visible
}

private fun fitNodeIds(nodes: List<dynamic>): Array<String> {
    val graphNodes = nodes.filter { it.type == "reaktorNode" }
    val readableCount = graphNodes.size
        .coerceAtMost(42)
        .coerceAtLeast(graphNodes.size.coerceAtMost(12))

    return graphNodes
        .sortedWith(compareBy<dynamic>({ (it.position?.x as? Number)?.toDouble() ?: 0.0 }, { (it.position?.y as? Number)?.toDouble() ?: 0.0 }))
        .take(readableCount)
        .mapNotNull { it.id as? String }
        .toTypedArray()
}

private fun graphNodePorts(nodes: List<dynamic>): Map<String, Pair<Set<String>, Set<String>>> =
    nodes.mapNotNull { node ->
        val id = node.id as? String ?: return@mapNotNull null
        val data = node.data ?: return@mapNotNull null
        val providers = (data.providerPorts as? Array<dynamic>).orEmpty()
            .mapNotNull { it.handleId as? String }
            .toSet()
        val consumers = (data.consumerPorts as? Array<dynamic>).orEmpty()
            .mapNotNull { it.handleId as? String }
            .toSet()
        id to (providers to consumers)
    }.toMap()

fun ReaktorFlowGraph.toReactFlowViewData(
    selectedId: String? = null,
    focus: String = "none",
    edgeFilters: Set<String> = setOf("route", "action", "parent", "consumes", "invokes", "writes", "emits", "deploys"),
    sourceLabel: String = "Reaktor graph",
): JsReaktorFlowData {
    val visuals = style.toJs()
    val graphNodes = nodes.map { node ->
        val jsNode = node.toReactFlow()
        val nodeData = node.data
        if (nodeData is ReaktorGraphNodeData) {
            val data = nodeData.toJs()
            data.asDynamic().entityId = node.id
            data.asDynamic().visuals = visuals
            data.asDynamic().colors = nodeData.kind.colorsToJs()
            data.asDynamic().width = node.width ?: style.node.minWidthPx
            val visiblePortRows = min(
                max(nodeData.providerPorts.size, nodeData.consumerPorts.size).coerceAtLeast(1),
                style.port.previewRows.coerceAtLeast(1),
            )
            data.asDynamic().height = node.height ?: (
                style.node.titleHeightPx +
                    visiblePortRows * style.port.rowHeightPx +
                    style.node.verticalPaddingPx * 2.0
                )
            jsNode.asDynamic().type = "reaktorNode"
            jsNode.asDynamic().data = data
        }
        val width = node.width ?: style.node.minWidthPx
        val height = node.height ?: style.node.titleHeightPx + style.port.rowHeightPx + style.node.verticalPaddingPx * 2.0
        jsNode.asDynamic().width = width
        jsNode.asDynamic().height = height
        jsNode.asDynamic().style = jso<dynamic> {
            this.width = width
            this.height = height
        }
        jsNode.asDynamic().selected = node.id == selectedId
        jsNode
    }

    val regionNodes = regions.map { region ->
        jso<dynamic> {
            id = "region-${region.id}"
            type = "regionNode"
            position = jso<dynamic> {
                x = region.x
                y = region.y - 20.0
            }
            width = region.width
            height = region.height + 20.0
            selectable = false
            draggable = false
            connectable = false
            focusable = false
            zIndex = -1
            data = jso<dynamic> {
                label = region.label
                width = region.width
                height = region.height + 20.0
                color = region.color.toHex()
                this.visuals = visuals
            }
        }
    }

    val nodePorts = graphNodePorts(graphNodes)
    val convertedEdges = edges.mapNotNull { edge ->
        val edgeData = edge.data as? ReaktorGraphEdgeData
        val kind = edgeData?.kind ?: ReaktorEdgeKind.Data
        if (kind.filterType() !in edgeFilters) return@mapNotNull null

        val jsEdge = edge.toReactFlow()
        val sourcePorts = nodePorts[edge.source]?.first.orEmpty()
        val targetPorts = nodePorts[edge.target]?.second.orEmpty()
        jsEdge.asDynamic().sourceHandle = edge.sourceHandle?.takeIf { it in sourcePorts }
        jsEdge.asDynamic().targetHandle = edge.targetHandle?.takeIf { it in targetPorts }
        jsEdge.asDynamic().data = (edgeData ?: ReaktorGraphEdgeData(kind)).toJs().also {
            it.asDynamic().filterType = kind.filterType()
        }
        jsEdge.asDynamic().style = jso<dynamic> {
            stroke = kind.edgeColor()
            strokeWidth = kind.edgeStrokeWidth()
            opacity = kind.edgeOpacity()
        }
        jsEdge
    }

    val focusIds = visibleFocusIds(focus, selectedId, graphNodes, convertedEdges)
    if (focusIds != null) {
        graphNodes.forEach { node ->
            node.data?.let { data ->
                data.asDynamic().dim = (node.id as? String) !in focusIds
            }
        }
        convertedEdges.forEach { edge ->
            val source = edge.source as? String
            val target = edge.target as? String
            val dim = source !in focusIds || target !in focusIds
            val style = edge.asDynamic().style ?: jso<dynamic> {}
            style.opacity = if (dim) 0.18 else style.opacity
            edge.asDynamic().style = style
        }
    }

    val allNodes = regionNodes + graphNodes
    return jso {
        this.nodes = allNodes.toTypedArray()
        this.edges = convertedEdges.toTypedArray()
        this.regions = this@toReactFlowViewData.regions.map { it.toJs() }.toTypedArray()
        this.visuals = visuals
        this.source = sourceLabel
        this.totalNodes = this@toReactFlowViewData.nodes.size
        this.totalEdges = this@toReactFlowViewData.edges.size
        this.fitNodeIds = fitNodeIds(allNodes)
    }
}
