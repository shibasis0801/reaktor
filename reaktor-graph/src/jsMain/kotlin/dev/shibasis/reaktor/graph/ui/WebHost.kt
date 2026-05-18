package dev.shibasis.reaktor.graph.ui

import dev.shibasis.reaktor.graph.Reaktor
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.edge.NavigationEdge
import dev.shibasis.reaktor.graph.navigation.*
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

    fun dispatch(command: NavCommand) = graph.dispatch(command)

    fun navigate(edge: NavigationEdge<Payload>, payload: Payload = Payload()) {
        graph.dispatch(Push(edge, payload))
    }

    fun goBack() = graph.dispatch(Pop)

    fun topEntry(): BackStackEntry<*, *>? {
        val entries = graph.backStack.entries.value
        return entries.lastOrNull()
    }

    fun topPattern(): String {
        val entry = topEntry() ?: return "/"
        return entry.edge.end.pattern.original
    }

    fun topParams(): HashMap<String, String> {
        val entry = topEntry() ?: return HashMap()
        return entry.payload.routeParams
    }

    fun stackSize(): Int = graph.backStack.entries.value.size

    fun destroy() {
        bridge.destroy()
        graph.close()
    }
}
