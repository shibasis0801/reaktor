package dev.shibasis.reaktor.graph.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.material3.Icon
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.io.network.RoutePattern
import dev.shibasis.reaktor.portgraph.port.provides
import dev.shibasis.reaktor.ui.themed
import kotlinx.coroutines.flow.MutableStateFlow

interface Navigable {
    val key: String
    val label: String
    val icon: ImageVector
}

typealias BottomNavigable = Navigable

data class ChildGraph(
    val graph: Graph,
    override val key: String,
    override val label: String,
    override val icon: ImageVector
): Navigable


interface Controller {
    val selected: MutableStateFlow<String>
}

open class BottomNavigationContainer(
    graph: Graph,
    pattern: String,
    val children: Map<String, ChildGraph>,
    initialSelection: String,
    val bottomNavKeys: Set<String> = children.keys
): ContainerNode(
    graph, pattern,
    ArrayList(children.values.map { it.graph })
), ComposeContainer {
    val selected = MutableStateFlow(initialSelection)

    var topBar: (@Composable (selectedKey: String, isAtRoot: Boolean) -> Unit)? = null

    val controller by provides<Controller>(object: Controller {
        override val selected = this@BottomNavigationContainer.selected
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

    @Composable
    override fun Content(renderer: @Composable (graph: Graph, isFocused: Boolean) -> Unit) = themed {
        val contract = controller.impl

        val selected by contract.selected.collectAsState()
        val activeGraph = children[selected] ?: throw IllegalStateException("selected key is invalid")

        val entries by activeGraph.graph.backStack.entries.collectAsState()
        val isAtRoot = entries.size <= 1

        val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
        val isKeyboardVisible = imeBottom > 0

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = { if (isAtRoot) topBar?.invoke(selected, isAtRoot) },
            bottomBar = {
                if (!isKeyboardVisible && isAtRoot) {
                    NavigationBar {
                        children.filter { it.key in bottomNavKeys }.forEach { (key, value) ->
                            NavigationBarItem(
                                selected = (selected == key),
                                onClick = { contract.selected.value = key },
                                icon = { Icon(value.icon, value.label) },
                                label = { TextView(text = value.label) }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                children.forEach { (childKey, child) ->
                    key(child.graph.id) {
                        if (childKey == selected) {
                            renderer(child.graph, true)
                        }
                    }
                }
            }
        }
    }
}
