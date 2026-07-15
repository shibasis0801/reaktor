package dev.shibasis.composeflow.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.zIndex
import dev.shibasis.composeflow.compose.primitives.Handle
import dev.shibasis.composeflow.compose.theme.FlowSelection
import dev.shibasis.composeflow.compose.theme.FlowBorder
import dev.shibasis.composeflow.compose.theme.FlowSizing
import dev.shibasis.composeflow.compose.theme.FlowSurface
import dev.shibasis.composeflow.compose.theme.FlowText
import dev.shibasis.composeflow.compose.theme.FlowVisualDefaults
import dev.shibasis.composeflow.compose.primitives.HandleRenderStyle
import dev.shibasis.composeflow.compose.primitives.NodeContent
import dev.shibasis.composeflow.compose.primitives.NodeProps
import dev.shibasis.composeflow.compose.primitives.NodeRenderStyle
import dev.shibasis.composeflow.compose.primitives.handleModifier
import dev.shibasis.composeflow.compose.primitives.resolvedHandles
import dev.shibasis.composeflow.model.Connection
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.NodeChange
import dev.shibasis.composeflow.model.NodePositionChange
import dev.shibasis.composeflow.model.XYPosition
import dev.shibasis.composeflow.model.HandleType
import dev.shibasis.composeflow.model.Dimensions
import dev.shibasis.composeflow.model.Handle
import dev.shibasis.composeflow.model.NodeDimensionChange
import dev.shibasis.composeflow.model.NodeSelectionChange
import dev.shibasis.composeflow.model.Viewport
import dev.shibasis.composeflow.runtime.ConnectionController
import dev.shibasis.composeflow.runtime.FlowRuntimeDefaults
import dev.shibasis.composeflow.runtime.LocalConnectionController
import kotlin.collections.get
import kotlin.math.roundToInt

