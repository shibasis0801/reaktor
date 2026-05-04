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
import react.dom.html.ReactHTML.span
import js.objects.jso
import web.cssom.*
import kotlin.js.JsExport

@JsExport
open class WebBottomNavigationContainer(
    graph: Graph,
    pattern: String,
    val children: Map<String, ChildGraph>,
    initialSelection: String,
    val bottomNavKeys: Set<String> = children.keys
) : ContainerNode(
    graph, pattern,
    ArrayList(children.values.map { it.graph })
), ReactContainer {
    val selected = MutableStateFlow(initialSelection)

    val controller by provides<Controller>(object : Controller {
        override val selected = this@WebBottomNavigationContainer.selected
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
        return WebBottomNavLayout.create {
            this.container = this@WebBottomNavigationContainer
            this.renderer = renderer
        }
    }
}

external interface WebBottomNavProps : Props {
    var container: WebBottomNavigationContainer
    var renderer: (Graph, Boolean) -> Component?
}

val WebBottomNavLayout = FC<WebBottomNavProps> { props ->
    val container = props.container
    val renderFn = props.renderer

    val (selectedKey, _) = container.selected.toReactState()
    val activeChild = container.children[selectedKey] ?: return@FC

    val (entries, _) = activeChild.graph.backStack.entries.toReactState()
    val isAtRoot = entries.size <= 1

    div {
        style = jso {
            display = Display.flex
            flexDirection = FlexDirection.column
            height = 100.vh
        }

        div {
            style = jso {
                flex = Flex(number(1.0), number(1.0), 0.px)
                overflow = Auto.auto
            }
            +renderFn(activeChild.graph, true)
        }

        if (isAtRoot) {
            nav {
                style = jso {
                    display = Display.flex
                    justifyContent = JustifyContent.spaceAround
                    alignItems = AlignItems.center
                    borderTop = Border(1.px, LineStyle.solid, Color("#e0e0e0"))
                    backgroundColor = Color("#ffffff")
                    padding = Padding(8.px, 0.px)
                }

                container.children.filter { it.key in container.bottomNavKeys }.forEach { (key, child) ->
                    button {
                        this.key = key
                        onClick = { container.selected.value = key }
                        style = jso {
                            display = Display.flex
                            flexDirection = FlexDirection.column
                            alignItems = AlignItems.center
                            gap = 2.px
                            border = None.none
                            background = None.none
                            cursor = Cursor.pointer
                            padding = Padding(4.px, 12.px)
                            color = if (key == selectedKey) Color("#1976d2") else Color("#757575")
                            fontSize = 12.px
                            fontWeight = if (key == selectedKey) FontWeight.bold else FontWeight.normal
                        }
                        span { +child.label }
                    }
                }
            }
        }
    }
}
