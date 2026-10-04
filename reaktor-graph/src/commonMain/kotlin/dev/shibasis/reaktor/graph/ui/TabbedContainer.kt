package dev.shibasis.reaktor.graph.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.portgraph.port.provides
import dev.shibasis.reaktor.ui.themed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow

open class TabbedContainer(
    graph: Graph,
    pattern: String,
    val children: Map<String, ChildGraph>,
    initialSelection: String
): ContainerNode(
    graph, pattern,
    ArrayList(children.values.map { it.graph })
), ComposeContainer {
    val selected = MutableStateFlow(initialSelection)

    override val changes: List<Flow<*>> get() = listOf(activeGraphIndex, selected)

    override fun shows(graph: Graph): Boolean = children[selected.value]?.graph == graph

    override fun labelOf(graph: Graph): String =
        children.entries.firstOrNull { it.value.graph == graph }?.let { (key, child) -> child.label.ifBlank { key } }
            ?: super.labelOf(graph)

    val controller by provides<Controller>(object: Controller {
        override val selected = this@TabbedContainer.selected
    })

    override fun activate(graph: Graph): Boolean {
        val activated = super.activate(graph)
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
        val selectedIndex = children.keys.indexOf(selected)

        Column(modifier = Modifier.fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = selectedIndex) {
                children.forEach { (key, value) ->
                    Tab(
                        selected = (selected == key),
                        onClick = { contract.selected.value = key },
                        text = { TextView(text = value.label) },
                        icon = { Icon(value.icon, value.label) }
                    )
                }
            }
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
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