// Measure in the node container and report the result upward. This follows the same pattern
// used by graph editors such as React Flow: layout math consumes measured sizes, while the node
// card itself stays focused on rendering.
@Composable
internal fun FlowNodeBox(
    node: Node,
    nodeContent: NodeContent?,
    onNodeClick: ((Node) -> Unit)?,
    onNodesChange: ((List<NodeChange>) -> Unit)?,
    onConnect: ((Connection) -> Unit)?,
    viewport: Viewport,
    renderStyle: NodeRenderStyle,
    handleRenderStyle: (Handle) -> HandleRenderStyle,
    defaultNodeWidthPx: Double,
    defaultNodeHeightPx: Double,
    snapToGrid: Boolean = false,
    snapGrid: Pair<Double, Double> = Pair(15.0, 15.0),
    autoPanOnDrag: Boolean = false,
    canvasSize: IntSize = IntSize.Zero,
    onPanBy: ((Double, Double) -> Unit)? = null,
    isValidConnection: ((Connection) -> Boolean)? = null,
    onConnectStart: (() -> Unit)? = null,
    onConnectEnd: (() -> Unit)? = null,
) {
    val connectionController = LocalConnectionController.current
    val density = LocalDensity.current
    val width = node.measured?.width ?: node.width ?: defaultNodeWidthPx
    val height = node.measured?.height ?: node.height ?: defaultNodeHeightPx
    val widthDp = with(density) { width.toFloat().toDp() }
    val heightDp = with(density) { height.toFloat().toDp() }
    val handles = resolvedHandles(node)
    val backgroundColor = renderStyle.backgroundColor ?: if (node.selected) {
        FlowSurface.copy(alpha = FlowVisualDefaults.selectedNodeSurfaceAlpha)
    } else {
        FlowSurface.copy(alpha = FlowVisualDefaults.idleNodeSurfaceAlpha)
    }
    val borderColor = renderStyle.borderColor ?: if (node.selected) FlowSelection else FlowBorder

    Box(
        modifier = Modifier
            .offset { IntOffset(node.position.x.roundToInt(), node.position.y.roundToInt()) }
            .size(widthDp, heightDp)
            .zIndex(if (node.dragging || node.selected) FlowRuntimeDefaults.selectedNodeZIndex else node.zIndex.toFloat())
            .graphicsLayer {
                alpha = renderStyle.alpha
                scaleX = renderStyle.scale
                scaleY = renderStyle.scale
            }
            .then(
                renderStyle.glowColor?.let { glow ->
                    // Layered halo behind the card — three widening, fading strokes read as a
                    // soft bloom without any blur shader (crisp at every zoom level).
                    Modifier.drawBehind {
                        val radius = FlowSizing.nodeCornerRadius.toPx()
                        listOf(2f to 0.38f, 6f to 0.18f, 12f to 0.08f).forEach { (spread, glowAlpha) ->
                            drawRoundRect(
                                color = glow.copy(alpha = glowAlpha),
                                topLeft = Offset(-spread, -spread),
                                size = Size(size.width + spread * 2f, size.height + spread * 2f),
                                cornerRadius = CornerRadius(radius + spread),
                                style = Stroke(width = spread),
                            )
                        }
                    }
                } ?: Modifier,
            )
            .clip(RoundedCornerShape(FlowSizing.nodeCornerRadius))
            .background(backgroundColor, RoundedCornerShape(FlowSizing.nodeCornerRadius))
            .border(FlowSizing.nodeBorderWidth, borderColor, RoundedCornerShape(FlowSizing.nodeCornerRadius))
            .onSizeChanged { size ->
                val dimensions = Dimensions(size.width.toDouble(), size.height.toDouble())
                if (node.measured != dimensions) {
                    onNodesChange?.invoke(listOf(NodeDimensionChange(id = node.id, dimensions = dimensions)))
                }
            }
            .pointerInput(node.id, viewport.zoom, node.draggable, node.selectable) {
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
                                if (!dragged && node.selectable) {
                                    onNodeClick?.invoke(node)
                                } else if (dragged) {
                                    onNodesChange?.invoke(listOf(NodePositionChange(id = node.id, position = currentPosition, dragging = false)))
                                }
                                break
                            }

                            if (!node.draggable) continue

                            val delta = change.position - change.previousPosition
                            if (!dragged) {
                                accumulatedDx += delta.x
                                accumulatedDy += delta.y
                                if ((accumulatedDx * accumulatedDx) + (accumulatedDy * accumulatedDy) < FlowSizing.nodeDragThresholdSquared) {
                                    continue
                                }
                                dragged = true
                                if (node.selectable) {
                                    onNodeClick?.invoke(node)
                                }
                            }

                            var newX = currentPosition.x + delta.x / viewport.zoom
                            var newY = currentPosition.y + delta.y / viewport.zoom

                            if (snapToGrid) {
                                newX = (kotlin.math.round(newX / snapGrid.first) * snapGrid.first)
                                newY = (kotlin.math.round(newY / snapGrid.second) * snapGrid.second)
                            }

                            node.extent?.let { (min, max) ->
                                newX = newX.coerceIn(min.x, max.x)
                                newY = newY.coerceIn(min.y, max.y)
                            }

                            currentPosition = XYPosition(x = newX, y = newY)

                            if (autoPanOnDrag && canvasSize.width > 0 && onPanBy != null) {
                                val edgeMargin = 40f
                                val panSpeed = 10.0
                                val screenX = change.position.x
                                val screenY = change.position.y
                                var panDx = 0.0
                                var panDy = 0.0
                                if (screenX < edgeMargin) panDx = -panSpeed
                                else if (screenX > canvasSize.width - edgeMargin) panDx = panSpeed
                                if (screenY < edgeMargin) panDy = -panSpeed
                                else if (screenY > canvasSize.height - edgeMargin) panDy = panSpeed
                                if (panDx != 0.0 || panDy != 0.0) onPanBy(panDx, panDy)
                            }

                            onNodesChange?.invoke(listOf(NodePositionChange(id = node.id, position = currentPosition, dragging = true)))
                            change.consume()
                        }
                    }
                }
            },
    ) {
        val props = NodeProps(
            id = node.id,
            data = node.data,
            selected = node.selected,
            dragging = node.dragging,
            type = node.type,
            width = width,
            height = height,
            onClick = onNodeClick?.let { callback -> { callback(node) } },
        )
        if (nodeContent != null) nodeContent(props) else DefaultNode(props)

        if (node.connectable) {
            handles.forEach { handle ->
                Handle(
                    modifier = handleModifier(handle, widthDp, heightDp, handleRenderStyle(handle))
                        .pointerInput(node.id, handle.id, handle.type, connectionController) {
                            if (connectionController == null) return@pointerInput
                            awaitPointerEventScope {
                                while (true) {
                                    val down = awaitPointerEvent().changes.firstOrNull { it.pressed } ?: continue
                                    connectionController.startConnection(
                                        nodeId = node.id,
                                        handleId = handle.id,
                                        type = handle.type,
                                        startPosition = down.position,
                                    )
                                    onConnectStart?.invoke()
                                    down.consume()

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) {
                                            connectionController.completeConnection(isValidConnection, onConnect)
                                            onConnectEnd?.invoke()
                                            break
                                        }
                                        connectionController.updateConnectionEnd(change.position)
                                        change.consume()
                                    }
                                }
                            }
                        },
                    type = handle.type,
                    style = handleRenderStyle(handle),
                    onConnect = null,
                )
            }
        } else {
            handles.forEach { handle ->
                Handle(
                    modifier = handleModifier(handle, widthDp, heightDp, handleRenderStyle(handle)),
                    type = handle.type,
                    style = handleRenderStyle(handle),
                    onConnect = null,
                )
            }
        }
    }
}

@Composable
internal fun DefaultNode(props: NodeProps) {
    val title = nodeLabel(props.data)
    Column(modifier = Modifier.fillMaxSize().padding(FlowSizing.nodePadding)) {
        Text(
            text = title,
            color = FlowText,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        props.type?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = FlowText.copy(alpha = FlowVisualDefaults.secondaryTextAlpha),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = FlowSizing.defaultLabelSpacing),
            )
        }
    }
}

internal fun nodeLabel(data: Any?): String = when (data) {
    null -> "Node"
    is String -> data
    is Map<*, *> -> data["label"]?.toString() ?: data.toString()
    else -> data.toString()
}
