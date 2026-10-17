package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ContactPlate(
    depth: Dp,
    color: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier, propagateMinConstraints = true) {
        if (depth > 0.dp) Box(Modifier.matchParentSize().offset(y = depth).clip(shape).background(color))
        content()
    }
}

@Composable
fun MovingFace(
    travel: Dp,
    press: () -> Float,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        Modifier
            .graphicsLayer {
                val progress = press()
                val size = 1f - (1f - scale) * progress
                translationY = travel.toPx() * progress
                scaleX = size
                scaleY = size
            }
            .then(modifier),
        propagateMinConstraints = true,
        content = content,
    )
}
