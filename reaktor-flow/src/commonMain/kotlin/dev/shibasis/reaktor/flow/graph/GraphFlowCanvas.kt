package dev.shibasis.reaktor.flow.graph

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import dev.shibasis.reaktor.flow.BackgroundVariant
import dev.shibasis.reaktor.flow.Edge
import dev.shibasis.reaktor.flow.Handle
import dev.shibasis.reaktor.flow.HandleType
import dev.shibasis.reaktor.flow.Node
import dev.shibasis.reaktor.flow.PanelPosition
import dev.shibasis.reaktor.flow.Viewport
import dev.shibasis.reaktor.flow.XYPosition
import dev.shibasis.reaktor.flow.compose.EdgeRenderStyle
import dev.shibasis.reaktor.flow.compose.EdgePathStyle
import dev.shibasis.reaktor.flow.compose.HandleRenderStyle
import dev.shibasis.reaktor.flow.compose.NodeProps
import dev.shibasis.reaktor.flow.compose.NodeRenderStyle
import dev.shibasis.reaktor.flow.compose.NodeTypes
import dev.shibasis.reaktor.flow.compose.Panel
import dev.shibasis.reaktor.flow.compose.ReactFlow
import dev.shibasis.reaktor.flow.compose.ReactFlowProvider
import dev.shibasis.reaktor.flow.compose.ReactFlowState
import dev.shibasis.reaktor.flow.compose.rememberEdgesState
import dev.shibasis.reaktor.flow.compose.rememberNodesState
import dev.shibasis.reaktor.flow.compose.rememberReactFlowState
import dev.shibasis.reaktor.graph.core.node.Node as GraphNode
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val GraphCanvasChrome = Color(0xCC1C2338)
private val GraphCanvasBorder = Color(0xFF2F3A56)
private val GraphCanvasMuted = Color(0xFF93A0BB)
private val GraphCanvasText = Color(0xFFF1F5FF)
private val GraphCanvasSelected = Color(0xFFE8A33D)
private val GraphNodeTitleHeight = 40.dp
private val GraphNodeRowHeight = 50.dp
private val GraphNodeBodyPadding = 22.dp

