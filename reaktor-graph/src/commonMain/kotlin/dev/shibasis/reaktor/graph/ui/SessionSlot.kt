package dev.shibasis.reaktor.graph.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow

class SessionSlot<K : Any>(
    parent: Graph,
    pattern: String,
    private val identity: StateFlow<K?>,
    private val build: (K) -> Graph,
) : ContainerNode(parent, pattern), ComposeContainer {
    private val current = MutableStateFlow<Graph?>(null)
    private val shown = mutableSetOf<Graph>()
    private val retired = mutableSetOf<Graph>()
    val child: StateFlow<Graph?> = current.asStateFlow()

    override val changes: List<Flow<*>> get() = listOf(activeGraphIndex, child)

    override val dormant: GraphShape? get() = if (child.value == null) blueprint else null

    override fun shows(graph: Graph): Boolean = child.value == graph

    var blueprint: GraphShape? = null
        private set

    fun start() {
        coroutineScope.launch(Dispatchers.Main) { identity.collect(::swap) }
    }

    fun preview(key: K) {
        if (current.value != null) return
        swap(key)
        blueprint = current.value?.shape()
    }

    private fun swap(next: K?) {
        val previous = current.value
        val built = next?.let(build)
        if (built != null) {
            graphs.add(built)
            activate(built)
        }
        current.value = built
        previous?.let(::retire)
    }

    private fun retire(graph: Graph) {
        graphs.remove(graph)
        if (graph in shown) retired += graph else graph.close()
    }

    override fun close() {
        (retired + listOfNotNull(current.value)).forEach(Graph::close)
        retired.clear()
        current.value = null
        graphs.clear()
        super.close()
    }

    @Composable
    override fun Content(renderer: @Composable (graph: Graph, isFocused: Boolean) -> Unit) {
        val graph by current.collectAsState()
        graph?.takeIf { it === current.value }?.let { showing ->
            key(showing.id) {
                DisposableEffect(showing) {
                    shown += showing
                    onDispose {
                        shown -= showing
                        if (retired.remove(showing)) showing.close()
                    }
                }
                renderer(showing, true)
            }
        }
    }
}
