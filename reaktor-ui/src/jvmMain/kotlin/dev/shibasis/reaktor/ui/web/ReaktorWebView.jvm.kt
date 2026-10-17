package dev.shibasis.reaktor.ui.web
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import dev.shibasis.reaktor.web.*

@Composable
internal actual fun PlatformWebView(runtime: WebRuntime, content: WebContent, modifier: Modifier,
    state: WebViewState, options: WebViewOptions, bridge: WebBridge?) {
    val panel = remember(runtime, options, bridge) { DesktopWebView(runtime, content, options, bridge) }
    LaunchedEffect(panel, state) { panel.sessionState.collect { state.attach(it) } }
    LaunchedEffect(panel, state) { panel.failures.collect { state.fail(it) } }
    LaunchedEffect(panel, content) { panel.load(content) }
    DisposableEffect(panel, state) { onDispose { panel.close(); state.attach(null) } }
    key(panel) { SwingPanel(factory = { panel }, modifier = modifier) }
}
