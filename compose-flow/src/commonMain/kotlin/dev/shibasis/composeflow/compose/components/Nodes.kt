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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import dev.shibasis.composeflow.compose.primitives.Handle
import dev.shibasis.composeflow.compose.theme.FlowBorder
import dev.shibasis.composeflow.compose.theme.FlowSizing
import dev.shibasis.composeflow.compose.theme.FlowSurface
import dev.shibasis.composeflow.compose.theme.FlowText
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
import dev.shibasis.composeflow.model.Viewport
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
) {
    val density = LocalDensity.current
    val width = node.measured?.width ?: node.width ?: defaultNodeWidthPx
    val height = node.measured?.height ?: node.height ?: defaultNodeHeightPx
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
            .clip(RoundedCornerShape(FlowSizing.nodeCornerRadius))
            .background(backgroundColor, RoundedCornerShape(FlowSizing.nodeCornerRadius))
            .border(FlowSizing.nodeBorderWidth, borderColor, RoundedCornerShape(FlowSizing.nodeCornerRadius))
            .onSizeChanged { size ->
                val dimensions = Dimensions(size.width.toDouble(), size.height.toDouble())
                if (node.measured != dimensions) {
                    onNodesChange?.invoke(listOf(NodeDimensionChange(id = node.id, dimensions = dimensions)))
                }
            }
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
                                    onNodesChange?.invoke(listOf(NodePositionChange(id = node.id, position = currentPosition, dragging = false)))
                                }
                                break
                            }

                            val delta = change.position - change.previousPosition
                            if (!dragged) {
                                accumulatedDx += delta.x
                                accumulatedDy += delta.y
                                if ((accumulatedDx * accumulatedDx) + (accumulatedDy * accumulatedDy) < FlowSizing.nodeDragThresholdSquared) {
                                    continue
                                }
                                dragged = true
                                onNodeClick?.invoke(node)
                            }

                            currentPosition = XYPosition(
                                x = currentPosition.x + delta.x / viewport.zoom,
                                y = currentPosition.y + delta.y / viewport.zoom,
                            )
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
        )
        if (nodeContent != null) nodeContent(props) else DefaultNode(props)

        handles.forEach { handle ->
            Handle(
                modifier = handleModifier(handle, widthDp, heightDp, handleRenderStyle(handle)),
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
                color = FlowText.copy(alpha = 0.7f),
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
