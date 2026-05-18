package dev.shibasis.reaktor.graph.navigation

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.edge.NavigationEdge
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.io.network.RoutePattern
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.w3c.dom.events.Event
import kotlin.js.JsExport

@JsExport
class WebNavigationBridge(private val graph: Graph) {
    private val routeIndex = mutableMapOf<RoutePattern, RouteNode<*, *>>()
    private var handlingPopState = false
    private var programmaticBack = false
    private var lastStackSize = graph.backStack.entries.value.size
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        buildRouteIndex(graph)
        observeBackStack()
        listenToPopState()
    }

    private fun buildRouteIndex(graph: Graph) {
        for (node in graph.nodes) {
            if (node is RouteNode<*, *> && node.pattern.original.isNotEmpty()) {
                routeIndex[node.pattern] = node
            }
            if (node is ContainerNode) {
                for (child in node.graphs) {
                    buildRouteIndex(child)
                }
            }
        }
    }

    private fun observeBackStack() {
        var initialized = false
        scope.launch {
            graph.backStack.entries.collectLatest { entries ->
                if (handlingPopState) return@collectLatest
                if (!initialized) {
                    initialized = true
                    return@collectLatest
                }

                val size = entries.size
                val topEntry = entries.lastOrNull() ?: return@collectLatest
                val url = entryToUrl(topEntry)

                when {
                    size > lastStackSize -> window.history.pushState(null, "", url)
                    size < lastStackSize -> {
                        programmaticBack = true
                        window.history.back()
                    }
                    size == lastStackSize && size > 0 -> window.history.replaceState(null, "", url)
                }
                lastStackSize = size
            }
        }
    }

    private val popStateHandler: (Event) -> Unit = {
        if (programmaticBack) {
            programmaticBack = false
            lastStackSize = graph.backStack.entries.value.size
        } else {
            handlingPopState = true
            graph.dispatch(Pop)
            lastStackSize = graph.backStack.entries.value.size
            handlingPopState = false
        }
    }

    private fun listenToPopState() {
        window.addEventListener("popstate", popStateHandler)
    }

    fun resolveCurrentUrl(): Boolean {
        val path = window.location.pathname
        if (path == "/" || path.isEmpty()) return false

        val (routeNode, params) = matchRoute(path) ?: return false
        val payload = Payload(HashMap(params))
        @Suppress("UNCHECKED_CAST")
        val edge = graph.sentinel.edge(routeNode) as NavigationEdge<Payload>
        graph.dispatch(Push(edge, payload))
        return true
    }

    private fun entryToUrl(entry: BackStackEntry<*, *>): String {
        val pattern = entry.edge.end.pattern
        val params = entry.payload.routeParams
        return pattern.fill(params.toMap())
    }

    private fun matchRoute(path: String): Pair<RouteNode<*, *>, HashMap<String, String>>? {
        for ((pattern, node) in routeIndex) {
            val match = pattern.regex.matchEntire(path) ?: continue
            val params = HashMap(pattern.getParams(match))
            return node to params
        }
        return null
    }

    fun destroy() {
        scope.cancel()
        window.removeEventListener("popstate", popStateHandler)
    }
}
