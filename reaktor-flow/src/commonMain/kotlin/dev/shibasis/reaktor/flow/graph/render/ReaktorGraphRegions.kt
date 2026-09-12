package dev.shibasis.reaktor.flow.graph.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.shibasis.composeflow.model.XYPosition
import dev.shibasis.reaktor.flow.graph.model.ReaktorFlowGraph
import dev.shibasis.reaktor.flow.graph.model.ReaktorGraphNodeData
import dev.shibasis.reaktor.flow.graph.model.ReaktorScopeDisclosure
import dev.shibasis.reaktor.flow.graph.style.DefaultReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.ReaktorGraphStyle
import dev.shibasis.reaktor.flow.graph.style.defaultNodeHeight
import dev.shibasis.reaktor.flow.graph.style.defaultNodeWidth
import dev.shibasis.reaktor.flow.graph.style.dpOf
import dev.shibasis.reaktor.flow.graph.style.readablePadding
import dev.shibasis.reaktor.flow.graph.style.regionLabelOffset
import dev.shibasis.reaktor.flow.graph.style.regionSelectedLabelSurface
import dev.shibasis.reaktor.flow.graph.style.spOf
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun BoxScope.GraphRegionsOverlay(
    flow: ReaktorFlowGraph,
    selectedGraphId: String?,
    onSelectGraph: (String?) -> Unit,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
    scopeDisclosure: ReaktorScopeDisclosure? = null,
) {
    val density = LocalDensity.current
    val regionLabelOffset = style.regionLabelOffset()
    val labelPaddingX = with(density) { dpOf(style.region.labelPaddingXPx) }
    val labelPaddingY = with(density) { dpOf(style.region.labelPaddingYPx) }
    // A folded scope is already a card that carries its own name. Framing that one card in a
    // dashed boundary and captioning it with the same word again is the same thing said three
    // times, so the boundary is drawn only for scopes that actually contain something.
    val folded = flow.nodes.mapNotNullTo(mutableSetOf()) { node ->
        node.id.takeIf { (node.data as? ReaktorGraphNodeData)?.isScopeSummary == true }
    }
    Canvas(Modifier.fillMaxSize()) {
        flow.regions.sortedBy { it.depth }.filterNot { it.id in folded }.forEach { region ->
            val topLeft = Offset(region.x.toFloat(), region.y.toFloat())
            val size = Size(region.width.toFloat(), region.height.toFloat())
            drawRoundRect(
                color = region.color.copy(alpha = style.region.fillAlpha),
                topLeft = topLeft,
                size = size,
                cornerRadius = CornerRadius(
                    style.region.cornerRadiusPx.toFloat(),
                    style.region.cornerRadiusPx.toFloat(),
                ),
            )
            drawRoundRect(
                color = region.color.copy(alpha = style.region.strokeAlpha),
                topLeft = topLeft,
                size = size,
                cornerRadius = CornerRadius(
                    style.region.cornerRadiusPx.toFloat(),
                    style.region.cornerRadiusPx.toFloat(),
                ),
                style = Stroke(
                    width = if (selectedGraphId == region.id) {
                        style.region.selectedStrokeWidthPx.toFloat()
                    } else {
                        style.region.strokeWidthPx.toFloat()
                    },
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(
                            style.region.dashOnPx.toFloat(),
                            style.region.dashOffPx.toFloat(),
                        ),
                    ),
                ),
            )
        }
    }

    flow.regions.sortedBy { it.depth }.filterNot { it.id in folded }.forEach { region ->
        // An expanded scope's only handle on the canvas is its name, so the name is the control:
        // clicking it folds the scope back into a card, which unfolds on its own next click.
        val fold = scopeDisclosure?.takeIf {
            region.id != flow.focusedScopeId && flow.scopes[region.id]?.parentId != null
        }
        val labelColor = region.color.copy(alpha = if (selectedGraphId == region.id) 0.96f else 0.72f)
        Surface(
            color = if (selectedGraphId == region.id) {
                style.regionSelectedLabelSurface()
            } else {
                style.canvas.regionLabelSurface
            },
            shape = RoundedCornerShape(with(density) { dpOf(style.region.labelRadiusPx) }),
            tonalElevation = 0.dp,
            modifier = Modifier.offset {
                IntOffset(
                    x = (region.x + regionLabelOffset.x).roundToInt(),
                    y = (region.y + regionLabelOffset.y).roundToInt(),
                )
            },
        ) {
            Text(
                text = region.label,
                color = labelColor,
                fontSize = with(density) { spOf(style.chrome.titleFontPx) },
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .then(
                        fold?.let { scope ->
                            Modifier
                                .testTag("reaktor-graph-region-toggle-${region.id}")
                                .clickable(role = Role.Button) { scope.onToggle(region.id) }
                                .semantics { contentDescription = "Collapse scope ${region.label}" }
                        } ?: Modifier.clickable { onSelectGraph(region.id) },
                    )
                    .padding(horizontal = labelPaddingX, vertical = labelPaddingY),
            )
        }
    }
}

