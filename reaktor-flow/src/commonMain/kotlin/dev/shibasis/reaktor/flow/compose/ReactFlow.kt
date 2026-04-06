package dev.shibasis.reaktor.flow.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.shibasis.reaktor.flow.BackgroundVariant
import dev.shibasis.reaktor.flow.Connection
import dev.shibasis.reaktor.flow.Edge
import dev.shibasis.reaktor.flow.EdgeMarker
import dev.shibasis.reaktor.flow.FitViewOptions
import dev.shibasis.reaktor.flow.Handle
import dev.shibasis.reaktor.flow.HandleType
import dev.shibasis.reaktor.flow.MarkerType
import dev.shibasis.reaktor.flow.Node
import dev.shibasis.reaktor.flow.NodeChange
import dev.shibasis.reaktor.flow.NodePositionChange
import dev.shibasis.reaktor.flow.PanelPosition
import dev.shibasis.reaktor.flow.Position
import dev.shibasis.reaktor.flow.Viewport
import dev.shibasis.reaktor.flow.XYPosition
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val FlowSurface = Color(0xFF0F172A)
private val FlowBorder = Color(0xFF334155)
private val FlowText = Color(0xFFE2E8F0)
private val FlowHandleSource = Color(0xFF3B82F6)
private val FlowHandleTarget = Color(0xFFFB923C)
private val FlowEdge = Color(0xFF64748B)

data class NodeProps(
    val id: String,
    val data: Any?,
    val selected: Boolean,
    val dragging: Boolean,
    val type: String?,
    val width: Double,
    val height: Double,
)

data class NodeRenderStyle(
    val alpha: Float = 1f,
    val scale: Float = 1f,
    val backgroundColor: Color? = null,
    val borderColor: Color? = null,
)

data class EdgeRenderStyle(
    val alpha: Float = 1f,
    val color: Color? = null,
    val width: Float? = null,
)

enum class EdgePathStyle {
    Bezier,
    Orthogonal,
}

data class HandleRenderStyle(
    val fillColor: Color? = null,
    val borderColor: Color? = null,
    val alpha: Float = 1f,
    val size: Dp = 12.dp,
)

private data class FlowAnchor(
    val point: Offset,
    val position: Position,
)

typealias NodeContent = @Composable BoxScope.(NodeProps) -> Unit

typealias NodeTypes = Map<String, NodeContent>

