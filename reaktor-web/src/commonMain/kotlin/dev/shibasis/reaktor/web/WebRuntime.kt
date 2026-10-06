package dev.shibasis.reaktor.web

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

interface WebViewHost { fun create(): WebViewController }

interface WebViewController : AutoCloseable {
    val features: Set<WebFeature> get() = emptySet()
    fun configure(context: WebHostContext) {}
    fun load(content: WebContent)
    fun executeJavaScript(script: String)
    fun focus(): Unit = throw IllegalStateException("Focus is unavailable on this host")
    suspend fun evaluate(script: String): JsonElement = throw WebUnavailable(WebFeature.Evaluation, "JavaScript results are unavailable")
    suspend fun postMessage(body: String) { evaluate("window.__reaktorReceive?.($body); null") }
    fun configureBridge(configuration: JsonObject) {
        executeJavaScript("window.__reaktorConfig = $configuration; window.__reaktorConnect?.(window.__reaktorConfig)")
    }
    fun reload(): Unit = throw WebUnavailable(WebFeature.Navigation, "Reload is unavailable")
    fun back(): Unit = throw WebUnavailable(WebFeature.History, "History is unavailable")
    fun forward(): Unit = throw WebUnavailable(WebFeature.History, "History is unavailable")
    fun zoom(factor: Double): Unit = throw WebUnavailable(WebFeature.Zoom, "Zoom is unavailable")
    fun openDevTools(): Unit = throw WebUnavailable(WebFeature.DevTools, "Developer inspection is unavailable")
}

data class WebSessionState(
    val requestedContent: WebContent? = null,
    val committedUrl: String? = null,
    val title: String = "",
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val zoom: Double = 1.0,
    val epoch: Long = 0,
    val error: String? = null,
    val rendererLosses: Int = 0,
    val closed: Boolean = false,
)

data class WebDownload(val url: String, val suggestedName: String, val mimeType: String)

interface WebHostContext {
    val options: WebViewOptions
    fun allowsNavigation(url: String): Boolean
    fun navigationStarted(url: String?)
    fun navigationCommitted(url: String, title: String, back: Boolean, forward: Boolean)
    fun navigationFinished(error: String? = null)
    fun rendererTerminated()
    fun message(body: String, sourceUrl: String, mainFrame: Boolean)
    fun download(request: WebDownload)
}

