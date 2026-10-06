package dev.shibasis.reaktor.web

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import org.koin.dsl.koinApplication
import java.awt.BorderLayout
import java.awt.EventQueue
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.Timer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

fun main() = EventQueue.invokeLater {
    val app = koinApplication {}
    val graph = Graph(dependencyAdapter = KoinDependencyAdapter(app))
    val runtime = graph.Node { WebRuntime(it) }
    val bridge = graph.Node { probeBridge(it) }
    val first = DesktopWebView(runtime, probeContent(), WebViewOptions(debug = true), bridge)
    val frame = JFrame("Reaktor WebView embedding probe")
    val tools = JPanel()
    tools.add(JButton("Focus").apply { addActionListener { first.focusWebView() } })
    tools.add(JButton("Execute JS").apply {
        addActionListener { first.session?.executeJavaScript("document.getElementById('status').textContent='Host JavaScript executed ✓'") }
    })
    tools.add(JButton("Hide / show").apply { addActionListener { first.isVisible = !first.isVisible } })
    frame.add(tools, BorderLayout.NORTH)
    frame.add(first, BorderLayout.CENTER)
    frame.setSize(900, 580)
    frame.setLocationByPlatform(true)
    frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
    frame.addWindowListener(object : WindowAdapter() {
        override fun windowClosed(event: WindowEvent) {
            try { first.close(); graph.close() } finally { app.close() }
        }
    })
    frame.isVisible = true
    runtime.coroutineScope.launch {
        try {
            withTimeout(15_000) { while (first.session?.state?.value?.loading != false || !first.nativeAttached) delay(50) }
            val session = requireNotNull(first.session)
            val result = withTimeout(10_000) {
                var text = ""
                while (!text.startsWith("Typed bridge received:")) {
                    text = session.evaluate("document.getElementById('bridge').textContent").jsonPrimitive.content
                    delay(50)
                }
                text
            }
            check(result == "Typed bridge received: नमस्ते 🌍")
            println("WebView bundle/typed bridge/evaluation PASS: $result")
        } catch (error: Throwable) {
            error.printStackTrace()
            runCatching { first.session?.evaluate("JSON.stringify({html:document.body.innerHTML,config:window.__reaktorConfig,client:typeof window.reaktor,errors:window.__reaktorHostErrors})") }
                .onSuccess { println("Probe diagnostic: $it; state=${first.session?.state?.value}") }
        }
    }
    var attempts = 0
    Timer(250) { event ->
        attempts++
        if (!first.nativeAttached && first.failure == null && attempts < 40) return@Timer
        (event.source as Timer).stop()
        first.failure?.let { it.printStackTrace(); frame.dispose() }
        println("WebView probe: session=${first.session != null}, nativeAttached=${first.nativeAttached}, failure=${first.failure}")
    }.start()
}
