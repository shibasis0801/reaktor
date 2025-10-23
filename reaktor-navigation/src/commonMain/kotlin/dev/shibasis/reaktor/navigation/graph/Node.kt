@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.navigation.graph

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.io.network.RoutePattern
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapability
import dev.shibasis.reaktor.core.capabilities.ConcurrencyCapabilityImpl
import dev.shibasis.reaktor.navigation.capabilities.LifecycleCapability
import dev.shibasis.reaktor.navigation.capabilities.LifecycleCapabilityImpl
import dev.shibasis.reaktor.navigation.capabilities.Unique
import dev.shibasis.reaktor.core.capabilities.invoke
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid


sealed class Node(
    val graph: Graph,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    override val id: Uuid = Uuid.random(),
):
    Unique,
    LifecycleCapability by LifecycleCapabilityImpl(),
    PortCapability by PortCapabilityImpl(),
    ConcurrencyCapability by ConcurrencyCapabilityImpl(
        graph.coroutineScope.coroutineContext,
        dispatcher
    )
{
    override fun close() {
        invoke<LifecycleCapability> { close() }
        invoke<ConcurrencyCapability> { close() }
    }
}

open class GraphNode(
    val childGraph: Graph,
    parent: Graph
): Node(parent) {

}

abstract class LogicNode(
    graph: Graph
): Node(graph) {

}

@Serializable
data class Parameters(val routeParams: HashMap<String, String>)

interface RouteBinding {
    fun parameters(): StateFlow<Parameters>
}

abstract class RouteNode(
    graph: Graph,
    val pattern: RoutePattern,
): Node(graph) {
    abstract val bindingPort: ProducerPort<RouteBinding>
}

abstract class ViewNode<State>(
    graph: Graph
): Node(graph) {
    abstract val state: MutableStateFlow<State>
    val routeBinding by consumer<RouteBinding>()
}

fun interface ComposeView {
    @Composable
    fun Compose()
}

// participate in both hierarchies.
abstract class ComposeViewNode<State>(
    graph: Graph
): ViewNode<State>(graph), ComposeView














