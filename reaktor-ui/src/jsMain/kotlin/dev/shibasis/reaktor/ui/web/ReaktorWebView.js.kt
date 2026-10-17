package dev.shibasis.reaktor.ui.web
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import dev.shibasis.reaktor.web.*

@Composable
internal actual fun PlatformWebView(runtime: WebRuntime, content: WebContent, modifier: Modifier,
    state: WebViewState, options: WebViewOptions, bridge: WebBridge?) {
    val global: dynamic = js("globalThis")
    val density = LocalDensity.current.density
    val container: dynamic = remember(runtime, options, bridge) {
        global.document.createElement("div").also { element: dynamic ->
            element.style.position = "fixed"
            element.style.overflow = "hidden"
            element.style.display = "none"
            global.document.body.appendChild(element)
        }
    }
    val host = remember(container) { BrowserWebViewHost(container) }
    val session = remember(host) { runCatching { runtime.open(host, options, bridge) }.onFailure(state::fail).getOrNull() }
    DisposableEffect(session, state) {
        state.attach(session)
        onDispose { session?.close(); container.remove(); state.attach(null) }
    }
    LaunchedEffect(session, content) { runCatching { session?.load(content) }.onFailure(state::fail) }
    Box(modifier.onGloballyPositioned { layout ->
        val rect = layout.boundsInWindow()
        container.style.left = (rect.left / density).toString() + "px"
        container.style.top = (rect.top / density).toString() + "px"
        container.style.width = (rect.width / density).toString() + "px"
        container.style.height = (rect.height / density).toString() + "px"
        container.style.display = if (rect.width > 0 && rect.height > 0 && session != null) "block" else "none"
    })
}
