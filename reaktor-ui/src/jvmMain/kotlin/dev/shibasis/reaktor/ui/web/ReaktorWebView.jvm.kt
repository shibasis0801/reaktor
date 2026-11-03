package dev.shibasis.reaktor.ui.web
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.colors
import dev.shibasis.reaktor.web.*

@Composable
internal actual fun PlatformWebView(runtime: WebRuntime, content: WebContent, modifier: Modifier,
    state: WebViewState, options: WebViewOptions, bridge: WebBridge?) {
    val panel = remember(runtime, options, bridge) { DesktopWebView(runtime, content, options, bridge) }
    val session by panel.sessionState.collectAsState()
    val page = session?.state?.collectAsState()?.value ?: WebSessionState(loading = true)
    LaunchedEffect(panel, state) { panel.sessionState.collect { state.attach(it) } }
    LaunchedEffect(panel, state) { panel.failures.collect { state.fail(it) } }
    LaunchedEffect(panel, content) { panel.load(content) }
    DisposableEffect(panel, state) { onDispose { panel.close(); state.attach(null) } }
    key(panel) {
        WebViewSurface(page, modifier) { visible, color ->
            val awtBackground = java.awt.Color(color.toArgb(), true)
            SwingPanel(
                background = color,
                factory = { panel.apply { this.background = awtBackground; contentVisible = visible } },
                modifier = Modifier.fillMaxSize(),
                update = { it.background = awtBackground; it.contentVisible = visible },
            )
        }
    }
}

@Composable
internal fun WebViewSurface(
    page: WebSessionState,
    modifier: Modifier = Modifier,
    content: @Composable (Boolean, Color) -> Unit,
) {
    var presented by remember { mutableStateOf(false) }
    val ready = page.requestedContent != null && page.committedUrl != null &&
        !page.loading && page.error == null && !page.closed
    SideEffect { if (ready) presented = true }
    Box(modifier.background(MachineSignal.colors.canvas)) {
        content(presented || ready, MachineSignal.colors.canvas)
    }
}