@Composable
fun ReactFlow(
    nodes: List<Node>,
    edges: List<Edge>,
    modifier: Modifier = Modifier,
    state: ReactFlowState = LocalReactFlowState.current ?: rememberReactFlowState(),
    nodeTypes: NodeTypes = emptyMap(),
    onNodesChange: ((List<NodeChange>) -> Unit)? = null,
    onConnect: ((Connection) -> Unit)? = null,
    onNodeClick: ((Node) -> Unit)? = null,
    fitView: Boolean = true,
    fitViewOptions: FitViewOptions = FitViewOptions(),
    showBackground: Boolean = true,
    backgroundVariant: BackgroundVariant = BackgroundVariant.Dots,
    showControls: Boolean = true,
    showMiniMap: Boolean = false,
    minZoom: Double = 0.25,
    maxZoom: Double = 2.0,
    defaultNodeWidth: Dp = 180.dp,
    defaultNodeHeight: Dp = 96.dp,
    nodeRenderStyle: (Node) -> NodeRenderStyle = { NodeRenderStyle() },
    edgeRenderStyle: (Edge) -> EdgeRenderStyle = { EdgeRenderStyle() },
    edgePathStyle: EdgePathStyle = EdgePathStyle.Bezier,
    handleRenderStyle: (Node, Handle) -> HandleRenderStyle = { _, _ -> HandleRenderStyle() },
    onPaneClick: (() -> Unit)? = null,
    overlay: @Composable BoxScope.(ReactFlowState) -> Unit = {},
    viewportOverlay: @Composable BoxScope.(ReactFlowState) -> Unit = {},
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var userModifiedViewport by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    val defaultWidthPx = with(density) { defaultNodeWidth.toPx().toDouble() }
    val defaultHeightPx = with(density) { defaultNodeHeight.toPx().toDouble() }

    LaunchedEffect(nodes, canvasSize, fitView, fitViewOptions, userModifiedViewport) {
        if (fitView && canvasSize.width > 0 && canvasSize.height > 0 && nodes.isNotEmpty() && !userModifiedViewport) {
            state.fitView(
                nodes = nodes,
                defaultNodeWidth = defaultWidthPx,
                defaultNodeHeight = defaultHeightPx,
                options = fitViewOptions,
            )
        }
    }

    LaunchedEffect(nodes, edges) {
        state.selectedNodeIds = nodes.filter(Node::selected).mapTo(linkedSetOf(), Node::id)
        state.selectedEdgeIds = edges.filter(Edge::selected).mapTo(linkedSetOf(), Edge::id)
    }
    CompositionLocalProvider(LocalReactFlowState provides state) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF020617))
                .onSizeChanged {
                    canvasSize = it
                    state.canvasSize = it
                }
                .pointerInput(minZoom, maxZoom) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) {
                                continue
                            }

                            val change = event.changes.firstOrNull() ?: continue
                            val scrollDelta = change.scrollDelta
                            val isZoomGesture = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                            userModifiedViewport = true
                            if (isZoomGesture) {
                                val factor = exp((-scrollDelta.y * 0.06).toDouble()).coerceIn(0.88, 1.16)
                                state.zoomBy(
                                    factor = factor,
                                    anchorX = change.position.x.toDouble(),
                                    anchorY = change.position.y.toDouble(),
                                    minZoom = minZoom,
                                    maxZoom = maxZoom,
                                )
                            } else {
                                state.panBy(
                                    dx = -scrollDelta.x.toDouble(),
                                    dy = -scrollDelta.y.toDouble(),
                                )
                            }
                            change.consume()
                        }
                    }
                }
        ) {
            if (showBackground) {
                FlowBackground(
                    modifier = Modifier.fillMaxSize(),
                    viewport = state.viewport,
                    variant = backgroundVariant,
                )
            }

            Box(Modifier.fillMaxSize().clipToBounds()) {
                val nodeById = remember(nodes) { nodes.associateBy(Node::id) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(minZoom, maxZoom) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                if (pan != Offset.Zero) {
                                    userModifiedViewport = true
                                    state.panBy(
                                        dx = pan.x.toDouble(),
                                        dy = pan.y.toDouble(),
                                    )
                                }
                                if (zoom != 1f) {
                                    userModifiedViewport = true
                                    state.zoomBy(
                                        factor = zoom.toDouble(),
                                        anchorX = centroid.x.toDouble(),
                                        anchorY = centroid.y.toDouble(),
                                        minZoom = minZoom,
                                        maxZoom = maxZoom,
                                    )
                                }
                            }
                        }
                        .pointerInput(onPaneClick) {
                            awaitPointerEventScope {
                                while (true) {
                                    val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: continue
                                    var moved = false
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) {
                                            if (!moved) {
                                                onPaneClick?.invoke()
                                            }
                                            break
                                        }

                                        val delta = change.position - change.previousPosition
                                        if (delta != Offset.Zero) {
                                            moved = true
                                            userModifiedViewport = true
                                            state.panBy(
                                                dx = delta.x.toDouble(),
                                                dy = delta.y.toDouble(),
                                            )
                                            change.consume()
                                        }
                                    }
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (!event.buttons.isSecondaryPressed) {
                                        continue
                                    }
                                    val next = event.changes.firstOrNull { it.pressed } ?: continue
                                    val delta = next.position - next.previousPosition
                                    if (delta != Offset.Zero) {
                                        userModifiedViewport = true
                                        state.panBy(delta.x.toDouble(), delta.y.toDouble())
                                        next.consume()
                                    }
                                }
                            }
                        }
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = state.viewport.x.toFloat()
                            translationY = state.viewport.y.toFloat()
                            scaleX = state.viewport.zoom.toFloat()
                            scaleY = state.viewport.zoom.toFloat()
                        }
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        edges
                            .filterNot { it.hidden }
                            .sortedBy { it.zIndex }
                            .forEach { edge ->
                                val source = nodeById[edge.source] ?: return@forEach
                                val target = nodeById[edge.target] ?: return@forEach
                                drawFlowEdge(
                                    source = source,
                                    target = target,
                                    edge = edge,
                                    renderStyle = edgeRenderStyle(edge),
                                    pathStyle = edgePathStyle,
                                    defaultNodeWidth = defaultWidthPx,
                                    defaultNodeHeight = defaultHeightPx,
                                )
                            }
                    }

                    nodes
                        .filterNot { it.hidden }
                        .sortedBy { it.zIndex }
                        .forEach { node ->
                            FlowNodeBox(
                                node = node,
                                nodeContent = nodeTypes[node.type],
                                onNodeClick = onNodeClick,
                                onNodesChange = onNodesChange,
                                onConnect = onConnect,
                                viewport = state.viewport,
                                renderStyle = nodeRenderStyle(node),
                                handleRenderStyle = { handle -> handleRenderStyle(node, handle) },
                                defaultNodeWidth = defaultNodeWidth,
                                defaultNodeHeight = defaultNodeHeight,
                            )
                        }

                    viewportOverlay(state)
                }

                if (showControls) {
                    FlowControls(
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                        viewport = state.viewport,
                        onZoomIn = {
                            userModifiedViewport = true
                            state.zoomBy(
                                factor = 1.15,
                                anchorX = canvasSize.width / 2.0,
                                anchorY = canvasSize.height / 2.0,
                                minZoom = minZoom,
                                maxZoom = maxZoom,
                            )
                        },
                        onZoomOut = {
                            userModifiedViewport = true
                            state.zoomBy(
                                factor = 1.0 / 1.15,
                                anchorX = canvasSize.width / 2.0,
                                anchorY = canvasSize.height / 2.0,
                                minZoom = minZoom,
                                maxZoom = maxZoom,
                            )
                        },
                        onFitView = {
                            userModifiedViewport = false
                            state.fitView(
                                nodes = nodes,
                                defaultNodeWidth = defaultWidthPx,
                                defaultNodeHeight = defaultHeightPx,
                                options = fitViewOptions,
                            )
                        },
                    )
                }

                if (showMiniMap) {
                    MiniMap(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                        nodes = nodes,
                        edges = edges,
                        defaultNodeWidth = defaultWidthPx,
                        defaultNodeHeight = defaultHeightPx,
                    )
                }

                overlay(state)
            }
        }
    }
}

