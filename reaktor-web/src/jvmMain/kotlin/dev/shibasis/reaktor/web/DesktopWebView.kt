package dev.shibasis.reaktor.web

import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.EventQueue
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.nio.file.Files
import java.util.concurrent.Executors
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlinx.coroutines.flow.asStateFlow

class DesktopWebView(
    private val runtime: WebRuntime,
    content: WebContent,
    private val options: WebViewOptions = WebViewOptions(),
    private val bridge: WebBridge? = null,
) : JPanel(BorderLayout()), AutoCloseable {
    constructor(runtime: WebRuntime, content: WebContent, debug: Boolean) : this(runtime, content, WebViewOptions(debug = debug))

    private val mutableSession = kotlinx.coroutines.flow.MutableStateFlow<WebSession?>(null)
    val sessionState: kotlinx.coroutines.flow.StateFlow<WebSession?> = mutableSession.asStateFlow()
    private val mutableFailure = kotlinx.coroutines.flow.MutableStateFlow<Throwable?>(null)
    val failures: kotlinx.coroutines.flow.StateFlow<Throwable?> = mutableFailure.asStateFlow()
    private val canvas = Canvas()
    private var desiredContent = content
    private var controller: DesktopController? = null
    var session: WebSession?
        get() = mutableSession.value
        private set(value) { mutableSession.value = value }
    var failure: Throwable?
        get() = mutableFailure.value
        private set(value) { mutableFailure.value = value }
    var nativeAttached: Boolean = false
        private set
    private var disposed = false

    init {
        add(canvas, BorderLayout.CENTER)
        canvas.addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) { controller?.focus() }
        })
        canvas.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) = updateBounds()
            override fun componentMoved(event: ComponentEvent) = updateBounds()
        })
        canvas.addHierarchyBoundsListener(object : HierarchyBoundsAdapter() {
            override fun ancestorMoved(event: HierarchyEvent) = updateBounds()
            override fun ancestorResized(event: HierarchyEvent) = updateBounds()
        })
        canvas.addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) updateBounds()
        }
    }

    override fun addNotify() {
        super.addNotify()
        EventQueue.invokeLater {
            if (disposed || !isDisplayable || session != null) return@invokeLater
            try {
                val opened = runtime.open(object : WebViewHost {
                    override fun create(): WebViewController = DesktopController(canvas, options,
                        onAttached = {
                            if (!disposed && session?.state?.value?.closed == false) {
                                nativeAttached = true
                                updateBounds()
                            }
                        },
                        onFailure = { error ->
                            if (!disposed) {
                                failure = error
                                session?.close()
                                session = null
                                controller = null
                                nativeAttached = false
                            }
                        },
                    ).also { controller = it }
                }, options, bridge)
                session = opened
                opened.load(desiredContent)
                updateBounds()
            } catch (error: Throwable) {
                session?.close()
                session = null
                controller = null
                failure = error
            }
        }
    }

    fun load(content: WebContent) = onEdt {
        check(!disposed) { "Desktop WebView is closed" }
        desiredContent = content
        session?.load(content)
    }

    fun focusWebView() = onEdt { controller?.focus() }

    private fun updateBounds() {
        val window = SwingUtilities.getWindowAncestor(canvas) ?: return
        val origin = SwingUtilities.convertPoint(canvas, Point(), window)
        controller?.bounds(
            (origin.x - window.insets.left).toDouble(),
            (origin.y - window.insets.top).toDouble(),
            canvas.width.toDouble(), canvas.height.toDouble(), canvas.isShowing,
        )
    }

    override fun removeNotify() {
        session?.close()
        session = null
        controller = null
        nativeAttached = false
        super.removeNotify()
    }

    override fun close() = onEdt {
        if (!disposed) {
            disposed = true
            session?.close()
            session = null
            controller = null
            nativeAttached = false
        }
    }
}

internal interface NativeWebCallbacks {
    fun event(body: String)
    fun allowsNavigation(url: String): Boolean
}

