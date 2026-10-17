@file:OptIn(kotlin.js.ExperimentalJsExport::class)
package dev.shibasis.reaktor.web

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import org.koin.dsl.koinApplication
import kotlinx.coroutines.*

@JsExport
fun startIframeProbe() {
    val global: dynamic = js("globalThis")
    val koin = koinApplication {}
    val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koin))
    val runtime = graph.Node { WebRuntime(it) }
    val bridge = graph.Node { probeBridge(it) }
    val host = BrowserWebViewHost(global.document.getElementById("webview"))
    val session = runtime.open(host, WebViewOptions(profile = WebProfile.Browser, debug = true,
        policy = WebNavigationPolicy(loopbackOrigins = setOf("http://localhost:47189"))), bridge = bridge)
    session.load(WebContent.Bundle(probeContent().app.copy(browserUrl = "http://localhost:47189/index.html")))
    runtime.coroutineScope.launch {
        withTimeout(20_000) {
            while (session.state.value.committedUrl == null || session.state.value.loading) delay(50)
        }
        global.document.getElementById("host-result").textContent = "Iframe bundle and private bridge connected"
    }
    global.addEventListener("pagehide", {
        graph.close()
        koin.close()
    }, js("({once:true})"))
}
