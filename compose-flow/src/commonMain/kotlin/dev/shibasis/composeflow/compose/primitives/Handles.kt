package dev.shibasis.composeflow.compose.primitives

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shibasis.composeflow.compose.theme.FlowSizing
import dev.shibasis.composeflow.compose.theme.FlowHandleBorder
import dev.shibasis.composeflow.compose.theme.FlowHandleSource
import dev.shibasis.composeflow.compose.theme.FlowHandleTarget
import dev.shibasis.composeflow.model.Handle
import dev.shibasis.composeflow.model.HandleType
import dev.shibasis.composeflow.model.Node
import dev.shibasis.composeflow.model.Position
import dev.shibasis.composeflow.runtime.FlowRuntimeDefaults

@Composable
fun Handle(
    modifier: Modifier = Modifier,
    type: HandleType,
    style: HandleRenderStyle = HandleRenderStyle(),
    onConnect: (() -> Unit)? = null,
) {
    val fill = style.fillColor ?: if (type == HandleType.Source) FlowHandleSource else FlowHandleTarget
    val border = style.borderColor ?: FlowHandleBorder
    Box(
        modifier = modifier
            .size(style.size)
            .graphicsLayer { alpha = style.alpha }
            .clip(CircleShape)
            .background(fill)
            .border(FlowSizing.handleBorderWidth, border, CircleShape)
            .clickable(enabled = onConnect != null) { onConnect?.invoke() },
    )
}

internal fun anchorFor(
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
    val normalizedOffset = (handle.offset ?: FlowRuntimeDefaults.defaultHandleOffset)
        .coerceIn(FlowRuntimeDefaults.minHandleOffset, FlowRuntimeDefaults.maxHandleOffset)
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
    return FlowAnchor(point = Offset(x.toFloat(), y.toFloat()), position = handle.position)
}

internal fun defaultHandle(node: Node, type: HandleType): Handle = Handle(
    id = if (type == HandleType.Source) "source" else "target",
    type = type,
    position = if (type == HandleType.Source) node.sourcePosition else node.targetPosition,
    offset = FlowRuntimeDefaults.defaultHandleOffset,
)

internal fun resolvedHandles(node: Node): List<Handle> =
    node.handles.ifEmpty {
        if (!node.showDefaultHandles) emptyList() else listOf(
            defaultHandle(node, HandleType.Target),
            defaultHandle(node, HandleType.Source),
        )
    }

internal fun handleModifier(
    handle: Handle,
    width: Dp,
    height: Dp,
    style: HandleRenderStyle,
): Modifier {
    val offset = (handle.offset ?: FlowRuntimeDefaults.defaultHandleOffset)
        .coerceIn(FlowRuntimeDefaults.minHandleOffset, FlowRuntimeDefaults.maxHandleOffset)
        .toFloat()
    val half = style.size / 2
    val inset = (handle.inset ?: 0.0).dp
    return when (handle.position) {
        Position.Left -> Modifier.offset(x = inset - half, y = (height * offset) - half)
        Position.Right -> Modifier.offset(x = width - inset - half, y = (height * offset) - half)
        Position.Top -> Modifier.offset(x = (width * offset) - half, y = inset - half)
        Position.Bottom -> Modifier.offset(x = (width * offset) - half, y = height - inset - half)
    }
}
