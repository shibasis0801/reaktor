package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

@Stable
class OverlayHost internal constructor() {
    internal val layers = mutableStateListOf<OverlayLayer>()
    internal var origin by mutableStateOf(Offset.Zero)
}

@Stable
internal class OverlayLayer(locals: CompositionLocalContext, modal: Boolean, content: @Composable () -> Unit) {
    var locals by mutableStateOf(locals)
    var modal by mutableStateOf(modal)
    var content by mutableStateOf(content)
}

val LocalOverlayHost = staticCompositionLocalOf<OverlayHost?> { null }

internal val LocalOverlayOrigin = staticCompositionLocalOf { Offset.Zero }

@Composable
fun OverlayHost(content: @Composable () -> Unit) {
    val host = remember { OverlayHost() }
    CompositionLocalProvider(LocalOverlayHost provides host) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { host.origin = it.positionInWindow() }, propagateMinConstraints = true) {
            val covered = host.layers.any { it.modal }
            Box(Modifier.fillMaxSize().then(if (covered) Modifier.clearAndSetSemantics {} else Modifier), propagateMinConstraints = true) { content() }
            if (host.layers.isNotEmpty()) Layers(host)
        }
    }
}

@Composable
private fun Layers(host: OverlayHost) {
    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        val placeables = host.layers.toList().flatMap { layer ->
            subcompose(layer) {
                CompositionLocalProvider(layer.locals) {
                    CompositionLocalProvider(LocalOverlayOrigin provides host.origin) { layer.content() }
                }
            }.map { it.measure(constraints) }
        }
        layout(constraints.maxWidth, constraints.maxHeight) { placeables.forEach { it.place(0, 0) } }
    }
}

@Composable
fun Overlay(modal: Boolean = true, content: @Composable () -> Unit) {
    val host = LocalOverlayHost.current
    if (host == null) {
        Popup(popupPositionProvider = WholeWindow, properties = PopupProperties(focusable = true, dismissOnClickOutside = false)) { content() }
        return
    }
    val locals = currentCompositionLocalContext
    val layer = remember(host) { OverlayLayer(locals, modal, content) }
    SideEffect {
        layer.locals = locals
        layer.modal = modal
        layer.content = content
    }
    DisposableEffect(host, layer) {
        host.layers += layer
        onDispose { host.layers -= layer }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun OverlayBack(enabled: Boolean = true, onBack: () -> Unit) = BackHandler(enabled, onBack)

internal fun anchoredOffset(anchor: IntRect, canvas: IntSize, content: IntSize, gap: Int, layoutDirection: LayoutDirection): IntOffset {
    val below = anchor.bottom + gap
    val y = if (below + content.height <= canvas.height) below else (anchor.top - gap - content.height).coerceAtLeast(0)
    val start = if (layoutDirection == LayoutDirection.Ltr) anchor.right - content.width else anchor.left
    return IntOffset(start.coerceIn(0, (canvas.width - content.width).coerceAtLeast(0)), y)
}

internal object WholeWindow : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}
