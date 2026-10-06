package dev.shibasis.reaktor.ui.web

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.web.*
import kotlinx.coroutines.flow.*

@Stable
class WebViewState {
    private val mutableSession = MutableStateFlow<WebSession?>(null)
    val session: StateFlow<WebSession?> = mutableSession.asStateFlow()
    private val mutableFailure = MutableStateFlow<Throwable?>(null)
    val failure: StateFlow<Throwable?> = mutableFailure.asStateFlow()
    internal fun attach(session: WebSession?) { mutableSession.value = session; if (session != null) mutableFailure.value = null }
    internal fun fail(error: Throwable?) { reportFailure(error) }
    fun reportFailure(error: Throwable?) { mutableFailure.value = error }
    fun reload() { session.value?.reload() }
    fun back() { session.value?.back() }
    fun forward() { session.value?.forward() }
    fun zoom(factor: Double) { session.value?.zoom(factor) }
    fun focus() { session.value?.focus() }
}
@Composable
fun rememberWebViewState(): WebViewState = remember { WebViewState() }

/** Native surfaces are rectangular. Remove/hide them while showing an overlapping Compose modal. */
@Composable
fun ReaktorWebView(
    runtime: WebRuntime,
    content: WebContent,
    modifier: Modifier = Modifier,
    state: WebViewState = rememberWebViewState(),
    options: WebViewOptions = WebViewOptions(),
    bridge: WebBridge? = null,
    visible: Boolean = true,
) {
    if (visible) PlatformWebView(runtime, content, modifier, state, options, bridge)
}
@Composable
internal expect fun PlatformWebView(runtime: WebRuntime, content: WebContent, modifier: Modifier,
    state: WebViewState, options: WebViewOptions, bridge: WebBridge?)
