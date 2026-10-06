@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.shibasis.reaktor.ui.web
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import dev.shibasis.reaktor.web.*

@Composable
internal actual fun PlatformWebView(runtime: WebRuntime, content: WebContent, modifier: Modifier,
    state: WebViewState, options: WebViewOptions, bridge: WebBridge?) {
    val host = remember(runtime, options, bridge) { DarwinWebViewHost() }
    val session = remember(host) { runCatching { runtime.open(host, options, bridge) }.onFailure(state::fail).getOrNull() }
    DisposableEffect(session, state) { state.attach(session); onDispose { session?.close(); state.attach(null) } }
    LaunchedEffect(session, content) { runCatching { session?.load(content) }.onFailure(state::fail) }
    if (session != null) key(host) { UIKitView(factory = { host.view }, modifier = modifier) }
}
