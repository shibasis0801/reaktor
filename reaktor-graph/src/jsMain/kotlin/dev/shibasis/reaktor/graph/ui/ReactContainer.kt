package dev.shibasis.reaktor.graph.ui

import dev.shibasis.reaktor.graph.core.Graph
import react.ReactNode as Component
import kotlin.js.JsExport

@JsExport
interface ReactContainer : View {
    fun Content(renderer: (graph: Graph, isFocused: Boolean) -> Component?): Component?
}
