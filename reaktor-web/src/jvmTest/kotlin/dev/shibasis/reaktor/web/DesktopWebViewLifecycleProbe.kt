package dev.shibasis.reaktor.web

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import org.koin.dsl.koinApplication
import java.awt.EventQueue
import javax.swing.JFrame

fun main() {
    val app = koinApplication {}
    val graph = Graph(dependencyAdapter = KoinDependencyAdapter(app))
    val runtime = graph.Node { WebRuntime(it) }
    try {
        for (attempt in 0 until 20) {
            lateinit var frame: JFrame
            lateinit var view: DesktopWebView
            EventQueue.invokeAndWait {
                view = DesktopWebView(runtime, WebContent.Html("<p>Lifecycle probe</p>"), WebViewOptions(profile = WebProfile.Ephemeral))
                frame = JFrame("WebView lifecycle $attempt").apply {
                    add(view)
                    setSize(500, 300)
                    defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                    isVisible = true
                }
            }
            try {
                val until = System.nanoTime() + 10_000_000_000L
                while (!view.nativeAttached && view.failure == null && System.nanoTime() < until) Thread.sleep(25)
                check(view.nativeAttached && view.failure == null) { "View $attempt failed to attach" }
            } finally {
                EventQueue.invokeAndWait { view.close() }
                NativeWebView.commands.submit {}.get()
                EventQueue.invokeAndWait { frame.dispose() }
            }
            check(NativeWebView.handles.isEmpty()) { "Native handle leaked after close" }
        }
        repeat(4) {
            val canvas = java.awt.Canvas()
            lateinit var frame: JFrame
            EventQueue.invokeAndWait {
                frame = JFrame("Failed native construction").apply { add(canvas); setSize(500, 300); isVisible = true }
            }
            try {
                val failure = runCatching {
                    NativeWebView.commands.submit<Long> {
                        NativeWebView.create(canvas, false, "${System.getProperty("java.home")}/lib/libjawt.dylib", "invalid-profile", NativeWebView.directory, object : NativeWebCallbacks {
                            override fun allowsNavigation(url: String) = true
                            override fun event(body: String) = Unit
                        })
                    }.get()
                }.exceptionOrNull()
                check(failure != null) { "Failed native creation must be reported" }
            } finally { EventQueue.invokeAndWait { frame.dispose() } }
            check(NativeWebView.handles.isEmpty())
        }
        println("Native WebView lifecycle PASS: 20 attachments and 4 failed constructions")
    } finally {
        graph.close()
        app.close()
        NativeWebView.commands.submit {}.get()
    }
}