@Composable
fun ReaktorGraphCanvas(
    flow: ReaktorFlowGraph,
    selectedNode: GraphNode?,
    selectedGraphId: String?,
    highlightedKind: ReaktorNodeKind?,
    onSelectNode: (GraphNode?) -> Unit,
    onSelectGraph: (String?) -> Unit,
    onHighlightKind: (ReaktorNodeKind?) -> Unit,
    onPaneClick: (() -> Unit)? = null,
    rightInset: Dp = 0.dp,
    state: ReactFlowState? = null,
    modifier: Modifier = Modifier,
) {
    val defaultNodeWidth = 480.dp
    val defaultNodeHeight = 304.dp
    val density = LocalDensity.current
    val rightInsetPx = with(density) { rightInset.toPx() }
    val defaultNodeWidthPx = with(density) { defaultNodeWidth.toPx().toDouble() }
    val defaultNodeHeightPx = with(density) { defaultNodeHeight.toPx().toDouble() }
    val selectedFlowId = remember(flow, selectedNode) { selectedNode?.let(flow.flowIdsByNode::get) }
    val reactFlowState = state ?: rememberReactFlowState()
    val nodesState = rememberNodesState(flow.nodes)
    val edgesState = rememberEdgesState(flow.edges)
    var hasFramedGraph by remember(flow) { mutableStateOf(false) }
    val nodeKinds = remember(flow) {
        flow.nodes.associate { node ->
            node.id to ((node.data as? ReaktorGraphNodeData)?.kind ?: ReaktorNodeKind.Node)
        }
    }
    val nodeTypes: NodeTypes = remember {
        mapOf(
            "graph" to { props ->
                ReaktorGraphNodeCard(props)
            }
        )
    }

    LaunchedEffect(flow) {
        nodesState.replaceNodes(mergeGraphNodes(nodesState.nodes, flow.nodes, selectedFlowId))
        edgesState.replaceEdges(flow.edges)
        hasFramedGraph = false
    }

    LaunchedEffect(selectedFlowId) {
        nodesState.updateNodes { nodes ->
            nodes.map { node -> node.copy(selected = node.id == selectedFlowId) }
        }
    }

    LaunchedEffect(flow, reactFlowState.canvasSize, hasFramedGraph) {
        if (hasFramedGraph || reactFlowState.canvasSize.width <= 0 || reactFlowState.canvasSize.height <= 0) {
            return@LaunchedEffect
        }
        delay(180)
        if (reactFlowState.canvasSize.width <= 0 || reactFlowState.canvasSize.height <= 0) {
            return@LaunchedEffect
        }
        frameGraph(
            state = reactFlowState,
            flow = flow,
            defaultNodeWidthPx = defaultNodeWidthPx,
            defaultNodeHeightPx = defaultNodeHeightPx,
            rightInsetPx = rightInsetPx,
            readable = true,
        )
        hasFramedGraph = true
    }

    ReactFlowProvider(state = reactFlowState) {
        ReactFlow(
            nodes = nodesState.nodes,
            edges = edgesState.edges,
            modifier = modifier,
            state = reactFlowState,
            nodeTypes = nodeTypes,
            onNodesChange = nodesState::onNodesChange,
            onNodeClick = { node -> onSelectNode(flow.graphNodes[node.id]) },
            fitView = false,
            showControls = false,
            showMiniMap = false,
            showBackground = true,
            backgroundVariant = BackgroundVariant.Cross,
            minZoom = 0.5,
            maxZoom = 2.5,
            defaultNodeWidth = defaultNodeWidth,
            defaultNodeHeight = defaultNodeHeight,
            nodeRenderStyle = { node -> graphNodeRenderStyle(node, highlightedKind) },
            edgeRenderStyle = { edge -> graphEdgeRenderStyle(edge, nodeKinds, highlightedKind) },
            edgePathStyle = EdgePathStyle.Bezier,
            handleRenderStyle = { node, handle -> graphHandleRenderStyle(node, handle, highlightedKind) },
            onPaneClick = onPaneClick,
            overlay = { state ->
                GraphViewportToolbar(
                    flow = flow,
                    state = state,
                    onZoomIn = {
                        reactFlowState.zoomBy(
                            factor = 1.12,
                            anchorX = reactFlowState.canvasSize.width / 2.0,
                            anchorY = reactFlowState.canvasSize.height / 2.0,
                            minZoom = 0.15,
                            maxZoom = 2.4,
                        )
                    },
                    onZoomOut = {
                        reactFlowState.zoomBy(
                            factor = 1.0 / 1.12,
                            anchorX = reactFlowState.canvasSize.width / 2.0,
                            anchorY = reactFlowState.canvasSize.height / 2.0,
                            minZoom = 0.15,
                            maxZoom = 2.4,
                        )
                    },
                    onFitView = {
                        frameGraph(
                            state = reactFlowState,
                            flow = flow,
                            defaultNodeWidthPx = defaultNodeWidthPx,
                            defaultNodeHeightPx = defaultNodeHeightPx,
                            rightInsetPx = rightInsetPx,
                            readable = false,
                        )
                    },
                    onResetZoom = {
                        reactFlowState.zoomTo(
                            zoom = 1.0,
                            anchorX = reactFlowState.canvasSize.width / 2.0,
                            anchorY = reactFlowState.canvasSize.height / 2.0,
                            minZoom = 0.15,
                            maxZoom = 2.4,
                        )
                    },
                )
                GraphKindLegend(
                    highlightedKind = highlightedKind,
                    flow = flow,
                    onHighlightKind = onHighlightKind,
                )
                GraphMiniMap(
                    flow = flow,
                    state = state,
                    rightInset = rightInset,
                )
            },
            viewportOverlay = {
                GraphRegionsOverlay(
                    flow = flow,
                    selectedGraphId = selectedGraphId,
                    onSelectGraph = onSelectGraph,
                )
            },
        )
    }
}

