package dev.shibasis.composeflow.compose.primitives

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shibasis.composeflow.model.Position

data class NodeProps(
    val id: String,
    val data: Any?,
    val selected: Boolean,
    val dragging: Boolean,
    val type: String?,
    val width: Double,
    val height: Double,
    /** Semantic activation for custom node renderers; pointer input stays owned by the flow canvas. */
    val onClick: (() -> Unit)?,
)

data class NodeRenderStyle(
    val alpha: Float = 1f,
    val scale: Float = 1f,
    val backgroundColor: Color? = null,
    val borderColor: Color? = null,
    // Layered outer halo drawn behind the card (selection/attention bloom). Null = no glow.
    val glowColor: Color? = null,
)

data class EdgeRenderStyle(
    val alpha: Float = 1f,
    val color: Color? = null,
    val width: Float? = null,
    // Soft under-stroke drawn beneath the wire (selection/attention bloom). Null = no glow.
    val glowColor: Color? = null,
    val glowWidth: Float? = null,
    // Dashed wire pattern; when [flowAnimated] the dash phase marches along the edge.
    val dashOn: Float? = null,
    val dashOff: Float? = null,
    val flowAnimated: Boolean = false,
)

enum class EdgePathStyle {
    Bezier,
    Orthogonal,
    Straight,
    SmoothStep,
    SimpleBezier,
}

data class HandleRenderStyle(
    val fillColor: Color? = null,
    val borderColor: Color? = null,
    val alpha: Float = 1f,
    val size: Dp = 12.dp,
)

internal data class FlowAnchor(
    val point: Offset,
    val position: Position,
)

typealias NodeContent = @Composable BoxScope.(NodeProps) -> Unit

typealias NodeTypes = Map<String, NodeContent>