class WebSession internal constructor(
    private val controller: WebViewController,
    override val options: WebViewOptions,
    private val bridge: WebBridge?,
    private val onClosed: (WebSession) -> Unit,
) : AutoCloseable, WebHostContext {
    val id: String = Uuid.random().toString()
    val features: Set<WebFeature> get() = controller.features
    private val mutableState = MutableStateFlow(WebSessionState())
    val state: StateFlow<WebSessionState> = mutableState.asStateFlow()
    private val mutableDownloads = MutableSharedFlow<WebDownload>(extraBufferCapacity = 8)
    val downloads: SharedFlow<WebDownload> = mutableDownloads.asSharedFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connection = MutableStateFlow<WebBridgeConnection?>(null)
    private val activeApp = MutableStateFlow<WebApp?>(null)

    init { controller.configure(this) }

    fun load(content: WebContent) {
        checkOpen()
        if (content is WebContent.Bundle) {
            requireFeature(WebFeature.Bundles)
            content.app.requiredFeatures.forEach(::requireFeature)
            if (WebFeature.Bridge in content.app.requiredFeatures && bridge == null)
                throw WebUnavailable(WebFeature.Bridge, "This app requires an explicitly bound graph bridge")
        }
        if (content is WebContent.Url) require(options.policy.allows(content.url)) { "Navigation denied" }
        connection.getAndUpdate { null }?.close()
        activeApp.value = (content as? WebContent.Bundle)?.app
        mutableState.update { it.copy(requestedContent = content, loading = true, error = null) }
        try { controller.load(content) } catch (error: Throwable) {
            navigationFinished(error.message ?: "Load failed")
            throw error
        }
    }

    fun executeJavaScript(script: String) {
        checkOpen()
        require('\u0000' !in script)
        controller.executeJavaScript(script)
    }
    suspend fun evaluate(script: String): JsonElement {
        checkOpen()
        requireFeature(WebFeature.Evaluation)
        require('\u0000' !in script)
        val epoch = state.value.epoch
        val result = withTimeout(15_000) { controller.evaluate(script) }
        check(!state.value.closed && epoch == state.value.epoch) { "The document changed during evaluation" }
        return result
    }
    fun reload() { checkOpen(); requireFeature(WebFeature.Navigation); controller.reload() }
    fun focus() { checkOpen(); controller.focus() }
    fun back() { checkOpen(); requireFeature(WebFeature.History); controller.back() }
    fun forward() { checkOpen(); requireFeature(WebFeature.History); controller.forward() }
    fun zoom(factor: Double) {
        checkOpen()
        requireFeature(WebFeature.Zoom)
        require(factor.isFinite() && factor in 0.25..5.0)
        controller.zoom(factor)
        mutableState.update { it.copy(zoom = factor) }
    }
    fun openDevTools() {
        checkOpen()
        check(options.debug) { "Developer inspection is disabled" }
        requireFeature(WebFeature.DevTools)
        controller.openDevTools()
    }
    fun requireFeature(feature: WebFeature) {
        if (feature !in features) throw WebUnavailable(feature, "This host does not support $feature")
    }
    private fun checkOpen() { check(!state.value.closed) { "Web session is closed" } }

    override fun allowsNavigation(url: String): Boolean {
        val app = activeApp.value
        if (app != null) return webOrigin(url) == app.origin && url.startsWith(app.origin + "/" + app.revision + "/")
        if (state.value.requestedContent is WebContent.Html && (url == "about:blank" || url.startsWith("about:blank#"))) return true
        return options.policy.allows(url)
    }
    override fun navigationStarted(url: String?) {
        if (state.value.closed) return
        connection.getAndUpdate { null }?.close()
        mutableState.update { it.copy(epoch = it.epoch + 1, loading = true, error = null, committedUrl = null) }
    }
    override fun navigationCommitted(url: String, title: String, back: Boolean, forward: Boolean) {
        if (state.value.closed) return
        if (!allowsNavigation(url)) {
            navigationFinished("Committed navigation is outside the session policy")
            return
        }
        mutableState.update { it.copy(committedUrl = url, title = title, canGoBack = back, canGoForward = forward) }
        val app = activeApp.value ?: return
        if (bridge != null && WebFeature.Bridge in features && webOrigin(url) == app.origin && connection.value == null) {
            val epoch = state.value.epoch
            val opened = bridge.connect(id, epoch, app, scope) { body ->
                if (!state.value.closed && state.value.epoch == epoch) controller.postMessage(body)
            }
            if (!connection.compareAndSet(null, opened)) { opened.close(); return }
            if (state.value.closed || state.value.epoch != epoch) {
                connection.compareAndSet(opened, null)
                opened.close()
            } else controller.configureBridge(opened.configuration())
        }
    }
    override fun navigationFinished(error: String?) {
        if (!state.value.closed) mutableState.update { it.copy(loading = false, error = error) }
    }
    override fun rendererTerminated() {
        if (state.value.closed) return
        connection.getAndUpdate { null }?.close()
        val losses = state.value.rendererLosses + 1
        mutableState.update { it.copy(rendererLosses = losses, loading = false, error = "Web renderer terminated") }
        if (losses <= 2) scope.launch {
            delay(250)
            if (!state.value.closed) runCatching { controller.reload() }
        }
    }
    override fun message(body: String, sourceUrl: String, mainFrame: Boolean) {
        if (!state.value.closed && mainFrame && sourceUrl == state.value.committedUrl) connection.value?.receive(body)
    }
    override fun download(request: WebDownload) {
        if (!state.value.closed && allowsNavigation(request.url)) mutableDownloads.tryEmit(request)
    }
    override fun close() {
        if (mutableState.getAndUpdate { it.copy(closed = true, loading = false) }.closed) return
        connection.getAndUpdate { null }?.close()
        scope.cancel()
        try { controller.close() } finally { onClosed(this) }
    }
}

class WebRuntime(graph: Graph) : BasicNode(graph) {
    private val sessions = MutableStateFlow<List<WebSession>?>(emptyList())
    fun open(host: WebViewHost, options: WebViewOptions = WebViewOptions(), bridge: WebBridge? = null): WebSession {
        check(sessions.value != null) { "Web runtime is closed" }
        val controller = host.create()
        val session = try { WebSession(controller, options, bridge) { closed ->
            sessions.getAndUpdate { it?.minus(closed) }
        } } catch (error: Throwable) {
            runCatching { controller.close() }
            throw error
        }
        while (true) {
            val current = sessions.value
            if (current == null) {
                session.close()
                error("Web runtime closed while creating the view")
            }
            if (sessions.compareAndSet(current, current + session)) return session
        }
    }
    override fun close() {
        val active = sessions.getAndUpdate { null } ?: return
        try {
            var failure: Throwable? = null
            active.forEach { session ->
                try { session.close() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        } finally { super.close() }
    }
}
