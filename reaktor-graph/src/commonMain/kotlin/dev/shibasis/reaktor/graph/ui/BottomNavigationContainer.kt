package dev.shibasis.reaktor.graph.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.material3.Icon
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.port.provides
import dev.shibasis.reaktor.ui.themed
import kotlinx.coroutines.flow.MutableStateFlow

data class ChildGraph(
    val icon: String,
    val label: String,
    val graph: Graph
)

interface Controller {
    val selected: MutableStateFlow<String>
}

open class BottomNavigationContainer(
    graph: Graph,
    val children: Map<String, ChildGraph>,
    initialSelection: String
): ContainerNode(
    graph,
    ArrayList(children.values.map { it.graph })
), ComposeContainer {
    val selected = MutableStateFlow(initialSelection)

    val controller by provides<Controller>(object: Controller {
        override val selected = this@BottomNavigationContainer.selected
    })

    @Composable
    override fun Content(renderer: @Composable (graph: Graph, isFocused: Boolean) -> Unit) = themed {
        val contract = controller.impl

        val selected by contract.selected.collectAsState()
        val activeGraph = children[selected] ?: throw IllegalStateException("selected key is invalid")

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar {
                    children.forEach { (key, value) ->
                        NavigationBarItem(
                            selected = (selected == key),
                            onClick = { contract.selected.value = key },
                            icon = { Icon(Icons.Filled.Map, value.label) },
                            label = { TextView(text = value.label) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                renderer(activeGraph.graph, true)
            }
        }
    }
}