@Composable
private fun FlowNodeBox(
    node: Node,
    nodeContent: NodeContent?,
    onNodeClick: ((Node) -> Unit)?,
    onNodesChange: ((List<NodeChange>) -> Unit)?,
    onConnect: ((Connection) -> Unit)?,
    viewport: Viewport,
    renderStyle: NodeRenderStyle,
    handleRenderStyle: (Handle) -> HandleRenderStyle,
    defaultNodeWidth: Dp,
    defaultNodeHeight: Dp,
) {
    val density = LocalDensity.current
    val width = (node.measured?.width ?: node.width ?: with(density) { defaultNodeWidth.toPx().toDouble() })
    val height = (node.measured?.height ?: node.height ?: with(density) { defaultNodeHeight.toPx().toDouble() })
    val widthDp = with(density) { width.toFloat().toDp() }
    val heightDp = with(density) { height.toFloat().toDp() }
    val handles = resolvedHandles(node)
    val backgroundColor = renderStyle.backgroundColor ?: if (node.selected) FlowSurface.copy(alpha = 0.98f) else FlowSurface.copy(alpha = 0.92f)
    val borderColor = renderStyle.borderColor ?: if (node.selected) Color(0xFF60A5FA) else FlowBorder

    Box(
        modifier = Modifier
            .offset { IntOffset(node.position.x.roundToInt(), node.position.y.roundToInt()) }
            .size(widthDp, heightDp)
            .zIndex(if (node.dragging || node.selected) 100f else node.zIndex.toFloat())
            .graphicsLayer {
                alpha = renderStyle.alpha
                scaleX = renderStyle.scale
                scaleY = renderStyle.scale
            }
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor, RoundedCornerShape(16.dp))
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .pointerInput(node.id, viewport.zoom) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: continue
                        var dragged = false
                        var currentPosition = node.position
                        var accumulatedDx = 0f
                        var accumulatedDy = 0f

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (!dragged) {
                                    onNodeClick?.invoke(node)
                                } else {
                                    onNodesChange?.invoke(
                                        listOf(
                                            NodePositionChange(
                                                id = node.id,
                                                position = currentPosition,
                                                dragging = false,
                                            )
                                        )
                                    )
                                }
                                break
                            }

                            val delta = change.position - change.previousPosition
                            if (!dragged) {
                                accumulatedDx += delta.x
                                accumulatedDy += delta.y
                                if ((accumulatedDx * accumulatedDx) + (accumulatedDy * accumulatedDy) < 9f) {
                                    continue
                                }
                                dragged = true
                                onNodeClick?.invoke(node)
                            }

                            currentPosition = XYPosition(
                                x = currentPosition.x + delta.x / viewport.zoom,
                                y = currentPosition.y + delta.y / viewport.zoom,
                            )
                            onNodesChange?.invoke(
                                listOf(
                                    NodePositionChange(
                                        id = node.id,
                                        position = currentPosition,
                                        dragging = true,
                                    )
                                )
                            )
                            change.consume()
                        }
                    }
                }
            }
    ) {
        val props = NodeProps(
            id = node.id,
            data = node.data,
            selected = node.selected,
            dragging = node.dragging,
            type = node.type,
            width = width,
            height = height,
        )
        if (nodeContent != null) {
            nodeContent(props)
        } else {
            DefaultNode(props)
        }

        handles.forEach { handle ->
                        Handle(
                            modifier = handleModifier(
                                handle = handle,
                                width = widthDp,
                                height = heightDp,
                                style = handleRenderStyle(handle),
                            ),
                            type = handle.type,
                            style = handleRenderStyle(handle),
                onConnect = {
                    onConnect?.invoke(
                        Connection(
                            source = if (handle.type == HandleType.Source) node.id else null,
                            target = if (handle.type == HandleType.Target) node.id else null,
                            sourceHandle = handle.id.takeIf { handle.type == HandleType.Source },
                            targetHandle = handle.id.takeIf { handle.type == HandleType.Target },
                        )
                    )
                },
            )
        }
    }
}

