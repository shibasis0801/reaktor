package dev.shibasis.reaktor.flow.graph.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.shibasis.composeflow.compose.components.Panel
import dev.shibasis.composeflow.model.PanelPosition
import dev.shibasis.composeflow.model.XYPosition
import dev.shibasis.composeflow.runtime.ReactFlowState
import dev.shibasis.reaktor.flow.graph.layout.DefaultGraphFlowMetrics
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphEdgeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorNodeKind
import kotlin.math.max
import kotlin.math.min

// React Flow and similar editors treat the minimap as a second viewport over the same graph
// coordinate space. Keeping this mapping explicit helps when tuning fit/center math later.
@Composable
internal fun BoxScope.GraphMiniMap(
    flow: ReaktorFlowGraph,
    state: ReactFlowState,
    rightInset: Dp,
) {
    if (flow.nodes.isEmpty()) return

    val density = LocalDensity.current
    val metrics = DefaultGraphFlowMetrics
    val miniMapPaddingPx = with(density) { GraphUi.miniMapInnerPadding.toPx() }
    val miniMapEdgeStrokePx = with(density) { miniMapEdgeStrokePx() }
    val miniMapNodeMinSizePx = with(density) { miniMapNodeMinSizePx() }
    val miniMapNodeCornerPx = with(density) { miniMapNodeCornerPx() }
    val miniMapViewportCornerPx = with(density) { miniMapViewportCornerPx() }
    val miniMapViewportStrokePx = with(density) { miniMapViewportStrokePx() }

    Panel(position = PanelPosition.TopRight, modifier = Modifier.padding(GraphUi.overlayPadding)) {
        Box(
            modifier = Modifier
                .padding(end = rightInset)
                .size(
                    width = GraphUi.miniMapSize.width,
                    height = GraphUi.miniMapSize.height,
                ),
        ) {
            Surface(
                color = GraphUi.miniMapSurface,
                shape = RoundedCornerShape(GraphUi.panelRadius),
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
                                    if (!change.pressed) break
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
                Canvas(Modifier.fillMaxSize().padding(GraphUi.miniMapInnerPadding)) {
                    val bounds = flowBounds(flow)
                    val scale = min(
                        size.width / max(bounds.width.toFloat(), 1f),
                        size.height / max(bounds.height.toFloat(), 1f),
                    )

                    flow.edges.forEach { edge ->
                        val source = flow.nodes.firstOrNull { it.id == edge.source } ?: return@forEach
                        val target = flow.nodes.firstOrNull { it.id == edge.target } ?: return@forEach
                        drawLine(
                            color = ((edge.data as? ReaktorGraphEdgeData)?.kind?.color ?: GraphCanvasMuted).copy(alpha = 0.35f),
                            start = miniMapPoint(source.position.x, source.position.y, bounds.left, bounds.top, scale),
                            end = miniMapPoint(target.position.x, target.position.y, bounds.left, bounds.top, scale),
                            strokeWidth = miniMapEdgeStrokePx,
                        )
                    }

                    flow.nodes.forEach { node ->
                        val width = ((node.width ?: metrics.defaultNodeWidth) * scale).toFloat()
                            .coerceAtLeast(miniMapNodeMinSizePx)
                        val height = ((node.height ?: metrics.defaultNodeHeight) * scale).toFloat()
                            .coerceAtLeast(miniMapNodeMinSizePx)
                        val origin = miniMapPoint(node.position.x, node.position.y, bounds.left, bounds.top, scale)
                        val kind = (node.data as? ReaktorGraphNodeData)?.kind ?: ReaktorNodeKind.Node
                        drawRoundRect(
                            color = kind.borderColor.copy(alpha = 0.65f),
                            topLeft = origin,
                            size = Size(width, height),
                            cornerRadius = CornerRadius(
                                miniMapNodeCornerPx,
                                miniMapNodeCornerPx,
                            ),
                        )
                    }

                    val viewportLeft = ((-state.viewport.x / state.viewport.zoom) - bounds.left) * scale
                    val viewportTop = ((-state.viewport.y / state.viewport.zoom) - bounds.top) * scale
                    val viewportWidth = (state.canvasSize.width / state.viewport.zoom * scale).toFloat()
                    val viewportHeight = (state.canvasSize.height / state.viewport.zoom * scale).toFloat()
                    drawRoundRect(
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.26f),
                        topLeft = Offset(viewportLeft.toFloat(), viewportTop.toFloat()),
                        size = Size(viewportWidth, viewportHeight),
                        cornerRadius = CornerRadius(
                            miniMapViewportCornerPx,
                            miniMapViewportCornerPx,
                        ),
                        style = Stroke(miniMapViewportStrokePx),
                    )
                }
            }
        }
    }
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