private class DesktopController(
    canvas: Canvas,
    options: WebViewOptions,
    private val onAttached: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : WebViewController {
    private var handle = 0L
    @Volatile private var closed = false
    @Volatile private var app: WebApp? = null
    private var failed = false
    private lateinit var context: WebHostContext
    private val evaluations = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<kotlinx.serialization.json.JsonElement>>()
    override val features: Set<WebFeature> get() = WebFeature.entries.filterIndexed { index, _ ->
        NativeWebView.features() and (1 shl index) != 0
    }.toSet()
    private val callbacks = object : NativeWebCallbacks {
        override fun allowsNavigation(url: String): Boolean =
            !closed && this@DesktopController::context.isInitialized && context.allowsNavigation(canonical(url))
        override fun event(body: String) {
            if (closed) return
            try {
                val event = kotlinx.serialization.json.Json.parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonObject }
                fun text(key: String): String = (event[key] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                fun flag(key: String): Boolean = text(key) == "true"
                when (text("kind")) {
                    "start" -> {
                        evaluations.values.forEach { it.cancel() }
                        evaluations.clear()
                        context.navigationStarted(canonical(text("url")))
                    }
                    "commit" -> context.navigationCommitted(canonical(text("url")), text("title"), flag("back"), flag("forward"))
                    "finish" -> context.navigationFinished(text("error").takeIf { it.isNotEmpty() })
                    "terminated" -> context.rendererTerminated()
                    "message" -> context.message(text("body"), canonical(text("url")), flag("main"))
                    "download" -> context.download(WebDownload(canonical(text("url")), text("name"), text("mime")))
                    "evaluation" -> {
                        val result = evaluations.remove(text("id")) ?: return
                        if (text("error").isNotEmpty()) result.completeExceptionally(IllegalStateException(text("error")))
                        else result.complete(event["value"] ?: kotlinx.serialization.json.JsonNull)
                    }
                }
            } catch (error: Throwable) { fail(error) }
        }
    }

    init {
        check(EventQueue.isDispatchThread()) { "Create the desktop host on the AWT event thread" }
        check(canvas.isDisplayable) { "The AWT host must be displayable" }
        NativeWebView.ensureLoaded()
        NativeWebView.commands.execute {
            if (!closed) try {
                val jawt = when (NativeWebView.platform) {
                    "macos" -> "${System.getProperty("java.home")}/lib/libjawt.dylib"
                    "windows" -> "${System.getProperty("java.home")}/bin/jawt.dll"
                    else -> "${System.getProperty("java.home")}/lib/libjawt.so"
                }
                val profile = when (val requested = options.profile) {
                    WebProfile.Browser -> throw WebUnavailable(WebFeature.BrowserStorage, "Browser storage is only available in iframe hosts")
                    WebProfile.Ephemeral -> "ephemeral"
                    is WebProfile.Persistent -> requested.id
                }
                handle = NativeWebView.create(canvas, options.debug, jawt, profile, NativeWebView.directory, callbacks)
                check(handle != 0L) { "Native WebView creation failed" }
                NativeWebView.handles += handle
                if (closed) release() else EventQueue.invokeLater(onAttached)
            } catch (error: Throwable) { fail(error) }
        }
    }
    override fun configure(context: WebHostContext) { this.context = context }
    private fun canonical(url: String): String {
        val bundle = app ?: return url
        val prefix = "reaktor-app://${bundle.id}/${bundle.revision}/"
        return if (url.startsWith(prefix)) bundle.origin + "/" + bundle.revision + "/" + url.removePrefix(prefix) else url
    }
    override fun load(content: WebContent) {
        app = (content as? WebContent.Bundle)?.app
        submit {
            NativeWebView.clearAssets(handle)
            when (content) {
                is WebContent.Url -> NativeWebView.load(handle, false, content.url)
                is WebContent.Html -> NativeWebView.load(handle, true, content.html)
                is WebContent.Bundle -> {
                    var size = 0L
                    content.app.assets.paths.forEach { path ->
                        val asset = requireNotNull(content.app.assets.read(path)) { "Missing bundle asset $path" }
                        size += asset.bytes.size
                        require(size <= 64 * 1024 * 1024) { "Bundle exceeds 64 MiB" }
                        NativeWebView.asset(handle, path, asset.mimeType, asset.bytes, content.app.contentSecurityPolicy)
                    }
                    NativeWebView.load(handle, false, "reaktor-app://${content.app.id}/${content.app.revision}${content.app.entrypoint}")
                }
            }
        }
    }
    override fun executeJavaScript(script: String) = submit { NativeWebView.execute(handle, script) }
    override suspend fun evaluate(script: String): kotlinx.serialization.json.JsonElement {
        val id = java.util.UUID.randomUUID().toString()
        val result = kotlinx.coroutines.CompletableDeferred<kotlinx.serialization.json.JsonElement>()
        evaluations[id] = result
        try {
            submit { NativeWebView.evaluate(handle, id, script) }
            return result.await()
        } finally { evaluations.remove(id) }
    }
    override fun reload() = submit { NativeWebView.control(handle, 0, 0.0) }
    override fun back() = submit { NativeWebView.control(handle, 1, 0.0) }
    override fun forward() = submit { NativeWebView.control(handle, 2, 0.0) }
    override fun zoom(factor: Double) = submit { NativeWebView.control(handle, 3, factor) }
    override fun openDevTools() = submit { NativeWebView.control(handle, 4, 0.0) }
    fun bounds(x: Double, y: Double, width: Double, height: Double, visible: Boolean) {
        if (!closed) submit { NativeWebView.bounds(handle, x, y, width, height, visible) }
    }
    override fun focus() { if (!closed) submit { NativeWebView.focus(handle) } }
    private fun submit(action: () -> Unit) {
        check(!closed) { "WebView is closed" }
        NativeWebView.commands.execute {
            if (!closed && !failed) try { action() } catch (error: Throwable) { fail(error) }
        }
    }
    private fun fail(error: Throwable) {
        failed = true
        evaluations.values.forEach { it.completeExceptionally(error) }
        evaluations.clear()
        release()
        EventQueue.invokeLater { onFailure(error) }
    }
    private fun release() {
        if (handle != 0L) { NativeWebView.destroy(handle); NativeWebView.handles -= handle; handle = 0L }
    }
    override fun close() {
        closed = true
        evaluations.values.forEach { it.cancel() }
        evaluations.clear()
        NativeWebView.commands.execute { release() }
    }
}

private fun onEdt(action: () -> Unit) {
    if (EventQueue.isDispatchThread()) action() else {
        var failure: Throwable? = null
        EventQueue.invokeAndWait { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
}

internal object NativeWebView {
    private var loaded = false
    val platform: String = when {
        System.getProperty("os.name").startsWith("Mac") -> "macos"
        System.getProperty("os.name").startsWith("Windows") -> "windows"
        else -> "linux"
    }
    lateinit var directory: String
        private set
    // AppKit accessibility can wait on the EDT; the EDT must never wait on AppKit.
    val commands = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "reaktor-webview-commands").apply { isDaemon = true }
    }

    @Synchronized fun ensureLoaded() {
        if (loaded) return
        val architecture = when (System.getProperty("os.arch")) {
            "aarch64", "arm64" -> "arm64"
            "x86_64", "amd64" -> "x64"
            else -> error("Unsupported desktop architecture")
        }
        val filename = when (platform) {
            "macos" -> "libReaktorWebView.dylib"
            "windows" -> "ReaktorWebView.dll"
            else -> "libReaktorWebView.so"
        }
        val root = "/native/$platform-$architecture/"
        val folder = Files.createTempDirectory("reaktor-webview-").toFile().apply { deleteOnExit() }
        directory = folder.absolutePath
        fun extract(name: String, required: Boolean): java.io.File? {
            val stream = NativeWebView::class.java.getResourceAsStream(root + name)
            if (stream == null) {
                check(!required) { "Missing $root$name; build :reaktor-web:desktopWebViewNative on the matching host" }
                return null
            }
            return folder.resolve(name).apply {
                deleteOnExit()
                stream.use { input -> outputStream().use { output -> input.copyTo(output) } }
            }
        }
        val library = requireNotNull(extract(filename, true))
        extract("libReaktorWebExtension.so", false)
        extract("WebView2Loader.dll", false)?.let { System.load(it.absolutePath) }
        System.load(library.absolutePath)
        check(abiVersion() == 2) { "Unsupported Reaktor WebView native ABI" }
        loaded = true
        Runtime.getRuntime().addShutdownHook(Thread({
            runCatching {
                commands.submit { handles.toList().forEach { destroy(it) } }.get(10, java.util.concurrent.TimeUnit.SECONDS)
            }
        }, "reaktor-webview-shutdown"))
    }
    val handles = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    external fun abiVersion(): Int
    external fun features(): Int
    external fun create(canvas: Canvas, debug: Boolean, jawtPath: String, profile: String, nativeDirectory: String, callbacks: NativeWebCallbacks): Long
    external fun bounds(handle: Long, x: Double, y: Double, width: Double, height: Double, visible: Boolean)
    external fun load(handle: Long, html: Boolean, value: String)
    external fun execute(handle: Long, script: String)
    external fun evaluate(handle: Long, id: String, script: String)
    external fun control(handle: Long, action: Int, value: Double)
    external fun clearAssets(handle: Long)
    external fun asset(handle: Long, path: String, mime: String, bytes: ByteArray, csp: String)
    external fun focus(handle: Long)
    external fun destroy(handle: Long)
}
