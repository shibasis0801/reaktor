@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.shibasis.reaktor.web

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import platform.CoreGraphics.CGRectZero
import platform.Foundation.*
import platform.WebKit.*
import platform.darwin.*

class DarwinWebViewHost : WebViewHost {
    private var controller: DarwinWebController? = null
    val view: WKWebView get() = requireNotNull(controller).view
    override fun create(): WebViewController {
        check(NSThread.isMainThread) { "Create Apple WebViews on the UI thread" }
        check(controller == null) { "An Apple host owns one view" }
        return DarwinWebController().also { controller = it }
    }
}

private class DarwinWebController : WebViewController {
    lateinit var view: WKWebView
        private set
    private lateinit var context: WebHostContext
    private var app: WebApp? = null
    private var closed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val resourceTasks = mutableSetOf<WKURLSchemeTaskProtocol>()
    private val evaluations = mutableSetOf<CompletableDeferred<JsonElement>>()
    override val features = setOf(
        WebFeature.Bundles, WebFeature.Bridge, WebFeature.Evaluation, WebFeature.Navigation,
        WebFeature.History, WebFeature.Zoom, WebFeature.EphemeralProfile, WebFeature.PersistentProfile, WebFeature.Downloads,
    )

    private val navigation = object : NSObject(), WKNavigationDelegateProtocol {
        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, decidePolicyForNavigationAction: WKNavigationAction, decisionHandler: (WKNavigationActionPolicy) -> Unit) {
            val url = decidePolicyForNavigationAction.request.URL?.absoluteString.orEmpty()
            val target = decidePolicyForNavigationAction.targetFrame
            if (target == null || !context.allowsNavigation(canonical(url))) {
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
                return
            }
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
        }
        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?) {
            evaluations.toList().forEach { it.cancel() }
            context.navigationStarted(webView.URL?.absoluteString?.let(::canonical))
        }
        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didCommitNavigation: WKNavigation?) { committed() }
        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) { committed(); context.navigationFinished() }
        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
            context.navigationFinished(withError.localizedDescription)
        }
        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
            context.navigationFinished(withError.localizedDescription)
        }
        override fun webViewWebContentProcessDidTerminate(webView: WKWebView) { context.rendererTerminated() }
        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, decidePolicyForNavigationResponse: WKNavigationResponse, decisionHandler: (WKNavigationResponsePolicy) -> Unit) {
            if (!decidePolicyForNavigationResponse.canShowMIMEType) {
                val response = decidePolicyForNavigationResponse.response
                context.download(WebDownload(response.URL?.absoluteString?.let(::canonical).orEmpty(), response.suggestedFilename.orEmpty(), response.MIMEType.orEmpty()))
                decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyCancel)
            } else decisionHandler(WKNavigationResponsePolicy.WKNavigationResponsePolicyAllow)
        }
    }
    private val messages = object : NSObject(), WKScriptMessageHandlerProtocol {
        override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
            val source = didReceiveScriptMessage.frameInfo.request.URL?.absoluteString ?: return
            val body = didReceiveScriptMessage.body as? String ?: return
            if (!closed) context.message(body, canonical(source), didReceiveScriptMessage.frameInfo.mainFrame)
        }
    }
    private val permissions = object : NSObject(), WKUIDelegateProtocol {
        override fun webView(webView: WKWebView, requestMediaCapturePermissionForOrigin: WKSecurityOrigin,
            initiatedByFrame: WKFrameInfo, type: WKMediaCaptureType, decisionHandler: (WKPermissionDecision) -> Unit) {
            decisionHandler(WKPermissionDecision.WKPermissionDecisionDeny)
        }
    }
    private val scheme = object : NSObject(), WKURLSchemeHandlerProtocol {
        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, startURLSchemeTask: WKURLSchemeTaskProtocol) {
            resourceTasks += startURLSchemeTask
            val url = startURLSchemeTask.request.URL
            val bundle = app
            scope.launch {
                val asset = runCatching {
                    require(url?.scheme == "reaktor-app" && url.host == bundle?.id)
                    val prefix = "/${requireNotNull(bundle).revision}"
                    val path = url?.path.orEmpty()
                    require(path.startsWith("$prefix/"))
                    bundle.assets.read(webAssetPath(path.removePrefix(prefix))) ?: error("Asset not found")
                }
                withContext(Dispatchers.Main) {
                    if (closed || !resourceTasks.remove(startURLSchemeTask)) return@withContext
                    asset.fold(onSuccess = {
                        val headers = mapOf<Any?, String>("Content-Type" to it.mimeType, "Content-Security-Policy" to requireNotNull(bundle).contentSecurityPolicy,
                            "X-Content-Type-Options" to "nosniff", "Referrer-Policy" to "no-referrer")
                        val response = NSHTTPURLResponse(requireNotNull(url), 200, "HTTP/1.1", headers)
                        startURLSchemeTask.didReceiveResponse(response)
                        startURLSchemeTask.didReceiveData(it.bytes.toData())
                        startURLSchemeTask.didFinish()
                    }, onFailure = {
                        startURLSchemeTask.didFailWithError(NSError("reaktor.web.asset", 404, mapOf(NSLocalizedDescriptionKey to "Bundle asset unavailable")))
                    })
                }
            }
        }
        @ObjCSignatureOverride
        override fun webView(webView: WKWebView, stopURLSchemeTask: WKURLSchemeTaskProtocol) { resourceTasks -= stopURLSchemeTask }
    }

    override fun configure(context: WebHostContext) {
        this.context = context
        val configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = when (val profile = context.options.profile) {
            WebProfile.Browser -> throw WebUnavailable(WebFeature.BrowserStorage, "Browser storage is only available in iframe hosts")
            WebProfile.Ephemeral -> WKWebsiteDataStore.nonPersistentDataStore()
            is WebProfile.Persistent -> if (profile.id == "default") WKWebsiteDataStore.defaultDataStore()
            else {
                if (NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion } < 17) throw WebUnavailable(WebFeature.PersistentProfile, "Independent Apple profiles require iOS 17")
                WKWebsiteDataStore.dataStoreForIdentifier(NSUUID(profile.id))
            }
        }
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        configuration.setURLSchemeHandler(scheme, "reaktor-app")
        configuration.userContentController.addScriptMessageHandler(messages, "reaktor")
        view = WKWebView(CGRectZero.readValue(), configuration)
        view.navigationDelegate = navigation
        view.UIDelegate = permissions
        if (NSProcessInfo.processInfo.operatingSystemVersion.useContents { majorVersion >= 17 }) view.inspectable = context.options.debug
        view.allowsBackForwardNavigationGestures = true
    }

    private fun canonical(url: String): String {
        val bundle = app ?: return url
        val prefix = "reaktor-app://${bundle.id}/${bundle.revision}/"
        return if (url.startsWith(prefix)) bundle.origin + "/" + bundle.revision + "/" + url.removePrefix(prefix) else url
    }
    private fun committed() {
        val url = view.URL?.absoluteString ?: return
        context.navigationCommitted(canonical(url), view.title.orEmpty(), view.canGoBack, view.canGoForward)
    }
    override fun load(content: WebContent) = onMain {
        app = (content as? WebContent.Bundle)?.app
        when (content) {
            is WebContent.Url -> view.loadRequest(NSURLRequest(NSURL(string = content.url)))
            is WebContent.Html -> view.loadHTMLString(content.html, null)
            is WebContent.Bundle -> view.loadRequest(NSURLRequest(NSURL(string = "reaktor-app://${content.app.id}/${content.app.revision}${content.app.entrypoint}")))
        }
    }
    override fun executeJavaScript(script: String) = onMain { view.evaluateJavaScript(script, null) }
    override suspend fun evaluate(script: String): JsonElement {
        val result = CompletableDeferred<JsonElement>()
        onMain {
            evaluations += result
            view.evaluateJavaScript(script) { value, error ->
                evaluations -= result
                if (closed) result.cancel()
                else if (error != null) result.completeExceptionally(IllegalStateException(error.localizedDescription))
                else runCatching {
                    if (value == null || value is NSNull) JsonNull
                    else {
                        val data = requireNotNull(NSJSONSerialization.dataWithJSONObject(value, NSJSONWritingFragmentsAllowed, null))
                        Json.parseToJsonElement(data.bytes?.readBytes(data.length.toInt())?.decodeToString() ?: "null")
                    }
                }.onSuccess { result.complete(it) }.onFailure { result.completeExceptionally(it) }
            }
        }
        try { return result.await() } finally { if (!closed) onMain { evaluations -= result } }
    }
    override fun reload() = onMain { view.reload(); Unit }
    override fun back() = onMain { view.goBack(); Unit }
    override fun forward() = onMain { view.goForward(); Unit }
    override fun focus() = onMain { view.becomeFirstResponder(); Unit }
    override fun zoom(factor: Double) = onMain { view.pageZoom = factor }
    override fun openDevTools(): Unit = throw WebUnavailable(WebFeature.DevTools, "Use Safari's Develop menu to inspect Apple WebViews")
    private fun onMain(action: () -> Unit) {
        check(!closed) { "Apple WebView is closed" }
        if (NSThread.isMainThread) action() else dispatch_async(dispatch_get_main_queue()) { if (!closed) action() }
    }
    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        val release = {
            evaluations.toList().forEach { it.cancel() }
            evaluations.clear()
            resourceTasks.clear()
            if (this::view.isInitialized) {
                view.stopLoading()
                view.navigationDelegate = null
                view.UIDelegate = null
                view.configuration.userContentController.removeScriptMessageHandlerForName("reaktor")
                view.removeFromSuperview()
            }
        }
        if (NSThread.isMainThread) release() else dispatch_async(dispatch_get_main_queue(), release)
    }
}

private fun ByteArray.toData(): NSData = if (isEmpty()) NSData() else usePinned {
    NSData.create(bytes = it.addressOf(0), length = size.toULong())
}
