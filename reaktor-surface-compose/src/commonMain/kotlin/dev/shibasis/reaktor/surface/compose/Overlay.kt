package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.focusGroup
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import dev.shibasis.reaktor.surface.KeyName
import dev.shibasis.reaktor.surface.KeyStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Stable
class OverlayHost internal constructor(private val scope: CoroutineScope) {
    internal val layers = mutableStateListOf<OverlayLayer>()
    internal var origin by mutableStateOf(Offset.Zero)
    internal val content = FocusRequester()
    internal val tips = mutableSetOf<TooltipAnchor>()
    internal val contentFocus = mutableListOf<() -> Unit>()
    private var contentFocused = false
    private var returning = false
    private var warmUntil = Long.MIN_VALUE

    internal fun warm(uptime: Long): Boolean = uptime < warmUntil

    internal fun cool(uptime: Long) {
        warmUntil = uptime + WarmMillis
    }

    internal fun escape(event: KeyEvent): Boolean {
        if (tips.isEmpty() || event.type != KeyEventType.KeyDown || event.stroke() != KeyStroke(KeyName.Escape)) return false
        return tips.toList().map { it.escape() }.any { it }
    }

    internal fun admits(layer: OverlayLayer?): Boolean {
        val above = if (layer == null) 0 else layers.indexOf(layer) + 1
        return (layer == null || above > 0) && (above until layers.size).none { layers[it].modal }
    }

    internal fun contentFocusChanged(hasFocus: Boolean) {
        contentFocused = hasFocus
        if (hasFocus) {
            returning = false
            contentFocus.toList().forEach { it() }
        }
    }

    internal fun contentLeft() {
        returning = content.saveFocusedChild()
    }

    internal fun close(layer: OverlayLayer) {
        layers -= layer
        if (layer.focused) scope.launch {
            withFrameNanos {}
            if (returning && !contentFocused && layers.none { it.focused }) {
                returning = false
                content.restoreFocusedChild()
            }
        }
    }
}

@Stable
internal class OverlayLayer(locals: CompositionLocalContext, modal: Boolean, content: @Composable () -> Unit) {
    var locals by mutableStateOf(locals)
    var modal by mutableStateOf(modal)
    var content by mutableStateOf(content)
    var focused = false
}

val LocalOverlayHost = staticCompositionLocalOf<OverlayHost?> { null }

internal val LocalOverlayOrigin = staticCompositionLocalOf { Offset.Zero }

@Composable
fun OverlayHost(content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val host = remember { OverlayHost(scope) }
    CompositionLocalProvider(LocalOverlayHost provides host) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { host.origin = it.positionInWindow() }.onPreviewKeyEvent(host::escape), propagateMinConstraints = true) {
            val covered = host.layers.any { it.modal }
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)
                    .onFocusChanged { host.contentFocusChanged(it.hasFocus) }
                    .focusRequester(host.content)
                    .focusProperties {
                        onEnter = { if (!host.admits(null)) cancelFocusChange() }
                        onExit = { host.contentLeft() }
                    }
                    .focusGroup(),
                propagateMinConstraints = true,
            ) { content() }
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
                    CompositionLocalProvider(LocalOverlayOrigin provides host.origin) {
                        Box(
                            Modifier
                                .onFocusChanged { layer.focused = it.hasFocus }
                                .focusProperties { onEnter = { if (!host.admits(layer)) cancelFocusChange() } }
                                .focusGroup(),
                            propagateMinConstraints = true,
                        ) { layer.content() }
                    }
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
        onDispose { host.close(layer) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun OverlayBack(enabled: Boolean = true, onBack: () -> Unit) = BackHandler(enabled, onBack)

private const val WarmMillis = 300L

internal object WholeWindow : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}
