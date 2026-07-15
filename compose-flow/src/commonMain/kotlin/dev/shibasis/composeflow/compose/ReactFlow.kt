package dev.shibasis.composeflow.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import dev.shibasis.composeflow.compose.components.FlowBackground
import dev.shibasis.composeflow.compose.components.FlowControls
import dev.shibasis.composeflow.compose.components.FlowNodeBox
import dev.shibasis.composeflow.compose.components.MiniMap
import dev.shibasis.composeflow.compose.interaction.FlowViewportPlatformGestureEffect
import dev.shibasis.composeflow.compose.interaction.FlowViewportGestureConfig
import dev.shibasis.composeflow.compose.interaction.LocalFlowViewportPlatformBridge
import dev.shibasis.composeflow.compose.interaction.SelectionBoxState
import dev.shibasis.composeflow.compose.interaction.flowPointerViewportGestures
import dev.shibasis.composeflow.compose.interaction.flowViewportPointerTracking
import dev.shibasis.composeflow.compose.interaction.flowWheelAndTrackpadViewportGestures
import dev.shibasis.composeflow.compose.interaction.rememberFlowViewportInteractionState
import dev.shibasis.composeflow.compose.interaction.zoomAroundCanvasCenter
import dev.shibasis.composeflow.compose.primitives.EdgeHitAreaOverlay
import dev.shibasis.composeflow.compose.primitives.EdgePathStyle
import dev.shibasis.composeflow.compose.primitives.EdgeRenderStyle
import dev.shibasis.composeflow.compose.primitives.FlowAnchor
import dev.shibasis.composeflow.compose.primitives.HandleRenderStyle
import dev.shibasis.composeflow.compose.primitives.NodeRenderStyle
import dev.shibasis.composeflow.compose.primitives.NodeTypes
import dev.shibasis.composeflow.compose.primitives.anchorFor
import dev.shibasis.composeflow.compose.primitives.drawConnectionLine
import dev.shibasis.composeflow.compose.primitives.drawFlowEdge
import dev.shibasis.composeflow.model.BackgroundVariant
import dev.shibasis.composeflow.model.Connection
import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.EdgeChange
import dev.shibasis.composeflow.model.EdgeSelectionChange
import dev.shibasis.composeflow.model.FitViewOptions
import dev.shibasis.composeflow.model.Handle
import dev.shibasis.composeflow.model.HandleType
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.NodeChange
import dev.shibasis.composeflow.model.NodeSelectionChange
import dev.shibasis.composeflow.runtime.ConnectionController
import dev.shibasis.composeflow.runtime.LocalConnectionController
import dev.shibasis.composeflow.runtime.LocalReactFlowState
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.composeflow.runtime.FlowRuntimeDefaults
import dev.shibasis.composeflow.runtime.rememberConnectionController
import dev.shibasis.composeflow.runtime.rememberReactFlowState
import dev.shibasis.composeflow.compose.theme.FlowSizing
import dev.shibasis.composeflow.compose.theme.FlowBorder
import dev.shibasis.composeflow.compose.theme.FlowCanvasBackground
import dev.shibasis.composeflow.compose.theme.FlowPanelSurface
import dev.shibasis.composeflow.compose.theme.FlowSelection
import dev.shibasis.composeflow.compose.theme.FlowText