private fun frameGraph(
    state: ReactFlowState,
    flow: ReaktorFlowGraph,
    defaultNodeWidthPx: Double,
    defaultNodeHeightPx: Double,
    rightInsetPx: Float = 0f,
    readable: Boolean = false,
) {
    if (state.canvasSize.width <= 0 || state.canvasSize.height <= 0 || flow.nodes.isEmpty()) {
        return
    }

    val bounds = readableFlowBounds(flow, defaultNodeWidthPx, defaultNodeHeightPx)
    val viewportWidth = (state.canvasSize.width - rightInsetPx).coerceAtLeast(1f).toDouble()
    val viewportHeight = state.canvasSize.height.toDouble()
    val horizontalPadding = if (readable) 10.0 else 18.0
    val verticalPadding = if (readable) 10.0 else 18.0
    val availableWidth = (viewportWidth - horizontalPadding * 2.0).coerceAtLeast(1.0)
    val availableHeight = (viewportHeight - verticalPadding * 2.0).coerceAtLeast(1.0)
    val contentWidth = bounds.width.coerceAtLeast(defaultNodeWidthPx)
    val contentHeight = bounds.height.coerceAtLeast(defaultNodeHeightPx)
    val fitZoom = min(
        availableWidth / contentWidth,
        availableHeight / contentHeight,
    )
    val zoom = if (readable) {
        (fitZoom * 1.18).coerceIn(0.72, 1.2)
    } else {
        (fitZoom * 1.02).coerceIn(0.2, 1.2)
    }

    val offsetX = horizontalPadding - bounds.left * zoom
    val offsetY = verticalPadding - bounds.top * zoom

    state.setViewport(
        Viewport(
            x = offsetX,
            y = offsetY,
            zoom = zoom,
        ),
    )
}

private fun mergeGraphNodes(
    existing: List<Node>,
    incoming: List<Node>,
    selectedFlowId: String?,
): List<Node> {
    if (existing.isEmpty()) {
        return incoming.map { it.copy(selected = it.id == selectedFlowId) }
    }

    val existingById = existing.associateBy(Node::id)
    return incoming.map { next ->
        val current = existingById[next.id]
        if (current == null) {
            next.copy(selected = next.id == selectedFlowId)
        } else {
            next.copy(
                position = current.position,
                measured = current.measured,
                selected = next.id == selectedFlowId,
                dragging = current.dragging,
            )
        }
    }
}

