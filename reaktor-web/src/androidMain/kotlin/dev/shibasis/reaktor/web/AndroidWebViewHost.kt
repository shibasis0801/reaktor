package dev.shibasis.reaktor.web

import android.content.Context
import android.widget.FrameLayout
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.*
import androidx.webkit.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidWebViewHost(private val context: Context) : WebViewHost {
    private var controller: AndroidWebController? = null
    val view: FrameLayout get() = requireNotNull(controller).container
    override fun create(): WebViewController {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Create Android WebViews on the UI thread" }
        check(controller == null) { "An Android host owns one view" }
        return AndroidWebController(context).also { controller = it }
    }
}

private class AndroidWebController(private val androidContext: Context) : WebViewController {
    val container = FrameLayout(androidContext)
    private var view = WebView(androidContext)
    private var terminated = false
    private var desiredContent: WebContent? = null
    private var zoom = 1.0
    init { container.addView(view, FrameLayout.LayoutParams(-1, -1)) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var context: WebHostContext
    private var app: WebApp? = null
    private var profile: String? = null
    private var ephemeral = false
    @Volatile private var closed = false
    private var messageListener = false
    private val evaluations = mutableSetOf<CompletableDeferred<JsonElement>>()
    override val features: Set<WebFeature> = buildSet {
        addAll(setOf(WebFeature.Bundles, WebFeature.Evaluation, WebFeature.Navigation, WebFeature.History, WebFeature.Zoom, WebFeature.PersistentProfile, WebFeature.Downloads))
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) add(WebFeature.Bridge)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) add(WebFeature.EphemeralProfile)
    }

    override fun configure(context: WebHostContext) {
        this.context = context
        when (val requested = context.options.profile) {
            WebProfile.Browser -> throw WebUnavailable(WebFeature.BrowserStorage, "Browser storage is only available in iframe hosts")
            WebProfile.Ephemeral -> {
                if (WebFeature.EphemeralProfile !in features) throw WebUnavailable(WebFeature.EphemeralProfile, "This Android WebView needs multi-profile support for ephemeral storage")
                ephemeral = true
                profile = "reaktor-ephemeral-" + UUID.randomUUID()
                WebViewCompat.setProfile(view, requireNotNull(profile))
            }
            is WebProfile.Persistent -> {
                if (requested.id != "default") {
                    if (WebFeature.EphemeralProfile !in features) throw WebUnavailable(WebFeature.PersistentProfile, "Independent Android profiles need multi-profile support")
                    profile = "reaktor-" + requested.id
                    WebViewCompat.setProfile(view, requireNotNull(profile))
                }
            }
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && !context.allowsNavigation(request.url.toString())
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val bundle = app ?: return null
                val address = request.url.toString()
                if (webOrigin(address) != bundle.origin) {
                    if (webOrigin(address) !in bundle.networkOrigins) return denied()
                    return null
                }
                if (request.method != "GET") return denied()
                return try {
                    val prefix = "/${bundle.revision}"
                    val path = request.url.encodedPath.orEmpty()
                    if (!path.startsWith("$prefix/")) return denied()
                    val asset = bundle.assets.read(webAssetPath(path.removePrefix(prefix)))
                    if (asset == null) WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream("Asset not found".toByteArray()))
                    else WebResourceResponse(asset.mimeType, "UTF-8", 200, "OK", mapOf(
                        "Content-Security-Policy" to bundle.contentSecurityPolicy,
                        "X-Content-Type-Options" to "nosniff",
                        "Referrer-Policy" to "no-referrer",
                        "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), clipboard-read=(), clipboard-write=()",
                    ), ByteArrayInputStream(asset.bytes))
                } catch (_: Exception) { denied() }
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                evaluations.toList().forEach { it.cancel() }
                context.navigationStarted(url)
            }
            override fun onPageCommitVisible(view: WebView, url: String) {
                context.navigationCommitted(url, view.title.orEmpty(), view.canGoBack(), view.canGoForward())
            }
            override fun onPageFinished(view: WebView, url: String) {
                context.navigationCommitted(url, view.title.orEmpty(), view.canGoBack(), view.canGoForward())
                context.navigationFinished()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) context.navigationFinished(error.description.toString())
            }
            override fun onReceivedSslError(view: WebView, callback: SslErrorHandler, error: android.net.http.SslError) {
                callback.cancel()
                context.navigationFinished("TLS validation failed")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                evaluations.toList().forEach { it.cancel() }
                terminated = true
                container.removeView(view)
                view.destroy()
                context.rendererTerminated()
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
            override fun onShowFileChooser(view: WebView, callback: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                callback.onReceiveValue(null)
                return true
            }
            override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, result: android.os.Message): Boolean = false
        }
        view.setDownloadListener { url, _, disposition, mime, _ ->
            context.download(WebDownload(url, URLUtil.guessFileName(url, disposition, mime), mime.orEmpty()))
        }
    }

    override fun load(content: WebContent) = onMain {
        desiredContent = content
        if (messageListener) {
            WebViewCompat.removeWebMessageListener(view, "ReaktorNative")
            messageListener = false
        }
        app = (content as? WebContent.Bundle)?.app
        val bundle = app
        if (bundle != null && WebFeature.Bridge in features) {
            WebViewCompat.addWebMessageListener(view, "ReaktorNative", setOf(bundle.origin)) { nativeView, message, source, mainFrame, _ ->
                val url = nativeView.url ?: return@addWebMessageListener
                if (mainFrame && webOrigin(source.toString()) == bundle.origin && webOrigin(url) == bundle.origin)
                    message.data?.let { context.message(it, url, true) }
            }
            messageListener = true
        }
        when (content) {
            is WebContent.Url -> view.loadUrl(content.url)
            is WebContent.Html -> view.loadDataWithBaseURL(null, content.html, "text/html", "UTF-8", null)
            is WebContent.Bundle -> view.loadUrl(content.app.origin + "/" + content.app.revision + content.app.entrypoint)
        }
    }
    override fun executeJavaScript(script: String) = onMain { view.evaluateJavascript(script, null) }
    override suspend fun evaluate(script: String): JsonElement {
        val result = CompletableDeferred<JsonElement>()
        onMain {
            evaluations += result
            view.evaluateJavascript(script) { value ->
                evaluations -= result
                if (!closed) runCatching { Json.parseToJsonElement(value ?: "null") }
                    .onSuccess { result.complete(it) }.onFailure { result.completeExceptionally(it) }
            }
        }
        try { return result.await() } finally { onMainIfOpen { evaluations -= result } }
    }
    override fun reload() = onMain {
        if (terminated) {
            terminated = false
            messageListener = false
            if (ephemeral) profile?.let { ProfileStore.getInstance().deleteProfile(it) }
            view = WebView(androidContext)
            container.addView(view, FrameLayout.LayoutParams(-1, -1))
            configure(context)
            desiredContent?.let(::load)
            view.zoomBy(zoom.toFloat())
        } else view.reload()
    }
    override fun back() = onMain { view.goBack() }
    override fun forward() = onMain { view.goForward() }
    override fun focus() = onMain { view.requestFocus(); Unit }
    override fun zoom(factor: Double) = onMain { view.zoomBy((factor / zoom).toFloat()); zoom = factor }
    override fun openDevTools(): Unit = throw WebUnavailable(WebFeature.DevTools, "Inspect Android WebViews through the developer device tools")
    private fun onMain(action: () -> Unit) {
        check(!closed) { "Android WebView is closed" }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { if (!closed) action() }
    }
    private fun onMainIfOpen(action: () -> Unit) { if (!closed) onMain(action) }
    override fun close() {
        if (closed) return
        closed = true
        val release = {
            evaluations.toList().forEach { it.cancel() }
            evaluations.clear()
            try {
                if (!terminated) {
                    if (messageListener) WebViewCompat.removeWebMessageListener(view, "ReaktorNative")
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    view.removeAllViews()
                    view.destroy()
                }
            } finally {
                container.removeAllViews()
                if (ephemeral) profile?.let { ProfileStore.getInstance().deleteProfile(it) }
            }
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) release() else handler.post { release() }
    }
    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream("Denied".toByteArray()))
}
