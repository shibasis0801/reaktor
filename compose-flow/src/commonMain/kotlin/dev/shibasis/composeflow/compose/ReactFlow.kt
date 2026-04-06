package dev.shibasis.composeflow.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import dev.shibasis.composeflow.compose.components.FlowBackground
import dev.shibasis.composeflow.compose.components.FlowControls
import dev.shibasis.composeflow.compose.components.FlowNodeBox
import dev.shibasis.composeflow.compose.components.MiniMap
import dev.shibasis.composeflow.compose.primitives.EdgePathStyle
import dev.shibasis.composeflow.compose.primitives.EdgeRenderStyle
import dev.shibasis.composeflow.compose.primitives.HandleRenderStyle
import dev.shibasis.composeflow.compose.primitives.NodeRenderStyle
import dev.shibasis.composeflow.compose.primitives.NodeTypes
import dev.shibasis.composeflow.compose.primitives.drawFlowEdge
import dev.shibasis.composeflow.model.BackgroundVariant
import dev.shibasis.composeflow.model.Connection
import dev.shibasis.composeflow.model.Edge
import dev.shibasis.composeflow.model.FitViewOptions
import dev.shibasis.composeflow.model.Handle
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.NodeChange
import dev.shibasis.composeflow.runtime.LocalReactFlowState
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.composeflow.runtime.rememberReactFlowState
import dev.shibasis.composeflow.compose.theme.FlowSizing
import kotlin.math.exp

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
    var userModifiedViewport by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val defaultWidthPx = with(density) { defaultNodeWidth.toPx().toDouble() }
    val defaultHeightPx = with(density) { defaultNodeHeight.toPx().toDouble() }

    LaunchedEffect(nodes, canvasSize, fitView, fitViewOptions, userModifiedViewport) {
        if (fitView && canvasSize.width > 0 && canvasSize.height > 0 && nodes.isNotEmpty() && !userModifiedViewport) {
            state.fitView(nodes, defaultWidthPx, defaultHeightPx, fitViewOptions)
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
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val scrollDelta = change.scrollDelta
                            val isZoomGesture = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                            userModifiedViewport = true
                            if (isZoomGesture) {
                                val factor = exp((-scrollDelta.y * FlowSizing.wheelZoomSensitivity).toDouble())
                                    .coerceIn(FlowSizing.wheelZoomFactorMin, FlowSizing.wheelZoomFactorMax)
                                state.zoomBy(factor, change.position.x.toDouble(), change.position.y.toDouble(), minZoom, maxZoom)
                            } else {
                                state.panBy(-scrollDelta.x.toDouble(), -scrollDelta.y.toDouble())
                            }
                            change.consume()
                        }
                    }
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
                        .pointerInput(minZoom, maxZoom) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                if (pan != androidx.compose.ui.geometry.Offset.Zero) {
                                    userModifiedViewport = true
                                    state.panBy(pan.x.toDouble(), pan.y.toDouble())
                                }
                                if (zoom != 1f) {
                                    userModifiedViewport = true
                                    state.zoomBy(zoom.toDouble(), centroid.x.toDouble(), centroid.y.toDouble(), minZoom, maxZoom)
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
                                            if (!moved) onPaneClick?.invoke()
                                            break
                                        }
                                        val delta = change.position - change.previousPosition
                                        if (delta != androidx.compose.ui.geometry.Offset.Zero) {
                                            moved = true
                                            userModifiedViewport = true
                                            state.panBy(delta.x.toDouble(), delta.y.toDouble())
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
                                    if (!event.buttons.isSecondaryPressed) continue
                                    val next = event.changes.firstOrNull { it.pressed } ?: continue
                                    val delta = next.position - next.previousPosition
                                    if (delta != androidx.compose.ui.geometry.Offset.Zero) {
                                        userModifiedViewport = true
                                        state.panBy(delta.x.toDouble(), delta.y.toDouble())
                                        next.consume()
                                    }
                                }
                            }
                        },
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = state.viewport.x.toFloat()
                            translationY = state.viewport.y.toFloat()
                            scaleX = state.viewport.zoom.toFloat()
                            scaleY = state.viewport.zoom.toFloat()
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        edges.filterNot { it.hidden }.sortedBy { it.zIndex }.forEach { edge ->
                            val source = nodeById[edge.source] ?: return@forEach
                            val target = nodeById[edge.target] ?: return@forEach
                            drawFlowEdge(source, target, edge, edgeRenderStyle(edge), edgePathStyle, defaultWidthPx, defaultHeightPx)
                        }
                    }

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
                        )
                    }

                    viewportOverlay(state)
                }

                if (showControls) {
                    FlowControls(
                        modifier = Modifier.align(Alignment.TopEnd).padding(FlowSizing.controlsPadding),
                        viewport = state.viewport,
                        onZoomIn = {
                            userModifiedViewport = true
                            state.zoomBy(FlowSizing.controlZoomFactor, canvasSize.width / 2.0, canvasSize.height / 2.0, minZoom, maxZoom)
                        },
                        onZoomOut = {
                            userModifiedViewport = true
                            state.zoomBy(1.0 / FlowSizing.controlZoomFactor, canvasSize.width / 2.0, canvasSize.height / 2.0, minZoom, maxZoom)
                        },
                        onFitView = {
                            userModifiedViewport = false
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