@Composable
private fun DefaultNode(props: NodeProps) {
    val title = nodeLabel(props.data)

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
    ) {
        Text(
            text = title,
            color = FlowText,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        props.type?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = FlowText.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
fun Handle(
    modifier: Modifier = Modifier,
    type: HandleType,
    style: HandleRenderStyle = HandleRenderStyle(),
    onConnect: (() -> Unit)? = null,
) {
    val size = style.size
    val fill = style.fillColor ?: if (type == HandleType.Source) FlowHandleSource else FlowHandleTarget
    val border = style.borderColor ?: Color(0xFF09101D)
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { alpha = style.alpha }
            .clip(CircleShape)
            .background(fill)
            .border(2.dp, border, CircleShape)
            .clickable(enabled = onConnect != null) { onConnect?.invoke() }
    )
}

@Composable
fun MiniMap(
    modifier: Modifier = Modifier,
    nodes: List<Node>,
    edges: List<Edge>,
    defaultNodeWidth: Double = 180.0,
    defaultNodeHeight: Double = 96.0,
) {
    Surface(
        modifier = modifier.width(144.dp).size(width = 144.dp, height = 92.dp),
        color = Color(0xCC020617),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            if (nodes.isEmpty()) {
                return@Canvas
            }
            val bounds = graphBounds(nodes, defaultNodeWidth, defaultNodeHeight)
            val scaleX = size.width / max(bounds.width, 1f)
            val scaleY = size.height / max(bounds.height, 1f)
            val scale = min(scaleX, scaleY)

            edges.forEach { edge ->
                val source = nodes.firstOrNull { it.id == edge.source } ?: return@forEach
                val target = nodes.firstOrNull { it.id == edge.target } ?: return@forEach
                drawLine(
                    color = FlowEdge.copy(alpha = 0.55f),
                    start = miniMapPoint(source.position, bounds.left, bounds.top, scale),
                    end = miniMapPoint(target.position, bounds.left, bounds.top, scale),
                    strokeWidth = 1f,
                )
            }

            nodes.forEach { node ->
                val width = ((node.measured?.width ?: node.width ?: defaultNodeWidth) * scale).toFloat().coerceAtLeast(8f)
                val height = ((node.measured?.height ?: node.height ?: defaultNodeHeight) * scale).toFloat().coerceAtLeast(8f)
                val origin = miniMapPoint(node.position, bounds.left, bounds.top, scale)
                drawRoundRect(
                    color = if (node.selected) Color(0xFF60A5FA) else FlowSurface,
                    topLeft = origin,
                    size = androidx.compose.ui.geometry.Size(width, height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                )
            }
        }
    }
}

@Composable
fun Background(
    modifier: Modifier = Modifier,
    viewport: Viewport = Viewport(),
    variant: BackgroundVariant = BackgroundVariant.Dots,
) {
    FlowBackground(modifier, viewport, variant)
}

@Composable
fun Controls(
    modifier: Modifier = Modifier,
    viewport: Viewport,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFitView: () -> Unit,
) {
    FlowControls(modifier, viewport, onZoomIn, onZoomOut, onFitView)
}

@Composable
fun BoxScope.Panel(
    position: PanelPosition = PanelPosition.TopRight,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.align(position.alignment()),
        content = content,
    )
}

