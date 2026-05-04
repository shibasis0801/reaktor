package dev.shibasis.reaktor.web

import dev.shibasis.reaktor.graph.Reaktor
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.navigation.WebNavigationBridge
import dev.shibasis.reaktor.graph.ui.ReactGraphContent
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
