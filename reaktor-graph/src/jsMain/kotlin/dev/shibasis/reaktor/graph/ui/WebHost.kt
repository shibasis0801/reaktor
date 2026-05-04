package dev.shibasis.reaktor.graph.ui

import dev.shibasis.reaktor.graph.Reaktor
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.navigation.WebNavigationBridge
import react.ReactNode as Component
import kotlin.js.JsExport

@JsExport
class WebHost(val graph: Graph) {
    val bridge = WebNavigationBridge(graph)

    fun start() {
        Reaktor.web()
        bridge.resolveCurrentUrl()
    }

    fun Content(): Component? = ReactGraphContent(graph)
}