/**
 * Where a scope's content begins in graph space, in whichever form it is currently drawn: the
 * boundary card when folded, the region's content origin when expanded. Disclosure uses this as
 * a fixed point so unfolding grows the graph under the pointer instead of moving the camera.
 */
internal fun ReaktorFlowGraph.scopeOrigin(scopeId: String, style: ReaktorGraphStyle): XYPosition? =
    nodes.firstOrNull { it.id == scopeId }?.position
        ?: regions.firstOrNull { it.id == scopeId }?.let { region ->
            XYPosition(region.x + style.region.boundsInsetXPx, region.y + style.region.boundsInsetTopPx)
        }

internal data class FlowBounds(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
)

internal fun flowBounds(
    flow: ReaktorFlowGraph,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
): FlowBounds {
    val left = min(
        flow.nodes.minOfOrNull { it.position.x } ?: 0.0,
        flow.regions.minOfOrNull { it.x } ?: Double.POSITIVE_INFINITY,
    ).takeUnless(Double::isInfinite) ?: 0.0
    val top = min(
        flow.nodes.minOfOrNull { it.position.y } ?: 0.0,
        flow.regions.minOfOrNull { it.y } ?: Double.POSITIVE_INFINITY,
    ).takeUnless(Double::isInfinite) ?: 0.0
    val right = max(
        flow.nodes.maxOfOrNull { it.position.x + (it.width ?: style.defaultNodeWidth()) } ?: 1.0,
        flow.regions.maxOfOrNull { it.x + it.width } ?: 1.0,
    )
    val bottom = max(
        flow.nodes.maxOfOrNull { it.position.y + (it.height ?: style.defaultNodeHeight()) } ?: 1.0,
        flow.regions.maxOfOrNull { it.y + it.height } ?: 1.0,
    )
    return FlowBounds(left = left, top = top, width = right - left, height = bottom - top)
}

internal fun readableFlowBounds(
    flow: ReaktorFlowGraph,
    style: ReaktorGraphStyle = DefaultReaktorGraphStyle,
): FlowBounds {
    val padding = style.readablePadding()
    val readableNodes = flow.nodes.filter { node ->
        val data = node.data as? ReaktorGraphNodeData
        data?.isRootNode == true ||
            data?.title == "/" ||
            data?.title == "/onboarding" ||
            data?.title == "StartScreen"
    }.ifEmpty {
        flow.nodes.take(10).ifEmpty { flow.nodes }
    }
    val nodeLeft = readableNodes.minOfOrNull { it.position.x } ?: 0.0
    val nodeTop = readableNodes.minOfOrNull { it.position.y } ?: 0.0
    val nodeRight = readableNodes.maxOfOrNull {
        it.position.x + (it.width ?: style.defaultNodeWidth())
    } ?: style.defaultNodeWidth()
    val nodeBottom = readableNodes.maxOfOrNull {
        it.position.y + (it.height ?: style.defaultNodeHeight())
    } ?: style.defaultNodeHeight()
    return FlowBounds(
        left = nodeLeft - padding.horizontal,
        top = nodeTop - padding.vertical,
        width = (nodeRight - nodeLeft) + padding.horizontal * 2.0,
        height = (nodeBottom - nodeTop) + padding.vertical * 2.0,
    )
}
