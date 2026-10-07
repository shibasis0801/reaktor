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
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow

class SessionSlot<K : Any>(
    parent: Graph,
    pattern: String,
    private val identity: StateFlow<K?>,
    private val build: (K) -> Graph,
) : ContainerNode(parent, pattern), ComposeContainer {
    private val lock = SynchronizedObject()
    private val current = MutableStateFlow<Graph?>(null)
    private val failureState = MutableStateFlow<Pair<K?, Throwable>?>(null)
    private val shown = mutableMapOf<Graph, Int>()
    private val retired = mutableSetOf<Graph>()
    private var collector: Job? = null
    private var closed = false
    private var activeIdentity: K? = null
    val child: StateFlow<Graph?> = current.asStateFlow()
    val failure: StateFlow<Pair<K?, Throwable>?> = failureState.asStateFlow()

    override val changes: List<Flow<*>> get() = listOf(activeGraphIndex, child)

    override val dormant: GraphShape? get() = if (child.value == null) blueprint else null

    override fun shows(graph: Graph): Boolean = child.value == graph

    var blueprint: GraphShape? = null
        private set

    fun start(): Unit = synchronized(lock) {
        if (closed || collector?.isActive == true && failureState.value == null) return
        var firstEmission = collector == null
        collector?.cancel()
        failureState.value = null
        collector = coroutineScope.launch(Dispatchers.Main) {
            identity.collectLatest { next ->
                val unchanged = synchronized(lock) {
                    val keepPreview = firstEmission && next == null && blueprint != null
                    firstEmission = false
                    keepPreview || activeIdentity == next && failureState.value == null
                }
                if (unchanged) return@collectLatest
                var built: Graph? = null
                try {
                    if (next != null) withContext(Dispatchers.Default) { built = build(next) }
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        if (!closed) {
                            val activation = built
                            built = null
                            swap(next, activation)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    synchronized(lock) { if (!closed) failureState.value = next to failure }
                } finally {
                    built?.close()
                }
            }
        }
    }

    fun identityOf(graph: Graph): K? = synchronized(lock) {
        activeIdentity.takeIf { current.value === graph }
    }

    fun preview(key: K): Unit = synchronized(lock) {
        if (closed || current.value != null) return
        swap(key, build(key))
        blueprint = current.value?.shape()
    }

    private fun swap(next: K?, built: Graph?) {
        val previous = current.value
        if (built != null) {
            graphs.add(built)
            activate(built)
        }
        activeIdentity = next
        failureState.value = null
        current.value = built
        previous?.let(::retire)
    }

    private fun retire(graph: Graph) {
        graphs.remove(graph)
        current.value?.let(::activate)
        if (graph in shown) retired += graph else graph.close()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            collector?.cancel()
            current.value?.let(::retire)
            current.value = null
            graphs.clear()
        }
        super.close()
    }

    @Composable
    override fun Content(renderer: @Composable (graph: Graph, isFocused: Boolean) -> Unit) {
        val graph by current.collectAsState()
        graph?.takeIf { it === current.value }?.let { showing ->
            key(showing.id) {
                DisposableEffect(showing) {
                    synchronized(lock) { shown[showing] = (shown[showing] ?: 0) + 1 }
                    onDispose {
                        synchronized(lock) {
                            val remaining = (shown[showing] ?: 1) - 1
                            if (remaining > 0) shown[showing] = remaining else {
                                shown.remove(showing)
                                if (retired.remove(showing)) showing.close()
                            }
                        }
                    }
                }
                renderer(showing, true)
            }
        }
    }
}
