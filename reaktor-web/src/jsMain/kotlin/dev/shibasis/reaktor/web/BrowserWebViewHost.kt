package dev.shibasis.reaktor.web

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

/** Bundle deployments must use an origin dedicated to the app and revision. */
class BrowserWebViewHost(private val container: dynamic) : WebViewHost {
    override fun create(): WebViewController = BrowserWebController(container)
}

private class BrowserWebController(container: dynamic) : WebViewController {
    private val global: dynamic = js("globalThis")
    private val frame: dynamic = global.document.createElement("iframe")
    private lateinit var context: WebHostContext
    private var app: WebApp? = null
    private var desiredContent: WebContent? = null
    private var origin: String? = null
    private var token: String? = null
    private var port: dynamic = null
    private var configuration: JsonObject? = null
    private var closed = false
    private var loaded = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val acknowledgements = mutableMapOf<String, CompletableDeferred<Unit>>()
    override val features = setOf(WebFeature.Bundles, WebFeature.Bridge, WebFeature.Navigation, WebFeature.Zoom, WebFeature.BrowserStorage)
    private val listener: (dynamic) -> Unit = { event ->
        val data = event.data
        if (!closed && app != null && port == null && event.source === frame.contentWindow &&
            event.origin == origin && data?.kind == "reaktor-bundle-ready" && data.token == token &&
            event.ports.length == 1) {
            port = event.ports[0]
            port.onmessage = { reply: dynamic ->
                if (!closed) when (reply.data?.kind as? String) {
                    "request" -> (reply.data.body as? String)?.let { body ->
                        app?.let { context.message(body, it.origin + "/" + it.revision + it.entrypoint, true) }
                    }
                    "ack" -> acknowledgements.remove(reply.data.id as? String)?.complete(Unit)
                }
            }
            port.start()
            val current = requireNotNull(app)
            context.navigationCommitted(current.origin + "/" + current.revision + current.entrypoint, current.id, false, false)
            context.navigationFinished()
        }
    }
    init {
        frame.style.width = "100%"; frame.style.height = "100%"; frame.style.border = "0"
        frame.setAttribute("title", "Embedded web application")
        frame.setAttribute("referrerpolicy", "no-referrer")
        frame.setAttribute("sandbox", "allow-scripts")
        frame.setAttribute("allow", "camera 'none'; microphone 'none'; geolocation 'none'; clipboard-read 'none'; clipboard-write 'none'")
        frame.onload = {
            if (!closed && this::context.isInitialized && desiredContent != null) {
                if (loaded) {
                    revoke(); token = null
                    context.navigationStarted(null)
                    context.navigationFinished("Iframe document changed; load its WebApp again to reauthorize")
                } else {
                    loaded = true
                    if (app == null) context.navigationFinished()
                }
            }
        }
        container.appendChild(frame)
        global.addEventListener("message", listener)
    }
    override fun configure(context: WebHostContext) {
        this.context = context
        if (context.options.profile != WebProfile.Browser)
            throw WebUnavailable(WebFeature.BrowserStorage, "Choose WebProfile.Browser to use the browser's storage and cookie partitioning")
    }
    private fun revoke() {
        port?.postMessage(js("({kind:'close'})")); port?.close(); port = null; configuration = null
        acknowledgements.values.forEach { it.cancel() }; acknowledgements.clear()
    }
    override fun load(content: WebContent) {
        check(!closed)
        val deployed = (content as? WebContent.Bundle)?.app?.browserUrl
        val deployedOrigin = deployed?.let(::webOrigin)
        if (content is WebContent.Bundle) {
            if (deployed == null) throw WebUnavailable(WebFeature.Bundles, "This WebApp needs a browser deployment URL")
            require(deployedOrigin != global.location.origin) { "Privileged bundle frames need an origin separate from the host application" }
            require(deployed.startsWith("https://") || (context.options.debug && deployedOrigin in context.options.policy.loopbackOrigins)) {
                "Browser bundle deployments require HTTPS; debug loopback origins must be explicitly allowed"
            }
        }
        revoke(); token = null; origin = deployedOrigin; loaded = false
        desiredContent = content
        app = (content as? WebContent.Bundle)?.app
        context.navigationStarted(null)
        when (content) {
            is WebContent.Url -> {
                frame.setAttribute("sandbox", "allow-scripts")
                frame.removeAttribute("srcdoc"); frame.src = content.url
            }
            is WebContent.Html -> {
                frame.setAttribute("sandbox", "allow-scripts")
                frame.removeAttribute("src"); frame.srcdoc = content.html
            }
            is WebContent.Bundle -> {
                frame.setAttribute("sandbox", "allow-scripts allow-same-origin")
                frame.removeAttribute("srcdoc")
                val key = Uuid.random().toString(); token = key
                val parameters: dynamic = js("new URLSearchParams()")
                parameters.set("reaktor_token", key); parameters.set("reaktor_parent", global.location.origin)
                frame.src = deployed + "#" + parameters.toString()
                scope.launch {
                    delay(15_000)
                    if (!closed && token == key && port == null) context.navigationFinished("Bundle bridge handshake unavailable; check deployment assets and framing policy")
                }
            }
        }
    }
    override fun configureBridge(configuration: JsonObject) {
        this.configuration = configuration
        if (port != null) {
            val message: dynamic = js("({kind:'configure'})")
            message.configuration = global.JSON.parse(configuration.toString()); port.postMessage(message)
        }
    }
    override suspend fun postMessage(body: String) {
        check(!closed && port != null)
        val id = Uuid.random().toString(); val acknowledged = CompletableDeferred<Unit>()
        acknowledgements[id] = acknowledged
        val message: dynamic = js("({kind:'deliver'})")
        message.id = id; message.envelope = global.JSON.parse(body); port.postMessage(message)
        try { withTimeout(15_000) { acknowledged.await() } } finally { acknowledgements.remove(id) }
    }
    override fun executeJavaScript(script: String): Unit = throw WebUnavailable(WebFeature.Evaluation, "Cross-origin browser documents do not expose arbitrary host evaluation")
    override fun reload() { desiredContent?.let(::load) }
    override fun focus() { frame.focus() }
    override fun zoom(factor: Double) { frame.style.zoom = factor.toString() }
    override fun close() {
        if (closed) return
        closed = true; scope.cancel(); revoke(); token = null
        global.removeEventListener("message", listener); frame.remove()
    }
}