// Compose best-practice note:
// keep viewport state and gesture handling above the node content tree. This mirrors
// React Flow's store-driven viewport transform and Compose guidance from "Thinking in Compose":
// state flows down, events flow up, and rendering stays a pure function of state.
@Composable
fun ReactFlow(
    nodes: List<Node>,
    edges: List<Edge>,
    modifier: Modifier = Modifier,
    state: ReactFlowState = LocalReactFlowState.current ?: rememberReactFlowState(),
    nodeTypes: NodeTypes = emptyMap(),
    onNodesChange: ((List<NodeChange>) -> Unit)? = null,
    onEdgesChange: ((List<EdgeChange>) -> Unit)? = null,
    onConnect: ((Connection) -> Unit)? = null,
    onNodeClick: ((Node) -> Unit)? = null,
    onEdgeClick: ((Edge) -> Unit)? = null,
    isValidConnection: ((Connection) -> Boolean)? = null,
    onConnectStart: (() -> Unit)? = null,
    onConnectEnd: (() -> Unit)? = null,
    onDelete: ((nodes: List<Node>, edges: List<Edge>) -> Unit)? = null,
    onSelectionChange: ((nodes: List<Node>, edges: List<Edge>) -> Unit)? = null,
    fitView: Boolean = true,
    fitViewOptions: FitViewOptions = FitViewOptions(),
    showBackground: Boolean = true,
    backgroundVariant: BackgroundVariant = BackgroundVariant.Dots,
    showControls: Boolean = true,
    showMiniMap: Boolean = false,
    minZoom: Double = FlowRuntimeDefaults.minZoom,
    maxZoom: Double = FlowRuntimeDefaults.maxZoom,
    panOnDrag: Boolean = true,
    selectionOnDrag: Boolean = false,
    snapToGrid: Boolean = false,
    snapGrid: Pair<Double, Double> = Pair(15.0, 15.0),
    autoPanOnNodeDrag: Boolean = true,
    defaultNodeWidth: androidx.compose.ui.unit.Dp = FlowSizing.defaultNodeWidth,
    defaultNodeHeight: androidx.compose.ui.unit.Dp = FlowSizing.defaultNodeHeight,
    nodeRenderStyle: (Node) -> NodeRenderStyle = { NodeRenderStyle() },
    edgeRenderStyle: (Edge) -> EdgeRenderStyle = { EdgeRenderStyle() },
    edgePathStyle: EdgePathStyle = EdgePathStyle.Bezier,
    handleRenderStyle: (Node, Handle) -> HandleRenderStyle = { _, _ -> HandleRenderStyle() },
    onPaneClick: (() -> Unit)? = null,
    overlay: @Composable BoxScope.(ReactFlowState) -> Unit = {},
    viewportOverlay: @Composable BoxScope.(ReactFlowState) -> Unit = {},
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val interactionState = rememberFlowViewportInteractionState()
    val platformBridge = LocalFlowViewportPlatformBridge.current
    val gestureConfig = remember(minZoom, maxZoom) {
        FlowViewportGestureConfig(
            minZoom = minZoom,
            maxZoom = maxZoom,
        )
    }
    val density = LocalDensity.current
    val defaultWidthPx = with(density) { defaultNodeWidth.toPx().toDouble() }
    val defaultHeightPx = with(density) { defaultNodeHeight.toPx().toDouble() }

    LaunchedEffect(nodes, canvasSize, fitView, fitViewOptions, interactionState.userModifiedViewport) {
        if (fitView && canvasSize.width > 0 && canvasSize.height > 0 && nodes.isNotEmpty() && !interactionState.userModifiedViewport) {
            state.fitView(nodes, defaultWidthPx, defaultHeightPx, fitViewOptions)
        }
    }

    LaunchedEffect(nodes, edges) {
        state.selectedNodeIds = nodes.filter(Node::selected).mapTo(linkedSetOf(), Node::id)
        state.selectedEdgeIds = edges.filter(Edge::selected).mapTo(linkedSetOf(), Edge::id)
    }

    val connectionController = rememberConnectionController()
    val selectionBoxState = remember { SelectionBoxState() }

    CompositionLocalProvider(
        LocalReactFlowState provides state,
        LocalConnectionController provides connectionController,
    ) {
        FlowViewportPlatformGestureEffect(
            state = state,
            interactionState = interactionState,
            config = gestureConfig,
            platformBridge = platformBridge,
        )
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .background(FlowCanvasBackground)
                .onSizeChanged {
                    canvasSize = it
                    state.canvasSize = it
                }
                .onGloballyPositioned { coordinates ->
                    interactionState.updateCanvasOriginInWindow(coordinates.positionInWindow())
                }
                .flowViewportPointerTracking(interactionState)
                .flowWheelAndTrackpadViewportGestures(
                    state = state,
                    interactionState = interactionState,
                    config = gestureConfig,
                    platformBridge = platformBridge,
                )
                .onPreviewKeyEvent { keyEvent ->
                    if (onDelete != null &&
                        keyEvent.type == KeyEventType.KeyDown &&
                        (keyEvent.key == Key.Delete ||
                            keyEvent.key == Key.Backspace)
                    ) {
                        val selectedNodes = nodes.filter { it.selected && it.deletable }
                        val selectedEdges = edges.filter { it.selected && it.deletable }
                        if (selectedNodes.isNotEmpty() || selectedEdges.isNotEmpty()) {
                            onDelete(selectedNodes, selectedEdges)
                            true
                        } else false
                    } else false
                },
        ) {
            if (showBackground) {
                FlowBackground(Modifier.fillMaxSize(), state.viewport, backgroundVariant)
            }

            Box(Modifier.fillMaxSize().clipToBounds()) {
                val nodeById = remember(nodes) { nodes.associateBy(Node::id) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .flowPointerViewportGestures(
                            state = state,
                            interactionState = interactionState,
                            config = gestureConfig,
                            onPaneClick = {
                                onNodesChange?.invoke(
                                    nodes.filter { it.selected }.map { NodeSelectionChange(it.id, false) },
                                )
                                onEdgesChange?.invoke(
                                    edges.filter { it.selected }.map { EdgeSelectionChange(it.id, false) },
                                )
                                onPaneClick?.invoke()
                            },
                        ),
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = state.viewport.x.toFloat()
                            translationY = state.viewport.y.toFloat()
                            scaleX = state.viewport.zoom.toFloat()
                            scaleY = state.viewport.zoom.toFloat()
                            // Viewport x/y, screenToFlowPosition, fitView, minimap, and gestures
                            // all use a top-left affine origin. Compose defaults layer scaling to
                            // the center, which adds an unmodelled half-canvas translation and can
                            // push correctly fitted nodes outside the viewport at zoom < 1.
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
                ) {
                    // Styles are resolved once per frame in composition so the draw pass, the
                    // labels layer, and the animation gate all agree on the same values.
                    val edgeStyles = edges.filterNot { it.hidden }
                        .sortedBy { it.zIndex }
                        .map { it to edgeRenderStyle(it) }
                    val anyFlowAnimated = edgeStyles.any { (_, style) -> style.flowAnimated }
                    val flowTransition = rememberInfiniteTransition(label = "edgeFlow")
                    val flowPhase by flowTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = FlowSizing.edgeFlowDashPeriodPx,
                        animationSpec = infiniteRepeatable(
                            animation = tween(FlowSizing.edgeFlowCycleMillis, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart,
                        ),
                        label = "edgeFlowPhase",
                    )
                    Canvas(Modifier.fillMaxSize()) {
                        // Only read the animated phase when a wire actually flows — otherwise the
                        // canvas would invalidate every frame for a static graph.
                        val dashPhase = if (anyFlowAnimated) flowPhase else 0f
                        edgeStyles.forEach { (edge, style) ->
                            val source = nodeById[edge.source] ?: return@forEach
                            val target = nodeById[edge.target] ?: return@forEach
                            drawFlowEdge(source, target, edge, style, edgePathStyle, defaultWidthPx, defaultHeightPx, dashPhase)
                        }

                        if (connectionController.isConnecting) {
                            val srcNodeId = connectionController.sourceNodeId
                            val srcHandleId = connectionController.sourceHandleId
                            val srcHandleType = connectionController.sourceHandleType
                            val srcNode = srcNodeId?.let(nodeById::get)
                            if (srcNode != null && srcHandleType != null) {
                                val startAnchor = anchorFor(
                                    srcNode, srcHandleId, srcHandleType, defaultWidthPx, defaultHeightPx,
                                )
                                val endScreen = connectionController.connectionLineEnd
                                val endFlow = androidx.compose.ui.geometry.Offset(
                                    ((endScreen.x - state.viewport.x) / state.viewport.zoom).toFloat(),
                                    ((endScreen.y - state.viewport.y) / state.viewport.zoom).toFloat(),
                                )
                                drawConnectionLine(startAnchor, endFlow, edgePathStyle, FlowSelection)
                            }
                        }

                        if (selectionBoxState.isSelecting) {
                            val rect = selectionBoxState.selectionRect
                            val flowStart = androidx.compose.ui.geometry.Offset(
                                ((rect.left - state.viewport.x) / state.viewport.zoom).toFloat(),
                                ((rect.top - state.viewport.y) / state.viewport.zoom).toFloat(),
                            )
                            val flowEnd = androidx.compose.ui.geometry.Offset(
                                ((rect.right - state.viewport.x) / state.viewport.zoom).toFloat(),
                                ((rect.bottom - state.viewport.y) / state.viewport.zoom).toFloat(),
                            )
                            drawRect(
                                color = FlowSelection.copy(alpha = 0.08f),
                                topLeft = flowStart,
                                size = androidx.compose.ui.geometry.Size(flowEnd.x - flowStart.x, flowEnd.y - flowStart.y),
                            )
                            drawRect(
                                color = FlowSelection.copy(alpha = 0.4f),
                                topLeft = flowStart,
                                size = androidx.compose.ui.geometry.Size(flowEnd.x - flowStart.x, flowEnd.y - flowStart.y),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f),
                            )
                        }
                    }

                    EdgeHitAreaOverlay(
                        edges = edges,
                        nodeById = nodeById,
                        defaultNodeWidth = defaultWidthPx,
                        defaultNodeHeight = defaultHeightPx,
                        onEdgeClick = onEdgeClick,
                    )

                    FlowEdgeLabels(
                        edgeStyles = edgeStyles,
                        nodeById = nodeById,
                        defaultNodeWidth = defaultWidthPx,
                        defaultNodeHeight = defaultHeightPx,
                    )

                    nodes.filterNot { it.hidden }.sortedBy { it.zIndex }.forEach { node ->
                        FlowNodeBox(
                            node = node,
                            nodeContent = nodeTypes[node.type],
                            onNodeClick = onNodeClick,
                            onNodesChange = onNodesChange,
                            onConnect = onConnect,
                            viewport = state.viewport,
                            renderStyle = nodeRenderStyle(node),
                            handleRenderStyle = { handle -> handleRenderStyle(node, handle) },
                            defaultNodeWidthPx = defaultWidthPx,
                            defaultNodeHeightPx = defaultHeightPx,
                            snapToGrid = snapToGrid,
                            snapGrid = snapGrid,
                            autoPanOnDrag = autoPanOnNodeDrag,
                            canvasSize = canvasSize,
                            onPanBy = state::panBy,
                            isValidConnection = isValidConnection,
                            onConnectStart = onConnectStart,
                            onConnectEnd = onConnectEnd,
                        )
                    }

                    viewportOverlay(state)
                }

                if (showControls) {
                    FlowControls(
                        modifier = Modifier.align(Alignment.TopEnd).padding(FlowSizing.controlsPadding),
                        viewport = state.viewport,
                        onZoomIn = {
                            interactionState.markViewportAsUserModified()
                            state.zoomAroundCanvasCenter(
                                factor = gestureConfig.controlZoomFactor,
                                minZoom = minZoom,
                                maxZoom = maxZoom,
                            )
                        },
                        onZoomOut = {
                            interactionState.markViewportAsUserModified()
                            state.zoomAroundCanvasCenter(
                                factor = 1.0 / gestureConfig.controlZoomFactor,
                                minZoom = minZoom,
                                maxZoom = maxZoom,
                            )
                        },
                        onFitView = {
                            interactionState.clearUserModifiedViewport()
                            state.fitView(nodes, defaultWidthPx, defaultHeightPx, fitViewOptions)
                        },
                    )
                }

                if (showMiniMap) {
                    MiniMap(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(FlowSizing.minimapPadding),
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

/**
 * Mid-wire label pills, rendered in editor space under the node layer. A label shows only while
 * its edge holds attention (flowing or selected) or is broadly visible — faded edges stay quiet,
 * matching the "label on demand" behavior of the web graph views.
 */
@Composable
private fun FlowEdgeLabels(
    edgeStyles: List<Pair<Edge, EdgeRenderStyle>>,
    nodeById: Map<String, Node>,
    defaultNodeWidth: Double,
    defaultNodeHeight: Double,
) {
    edgeStyles.forEach { (edge, style) ->
        val label = edge.label?.takeIf { it.isNotBlank() } ?: return@forEach
        val visible = style.flowAnimated || edge.selected || style.alpha >= 0.9f
        if (!visible) return@forEach
        val source = nodeById[edge.source] ?: return@forEach
        val target = nodeById[edge.target] ?: return@forEach
        val start = anchorFor(source, edge.sourceHandle, HandleType.Source, defaultNodeWidth, defaultNodeHeight)
        val end = anchorFor(target, edge.targetHandle, HandleType.Target, defaultNodeWidth, defaultNodeHeight)
        val midX = (start.point.x + end.point.x) / 2f
        val midY = (start.point.y + end.point.y) / 2f
        Box(
            modifier = Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    layout(0, 0) {
                        placeable.place(
                            x = (midX - placeable.width / 2f).roundToInt(),
                            y = (midY - placeable.height / 2f).roundToInt(),
                        )
                    }
                }
                .background(FlowPanelSurface, RoundedCornerShape(99.dp))
                .border(1.dp, FlowBorder, RoundedCornerShape(99.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Text(
                text = label,
                color = (style.color ?: FlowText).copy(alpha = 0.96f),
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}
