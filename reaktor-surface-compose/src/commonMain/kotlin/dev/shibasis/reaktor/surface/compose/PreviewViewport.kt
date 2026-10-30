package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Stable
class PreviewZoomState(initialScale: Float? = null) {
    private var manualScale by mutableStateOf(initialScale?.let(::boundedScale))
    private var fittedScale by mutableFloatStateOf(1f)
    val scale: Float get() = manualScale ?: fittedScale
    val fitting: Boolean get() = manualScale == null

    fun zoomTo(scale: Float) { manualScale = boundedScale(scale) }
    fun zoomBy(amount: Float) { zoomTo(scale + amount) }
    fun fit() { manualScale = null }
    internal fun fitted(scale: Float) { fittedScale = scale }

    private fun boundedScale(scale: Float): Float {
        require(scale.isFinite() && scale > 0f) { "Preview scale must be finite and positive" }
        return scale.coerceIn(.1f, 4f)
    }
}

@Composable
fun rememberPreviewZoomState(initialScale: Float? = null): PreviewZoomState = remember { PreviewZoomState(initialScale) }

@Composable
fun PreviewViewport(
    state: PreviewZoomState,
    contentSize: DpSize,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    require(contentSize.width.value.isFinite() && contentSize.height.value.isFinite() && contentSize.width.value > 0f && contentSize.height.value > 0f) {
        "Preview content dimensions must be finite and positive"
    }
    val density = LocalDensity.current
    val width = with(density) { contentSize.width.roundToPx().coerceAtLeast(1) }
    val height = with(density) { contentSize.height.roundToPx().coerceAtLeast(1) }
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    BoxWithConstraints(modifier.clipToBounds().semantics { stateDescription = "${(state.scale * 100).roundToInt()}%${if (state.fitting) " · Fit" else ""}" }) {
        require(constraints.hasBoundedWidth && constraints.hasBoundedHeight) { "Preview viewport needs bounded dimensions" }
        val viewportWidth = constraints.maxWidth
        val viewportHeight = constraints.maxHeight
        val fit = min(1f, min(viewportWidth.toFloat() / width, viewportHeight.toFloat() / height)).coerceAtLeast(Float.MIN_VALUE)
        val scale = if (state.fitting) fit else state.scale
        SideEffect { state.fitted(fit) }
        Layout(content = { Box { content() } }, modifier = Modifier.horizontalScroll(horizontal).verticalScroll(vertical)) { children, _ ->
            val child = children.single().measure(Constraints.fixed(width, height))
            val scaledWidth = (width * scale).roundToInt()
            val scaledHeight = (height * scale).roundToInt()
            val areaWidth = max(viewportWidth, scaledWidth)
            val areaHeight = max(viewportHeight, scaledHeight)
            layout(areaWidth, areaHeight) {
                child.placeWithLayer((areaWidth - scaledWidth) / 2, (areaHeight - scaledHeight) / 2) {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        }
    }
}