@Composable
fun BoxScope.ViewportPortal(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        content = content,
    )
}

@Composable
private fun FlowControls(
    modifier: Modifier,
    viewport: Viewport,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFitView: () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = Color(0xCC020617),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            ControlButton(label = "-", onClick = onZoomOut)
            ControlButton(label = "+", onClick = onZoomIn)
            ControlButton(label = "Fit", onClick = onFitView)
            Text(
                text = "${(viewport.zoom * 100).roundToInt()}%",
                color = FlowText.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun ControlButton(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.padding(horizontal = 2.dp),
        color = FlowSurface,
        shape = RoundedCornerShape(8.dp),
    ) {
        Box(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = FlowText, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun FlowBackground(
    modifier: Modifier,
    viewport: Viewport,
    variant: BackgroundVariant,
) {
    Canvas(modifier) {
        val step = when (variant) {
            BackgroundVariant.Dots -> 28f
            BackgroundVariant.Lines -> 36f
            BackgroundVariant.Cross -> 32f
        }
        val offsetX = ((viewport.x % step.toDouble()) + step).toFloat() % step
        val offsetY = ((viewport.y % step.toDouble()) + step).toFloat() % step

        when (variant) {
            BackgroundVariant.Dots -> {
                var x = offsetX
                while (x < size.width) {
                    var y = offsetY
                    while (y < size.height) {
                        drawCircle(Color(0x142E3B55), radius = 1.2f, center = androidx.compose.ui.geometry.Offset(x, y))
                        y += step
                    }
                    x += step
                }
            }
            BackgroundVariant.Lines -> {
                var x = offsetX
                while (x < size.width) {
                    drawLine(Color(0x142E3B55), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 1f)
                    x += step
                }
                var y = offsetY
                while (y < size.height) {
                    drawLine(Color(0x142E3B55), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1f)
                    y += step
                }
            }
            BackgroundVariant.Cross -> {
                val majorStep = step * 5f
                val majorOffsetX = ((viewport.x % majorStep.toDouble()) + majorStep).toFloat() % majorStep
                val majorOffsetY = ((viewport.y % majorStep.toDouble()) + majorStep).toFloat() % majorStep

                var x = offsetX
                while (x < size.width) {
                    drawLine(Color(0x122E3B55), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 1f)
                    x += step
                }
                var y = offsetY
                while (y < size.height) {
                    drawLine(Color(0x122E3B55), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1f)
                    y += step
                }
                var majorX = majorOffsetX
                while (majorX < size.width) {
                    drawLine(Color(0x203D5B8C), androidx.compose.ui.geometry.Offset(majorX, 0f), androidx.compose.ui.geometry.Offset(majorX, size.height), 1.3f)
                    majorX += majorStep
                }
                var majorY = majorOffsetY
                while (majorY < size.height) {
                    drawLine(Color(0x203D5B8C), androidx.compose.ui.geometry.Offset(0f, majorY), androidx.compose.ui.geometry.Offset(size.width, majorY), 1.3f)
                    majorY += majorStep
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFlowEdge(
    source: Node,
    target: Node,
    edge: Edge,
    renderStyle: EdgeRenderStyle,
    pathStyle: EdgePathStyle,
    defaultNodeWidth: Double,
    defaultNodeHeight: Double,
) {
    val start = anchorFor(source, edge.sourceHandle, HandleType.Source, defaultNodeWidth, defaultNodeHeight)
    val end = anchorFor(target, edge.targetHandle, HandleType.Target, defaultNodeWidth, defaultNodeHeight)
    val pathData = when (pathStyle) {
        EdgePathStyle.Bezier -> bezierEdgePath(start, end)
        EdgePathStyle.Orthogonal -> orthogonalEdgePath(start, end)
    }

    drawPath(
        path = pathData.path,
        color = (renderStyle.color ?: if (edge.selected) Color(0xFF93C5FD) else FlowEdge).copy(alpha = renderStyle.alpha),
        style = Stroke(width = renderStyle.width ?: if (edge.animated) 3f else 2f),
    )

    edge.markerEnd?.let { marker ->
        drawMarker(
            end = end.point,
            start = pathData.markerStart,
            marker = marker,
            color = (renderStyle.color ?: FlowEdge).copy(alpha = renderStyle.alpha),
        )
    }
}

private data class FlowEdgePath(
    val path: Path,
    val markerStart: Offset,
)

private fun bezierEdgePath(
    start: FlowAnchor,
    end: FlowAnchor,
): FlowEdgePath {
    val controlOffset = max(
        kotlin.math.abs(end.point.x - start.point.x),
        kotlin.math.abs(end.point.y - start.point.y),
    ) * 0.34f + 34f
    val startControl = controlPoint(start, controlOffset, outgoing = true)
    val endControl = controlPoint(end, controlOffset, outgoing = false)
    val path = Path().apply {
        moveTo(start.point.x, start.point.y)
        cubicTo(
            startControl.x,
            startControl.y,
            endControl.x,
            endControl.y,
            end.point.x,
            end.point.y,
        )
    }
    return FlowEdgePath(path = path, markerStart = endControl)
}

private fun orthogonalEdgePath(
    start: FlowAnchor,
    end: FlowAnchor,
): FlowEdgePath {
    val points = mutableListOf(start.point)
    val horizontalSource = start.position == Position.Left || start.position == Position.Right
    val markerStart: Offset
    val path = Path().apply {
        moveTo(start.point.x, start.point.y)
        if (horizontalSource) {
            val midX = (start.point.x + end.point.x) / 2f
            lineTo(midX, start.point.y)
            lineTo(midX, end.point.y)
            markerStart = Offset(midX, end.point.y)
            lineTo(end.point.x, end.point.y)
            points += Offset(midX, start.point.y)
            points += Offset(midX, end.point.y)
        } else {
            val midY = (start.point.y + end.point.y) / 2f
            lineTo(start.point.x, midY)
            lineTo(end.point.x, midY)
            markerStart = Offset(end.point.x, midY)
            lineTo(end.point.x, end.point.y)
            points += Offset(start.point.x, midY)
            points += Offset(end.point.x, midY)
        }
    }
    return FlowEdgePath(path = path, markerStart = markerStart)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMarker(
    end: androidx.compose.ui.geometry.Offset,
    start: androidx.compose.ui.geometry.Offset,
    marker: EdgeMarker,
    color: Color,
) {
    if (marker.type == MarkerType.ArrowClosed || marker.type == MarkerType.Arrow) {
        val angle = atan2(end.y - start.y, end.x - start.x)
        val length = (marker.width ?: 12.0).toFloat()
        val halfAngle = (PI / 7f).toFloat()
        val p1 = androidx.compose.ui.geometry.Offset(
            x = (end.x - length * kotlin.math.cos((angle - halfAngle).toDouble())).toFloat(),
            y = (end.y - length * kotlin.math.sin((angle - halfAngle).toDouble())).toFloat(),
        )
        val p2 = androidx.compose.ui.geometry.Offset(
            x = (end.x - length * kotlin.math.cos((angle + halfAngle).toDouble())).toFloat(),
            y = (end.y - length * kotlin.math.sin((angle + halfAngle).toDouble())).toFloat(),
        )
        val arrowPath = Path().apply {
            moveTo(end.x, end.y)
            lineTo(p1.x, p1.y)
            if (marker.type == MarkerType.ArrowClosed) {
                lineTo(p2.x, p2.y)
                close()
            } else {
                moveTo(end.x, end.y)
                lineTo(p2.x, p2.y)
            }
        }
        drawPath(
            path = arrowPath,
            color = marker.color?.let(::parseColor) ?: color,
        )
    }
}

private fun anchorFor(
    node: Node,
    handleId: String?,
    type: HandleType,
    defaultNodeWidth: Double,
    defaultNodeHeight: Double,
): FlowAnchor {
    val width = node.measured?.width ?: node.width ?: defaultNodeWidth
    val height = node.measured?.height ?: node.height ?: defaultNodeHeight
    val handle = node.handles.firstOrNull { it.type == type && it.id == handleId }
        ?: defaultHandle(node, type)
    val normalizedOffset = (handle.offset ?: 0.5).coerceIn(0.08, 0.92)
    val inset = handle.inset ?: 0.0

    val x = when (handle.position) {
        Position.Left -> node.position.x + inset
        Position.Top, Position.Bottom -> node.position.x + width * normalizedOffset
        Position.Right -> node.position.x + width - inset
    }
    val y = when (handle.position) {
        Position.Top -> node.position.y + inset
        Position.Left, Position.Right -> node.position.y + height * normalizedOffset
        Position.Bottom -> node.position.y + height - inset
    }
    return FlowAnchor(
        point = androidx.compose.ui.geometry.Offset(x.toFloat(), y.toFloat()),
        position = handle.position,
    )
}

private fun defaultHandle(node: Node, type: HandleType): Handle = Handle(
    id = if (type == HandleType.Source) "source" else "target",
    type = type,
    position = if (type == HandleType.Source) node.sourcePosition else node.targetPosition,
    offset = 0.5,
)

private fun resolvedHandles(node: Node): List<Handle> =
    node.handles.ifEmpty {
        if (!node.showDefaultHandles) {
            emptyList()
        } else {
            listOf(
                defaultHandle(node, HandleType.Target),
                defaultHandle(node, HandleType.Source),
            )
        }
    }

private fun handleModifier(
    handle: Handle,
    width: Dp,
    height: Dp,
    style: HandleRenderStyle,
): Modifier {
    val offset = (handle.offset ?: 0.5).coerceIn(0.08, 0.92)
    val half = style.size / 2
    val offsetFactor = offset.toFloat()
    val inset = (handle.inset ?: 0.0).dp
    return when (handle.position) {
        Position.Left -> Modifier.offset(x = inset - half, y = (height * offsetFactor) - half)
        Position.Right -> Modifier.offset(x = width - inset - half, y = (height * offsetFactor) - half)
        Position.Top -> Modifier.offset(x = (width * offsetFactor) - half, y = inset - half)
        Position.Bottom -> Modifier.offset(x = (width * offsetFactor) - half, y = height - inset - half)
    }
}

private fun controlPoint(
    anchor: FlowAnchor,
    distance: Float,
    outgoing: Boolean,
): Offset {
    val signedDistance = if (outgoing) distance else -distance
    return when (anchor.position) {
        Position.Left -> anchor.point.copy(x = anchor.point.x - signedDistance)
        Position.Right -> anchor.point.copy(x = anchor.point.x + signedDistance)
        Position.Top -> anchor.point.copy(y = anchor.point.y - signedDistance)
        Position.Bottom -> anchor.point.copy(y = anchor.point.y + signedDistance)
    }
}

private fun nodeLabel(data: Any?): String = when (data) {
    null -> "Node"
    is String -> data
    is Map<*, *> -> data["label"]?.toString() ?: data.toString()
    else -> data.toString()
}

internal fun fitViewport(
    nodes: List<Node>,
    viewportWidth: Double,
    viewportHeight: Double,
    defaultNodeWidth: Double,
    defaultNodeHeight: Double,
    options: FitViewOptions,
): Viewport {
    val bounds = graphBounds(nodes, defaultNodeWidth, defaultNodeHeight)
    val contentWidth = max(bounds.width.toDouble(), 1.0)
    val contentHeight = max(bounds.height.toDouble(), 1.0)
    val zoom = min(
        (viewportWidth * (1.0 - options.padding * 2.0)) / contentWidth,
        (viewportHeight * (1.0 - options.padding * 2.0)) / contentHeight,
    ).coerceIn(options.minZoom, options.maxZoom)

    val x = (viewportWidth - contentWidth * zoom) / 2.0 - bounds.left * zoom
    val y = (viewportHeight - contentHeight * zoom) / 2.0 - bounds.top * zoom
    return Viewport(x = x, y = y, zoom = zoom)
}

private fun graphBounds(
    nodes: List<Node>,
    defaultNodeWidth: Double,
    defaultNodeHeight: Double,
): androidx.compose.ui.geometry.Rect {
    val left = nodes.minOfOrNull { it.position.x.toFloat() } ?: 0f
    val top = nodes.minOfOrNull { it.position.y.toFloat() } ?: 0f
    val right = nodes.maxOfOrNull {
        (it.position.x + (it.measured?.width ?: it.width ?: defaultNodeWidth)).toFloat()
    } ?: 0f
    val bottom = nodes.maxOfOrNull {
        (it.position.y + (it.measured?.height ?: it.height ?: defaultNodeHeight)).toFloat()
    } ?: 0f
    return androidx.compose.ui.geometry.Rect(left, top, right, bottom)
}

private fun miniMapPoint(
    position: XYPosition,
    left: Float,
    top: Float,
    scale: Float,
): androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset(
    x = (position.x.toFloat() - left) * scale,
    y = (position.y.toFloat() - top) * scale,
)

private fun parseColor(value: String): Color = runCatching {
    val hex = value.removePrefix("#")
    val argb = if (hex.length <= 6) {
        hex.toLong(16) or 0xFF000000L
    } else {
        hex.toLong(16)
    }
    Color(argb)
}.getOrDefault(FlowEdge)

private fun PanelPosition.alignment(): Alignment = when (this) {
    PanelPosition.TopLeft -> Alignment.TopStart
    PanelPosition.TopCenter -> Alignment.TopCenter
    PanelPosition.TopRight -> Alignment.TopEnd
    PanelPosition.BottomLeft -> Alignment.BottomStart
    PanelPosition.BottomCenter -> Alignment.BottomCenter
    PanelPosition.BottomRight -> Alignment.BottomEnd
}