private fun graphNodeRenderStyle(
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

private fun graphEdgeRenderStyle(
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

private fun graphHandleRenderStyle(
    node: Node,
    handle: Handle,
    highlightedKind: ReaktorNodeKind?,
): HandleRenderStyle {
    val data = node.data as? ReaktorGraphNodeData ?: return HandleRenderStyle()
    val matchesKind = highlightedKind == null || data.kind == highlightedKind
    val color = when (handle.id) {
        "__nav__", "navBinding", "routeBinding" -> Color(0xFF55A8F4)
        "__contains__" -> Color(0xFFC38CFF)
        else -> when (handle.type) {
            HandleType.Source -> data.providerPorts.firstOrNull { it.handleId == handle.id }?.color
            HandleType.Target -> data.consumerPorts.firstOrNull { it.handleId == handle.id }?.color
        } ?: Color(0xFF7C8AA6)
    }
    return HandleRenderStyle(
        fillColor = color,
        borderColor = Color(0xFF09101D),
        alpha = 0f,
        size = 11.dp,
    )
}

@Composable
private fun BoxScope.ReaktorGraphNodeCard(props: NodeProps) {
    val data = props.data as? ReaktorGraphNodeData ?: return
    val rowCount = max(1, max(data.consumerPorts.size, data.providerPorts.size))

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(data.kind.titleColor)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = data.title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp),
            )
            if (data.isRootNode) {
                Surface(
                    color = Color(0x332F8BFF),
                    shape = RoundedCornerShape(999.dp),
                    tonalElevation = 0.dp,
                ) {
                    Text(
                        text = "Root",
                        color = Color(0xFFC9D8FF),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            if (rowCount == 1 && data.consumerPorts.isEmpty() && data.providerPorts.isEmpty()) {
                data.subtitle?.takeIf(String::isNotBlank)?.let { subtitle ->
                    Text(
                        text = subtitle,
                        color = GraphCanvasMuted,
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                repeat(rowCount) { index ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        PortEntry(
                            port = data.consumerPorts.getOrNull(index),
                            alignRight = false,
                        )
                        Spacer(Modifier.width(24.dp))
                        PortEntry(
                            port = data.providerPorts.getOrNull(index),
                            alignRight = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PortEntry(
    port: ReaktorPortData?,
    alignRight: Boolean,
    modifier: Modifier = Modifier,
) {
    if (port == null) {
        Spacer(modifier)
        return
    }

    Row(
        modifier = modifier,
        horizontalArrangement = if (alignRight) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (alignRight) {
            Port(port)
            Spacer(Modifier.width(9.dp))
            PortDot(port)
        } else {
            PortDot(port)
            Spacer(Modifier.width(9.dp))
            Port(port)
        }
    }
}

@Composable
private fun RowScope.Port(port: ReaktorPortData) {
    Text(
        text = port.label,
        color = if (port.connected) GraphCanvasText else GraphCanvasMuted,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun RowScope.PortDot(port: ReaktorPortData) {
    Box(
        modifier = Modifier
            .offset(y = 4.dp)
            .size(14.dp)
            .background(port.color.copy(alpha = if (port.connected) 1f else 0.36f), CircleShape)
            .border(1.dp, port.color, CircleShape),
    )
}

@Composable
private fun BoxScope.GraphViewportToolbar(
    flow: ReaktorFlowGraph,
    state: ReactFlowState,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFitView: () -> Unit,
    onResetZoom: () -> Unit,
) {
    Panel(position = PanelPosition.BottomRight, modifier = Modifier.padding(12.dp)) {
        Surface(
            color = GraphCanvasChrome,
            shape = RoundedCornerShape(10.dp),
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Graph", color = GraphCanvasText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "${flow.nodes.size} nodes • ${flow.edges.size} edges",
                        color = GraphCanvasMuted,
                        fontSize = 13.sp,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolbarButton(label = "-", onClick = onZoomOut)
                    ToolbarButton(label = "+", onClick = onZoomIn)
                    ToolbarButton(label = "Fit", onClick = onFitView)
                    ToolbarButton(label = "100%", onClick = onResetZoom)
                    Surface(color = Color(0xFF11182A), shape = RoundedCornerShape(999.dp), tonalElevation = 0.dp) {
                        Text(
                            text = "${(state.viewport.zoom * 100).toInt()}%",
                            color = GraphCanvasText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.GraphKindLegend(
    flow: ReaktorFlowGraph,
    highlightedKind: ReaktorNodeKind?,
    onHighlightKind: (ReaktorNodeKind?) -> Unit,
) {
    val counts = remember(flow) {
        flow.nodes.groupingBy { (it.data as? ReaktorGraphNodeData)?.kind ?: ReaktorNodeKind.Node }.eachCount()
    }

    Panel(position = PanelPosition.BottomLeft, modifier = Modifier.padding(12.dp)) {
        Surface(
            color = GraphCanvasChrome,
            shape = RoundedCornerShape(10.dp),
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Node Types", color = GraphCanvasText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                ReaktorNodeKind.entries.forEach { kind ->
                    val selected = highlightedKind == kind
                    Row(
                        modifier = Modifier
                            .width(176.dp)
                            .background(
                                if (selected) kind.bodyColor.copy(alpha = 0.95f) else Color(0x5011182A),
                                RoundedCornerShape(10.dp),
                            )
                            .border(
                                width = 1.dp,
                                color = if (selected) kind.borderColor else GraphCanvasBorder,
                                shape = RoundedCornerShape(10.dp),
                            )
                            .clickable { onHighlightKind(if (selected) null else kind) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .background(kind.borderColor, CircleShape),
                            )
                            Text(kind.label, color = GraphCanvasText, fontSize = 13.sp)
                        }
                        Text(
                            text = (counts[kind] ?: 0).toString(),
                            color = if (selected) Color.White else GraphCanvasMuted,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.GraphMiniMap(
    flow: ReaktorFlowGraph,
    state: ReactFlowState,
    rightInset: Dp,
) {
    if (flow.nodes.isEmpty()) {
        return
    }

    val miniMapPadding = 10.dp
    val density = LocalDensity.current
    val miniMapPaddingPx = with(density) { miniMapPadding.toPx() }

    Panel(position = PanelPosition.TopRight, modifier = Modifier.padding(12.dp)) {
        Box(
            modifier = Modifier
                .padding(end = rightInset)
                .size(width = 140.dp, height = 88.dp),
        ) {
            Surface(
                color = Color(0xDD0D1020),
                shape = RoundedCornerShape(10.dp),
                tonalElevation = 0.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(flow, state.canvasSize, state.viewport, miniMapPaddingPx) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: continue
                                focusViewportFromMiniMap(
                                    pointer = down.position,
                                    boxSize = size,
                                    paddingPx = miniMapPaddingPx,
                                    bounds = flowBounds(flow),
                                    state = state,
                                )
                                down.consume()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        break
                                    }
                                    focusViewportFromMiniMap(
                                        pointer = change.position,
                                        boxSize = size,
                                        paddingPx = miniMapPaddingPx,
                                        bounds = flowBounds(flow),
                                        state = state,
                                    )
                                    change.consume()
                                }
                            }
                        }
                    },
            ) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(miniMapPadding)) {
                val bounds = flowBounds(flow)
                val scale = min(size.width / max(bounds.width.toFloat(), 1f), size.height / max(bounds.height.toFloat(), 1f))

                flow.edges.forEach { edge ->
                    val source = flow.nodes.firstOrNull { it.id == edge.source } ?: return@forEach
                    val target = flow.nodes.firstOrNull { it.id == edge.target } ?: return@forEach
                    drawLine(
                        color = ((edge.data as? ReaktorGraphEdgeData)?.kind?.color ?: Color(0xFF64748B)).copy(alpha = 0.35f),
                        start = miniMapPoint(source.position.x, source.position.y, bounds.left, bounds.top, scale),
                        end = miniMapPoint(target.position.x, target.position.y, bounds.left, bounds.top, scale),
                        strokeWidth = 1f,
                    )
                }

                flow.nodes.forEach { node ->
                    val width = ((node.width ?: 320.0) * scale).toFloat().coerceAtLeast(6f)
                    val height = ((node.height ?: 128.0) * scale).toFloat().coerceAtLeast(6f)
                    val origin = miniMapPoint(node.position.x, node.position.y, bounds.left, bounds.top, scale)
                    val kind = (node.data as? ReaktorGraphNodeData)?.kind ?: ReaktorNodeKind.Node
                    drawRoundRect(
                        color = kind.borderColor.copy(alpha = 0.65f),
                        topLeft = origin,
                        size = Size(width, height),
                        cornerRadius = CornerRadius(4f, 4f),
                    )
                }

                val viewportLeft = ((-state.viewport.x / state.viewport.zoom) - bounds.left) * scale
                val viewportTop = ((-state.viewport.y / state.viewport.zoom) - bounds.top) * scale
                val viewportWidth = (state.canvasSize.width / state.viewport.zoom * scale).toFloat()
                val viewportHeight = (state.canvasSize.height / state.viewport.zoom * scale).toFloat()
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.26f),
                    topLeft = Offset(viewportLeft.toFloat(), viewportTop.toFloat()),
                    size = Size(viewportWidth, viewportHeight),
                    cornerRadius = CornerRadius(3f, 3f),
                    style = Stroke(1.3f),
                )
            }
            }
        }
    }
}

@Composable
private fun BoxScope.GraphRegionsOverlay(
    flow: ReaktorFlowGraph,
    selectedGraphId: String?,
    onSelectGraph: (String?) -> Unit,
) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        flow.regions
            .sortedBy { it.depth }
            .forEach { region ->
                val topLeft = Offset(region.x.toFloat(), region.y.toFloat())
                val size = Size(region.width.toFloat(), region.height.toFloat())
                drawRoundRect(
                    color = region.color.copy(alpha = 0.045f),
                    topLeft = topLeft,
                    size = size,
                    cornerRadius = CornerRadius(14f, 14f),
                )
                drawRoundRect(
                    color = region.color.copy(alpha = 0.22f),
                    topLeft = topLeft,
                    size = size,
                    cornerRadius = CornerRadius(14f, 14f),
                    style = Stroke(
                        width = if (selectedGraphId == region.id) 1.8f else 1.1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)),
                    ),
                )
            }
    }
    flow.regions
        .sortedBy { it.depth }
        .forEach { region ->
            Surface(
                color = if (selectedGraphId == region.id) Color(0xCC162846) else Color(0x88121A2C),
                shape = RoundedCornerShape(7.dp),
                tonalElevation = 0.dp,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (region.x + 16.0).roundToInt(),
                            y = (region.y + 12.0).roundToInt(),
                        )
                    }
                    .clickable { onSelectGraph(region.id) },
            ) {
                Text(
                    text = region.label,
                    color = region.color.copy(alpha = if (selectedGraphId == region.id) 0.96f else 0.72f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
}

private data class FlowBounds(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
)

private fun flowBounds(flow: ReaktorFlowGraph): FlowBounds {
    val left = min(
        flow.nodes.minOfOrNull { it.position.x } ?: 0.0,
        flow.regions.minOfOrNull { it.x } ?: Double.POSITIVE_INFINITY,
    ).takeUnless(Double::isInfinite) ?: 0.0
    val top = min(
        flow.nodes.minOfOrNull { it.position.y } ?: 0.0,
        flow.regions.minOfOrNull { it.y } ?: Double.POSITIVE_INFINITY,
    ).takeUnless(Double::isInfinite) ?: 0.0
    val right = max(
        flow.nodes.maxOfOrNull { it.position.x + (it.width ?: 320.0) } ?: 1.0,
        flow.regions.maxOfOrNull { it.x + it.width } ?: 1.0,
    )
    val bottom = max(
        flow.nodes.maxOfOrNull { it.position.y + (it.height ?: 128.0) } ?: 1.0,
        flow.regions.maxOfOrNull { it.y + it.height } ?: 1.0,
    )
    return FlowBounds(left = left, top = top, width = right - left, height = bottom - top)
}

private fun readableFlowBounds(
    flow: ReaktorFlowGraph,
    defaultNodeWidthPx: Double,
    defaultNodeHeightPx: Double,
): FlowBounds {
    val nodeLeft = flow.nodes.minOfOrNull { it.position.x } ?: 0.0
    val nodeTop = flow.nodes.minOfOrNull { it.position.y } ?: 0.0
    val nodeRight = flow.nodes.maxOfOrNull { it.position.x + (it.width ?: defaultNodeWidthPx) } ?: defaultNodeWidthPx
    val nodeBottom = flow.nodes.maxOfOrNull { it.position.y + (it.height ?: defaultNodeHeightPx) } ?: defaultNodeHeightPx
    val paddingX = 28.0
    val paddingY = 24.0
    return FlowBounds(
        left = nodeLeft - paddingX,
        top = nodeTop - paddingY,
        width = (nodeRight - nodeLeft) + paddingX * 2.0,
        height = (nodeBottom - nodeTop) + paddingY * 2.0,
    )
}

private fun focusViewportFromMiniMap(
    pointer: Offset,
    boxSize: IntSize,
    paddingPx: Float,
    bounds: FlowBounds,
    state: ReactFlowState,
) {
    val contentWidth = (boxSize.width - paddingPx * 2f).coerceAtLeast(1f)
    val contentHeight = (boxSize.height - paddingPx * 2f).coerceAtLeast(1f)
    val scale = min(
        contentWidth / max(bounds.width.toFloat(), 1f),
        contentHeight / max(bounds.height.toFloat(), 1f),
    ).coerceAtLeast(0.0001f)
    val localX = (pointer.x - paddingPx).coerceIn(0f, contentWidth)
    val localY = (pointer.y - paddingPx).coerceIn(0f, contentHeight)
    state.centerOn(
        position = XYPosition(
            x = bounds.left + localX / scale,
            y = bounds.top + localY / scale,
        ),
        zoom = state.viewport.zoom,
    )
}

private fun miniMapPoint(
    x: Double,
    y: Double,
    left: Double,
    top: Double,
    scale: Float,
): Offset = Offset(
    x = ((x - left) * scale).toFloat(),
    y = ((y - top) * scale).toFloat(),
)

@Composable
private fun ToolbarButton(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        color = Color(0xFF11182A),
        shape = RoundedCornerShape(9.dp),
        tonalElevation = 0.dp,
    ) {
        Text(
            text = label,
            color = GraphCanvasText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
