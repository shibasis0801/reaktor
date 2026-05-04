package dev.shibasis.reaktor.graph.ui

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.portgraph.port.provides
import kotlinx.coroutines.flow.MutableStateFlow
import react.FC
import react.Props
import react.ReactNode as Component
import react.create
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.nav
import react.dom.html.ReactHTML.button
import js.objects.jso
import web.cssom.*
import kotlin.js.JsExport

@JsExport
open class WebTabbedContainer(
    graph: Graph,
    pattern: String,
    val children: Map<String, ChildGraph>,
    initialSelection: String
) : ContainerNode(
    graph, pattern,
    ArrayList(children.values.map { it.graph })
), ReactContainer {
    val selected = MutableStateFlow(initialSelection)

    val controller by provides<Controller>(object : Controller {
        override val selected = this@WebTabbedContainer.selected
    })

    override fun activateGraphForRoute(route: RouteNode<*, *>): Boolean {
        val activated = super.activateGraphForRoute(route)
        if (activated) {
            val index = activeGraphIndex.value
            val key = children.keys.elementAtOrNull(index)
            if (key != null) {
                selected.value = key
            }
        }
        return activated
    }

    override fun Content(renderer: (Graph, Boolean) -> Component?): Component? {
        return WebTabbedLayout.create {
            this.container = this@WebTabbedContainer
            this.renderer = renderer
        }
    }
}

external interface WebTabbedProps : Props {
    var container: WebTabbedContainer
    var renderer: (Graph, Boolean) -> Component?
}

val WebTabbedLayout = FC<WebTabbedProps> { props ->
    val container = props.container
    val renderFn = props.renderer

    val (selectedKey, _) = container.selected.toReactState()
    val activeChild = container.children[selectedKey] ?: return@FC

    div {
        style = jso {
            display = Display.flex
            flexDirection = FlexDirection.column
            height = 100.pct
        }

        nav {
            style = jso {
                display = Display.flex
                borderBottom = Border(2.px, LineStyle.solid, Color("#e0e0e0"))
            }

            container.children.forEach { (key, child) ->
                button {
                    this.key = key
                    onClick = { container.selected.value = key }
                    style = jso {
                        flex = Flex(number(1.0), number(1.0), 0.px)
                        padding = Padding(12.px, 16.px)
                        border = None.none
                        background = None.none
                        cursor = Cursor.pointer
                        fontSize = 14.px
                        fontWeight = if (key == selectedKey) FontWeight.bold else FontWeight.normal
                        color = if (key == selectedKey) Color("#1976d2") else Color("#757575")
                        borderBottom = if (key == selectedKey) Border(2.px, LineStyle.solid, Color("#1976d2"))
                        else Border(2.px, LineStyle.solid, Color("transparent"))
                    }
                    +child.label
                }
            }
        }

        div {
            style = jso {
                flex = Flex(number(1.0), number(1.0), 0.px)
                overflow = Auto.auto
            }
            +renderFn(activeChild.graph, true)
        }
    }
}
