package dev.shibasis.reaktor.graph.visitor

import dev.shibasis.reaktor.graph.core.*
import dev.shibasis.reaktor.portgraph.edge.Edge
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.node.RouteNode
import dev.shibasis.reaktor.graph.core.node.ControllerNode
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import dev.shibasis.reaktor.portgraph.visitor.PortGraphVisitor
import dev.shibasis.reaktor.portgraph.visitor.Visitable
import dev.shibasis.reaktor.portgraph.visitor.Selector

object StructuralSelector : Selector {
    override fun neighbors(visitable: Visitable): List<Visitable> = when (visitable) {
        is Graph -> visitable.nodes
        is Node -> buildList {
            if (visitable is ContainerNode) {
                visitable.graphs.forEach { add(it) }
            }
            addAll(visitable.providerPorts.flattenedValues())
            addAll(visitable.consumerPorts.flattenedValues())
        }
        is ConsumerPort<*> -> visitable.edge?.let { listOf(it) } ?: listOf()
        is ProviderPort<*> -> visitable.edges.values.toList()
        is Edge<*> -> listOf()
        else -> emptyList()
    }
}

object RoutingSelector : Selector {
    override fun neighbors(visitable: Visitable): List<Visitable> = when (visitable) {
        is Graph -> visitable.nodes.filter { it is RouteNode<*, *> || it is ControllerNode<*> }
        is RouteNode<*, *> -> visitable.providerPorts
            .flattenedValues()
            .flatMap { it.edges.keys.map { consumer -> consumer.owner } }
            .filterIsInstance<ControllerNode<*>>()
        is ControllerNode<*> -> visitable.consumerPorts
            .flattenedValues()
            .mapNotNull { it.edge?.provider?.owner }
            .filterIsInstance<RouteNode<*, *>>()
        else -> emptyList()
    }
}

object ConnectivitySelector : Selector {
    override fun neighbors(visitable: Visitable): List<Visitable> = when (visitable) {
        is Node -> visitable.consumerPorts
            .flattenedValues()
            .mapNotNull { consumer ->
                val edge = consumer.edge ?: return@mapNotNull null
                if (edge.source == visitable) edge.destination else edge.source
            }
            .map { it as Visitable }
        else -> emptyList()
    }
}

typealias ExitScope = () -> Unit

open class Visitor : PortGraphVisitor() {
    override fun visit(visitable: Visitable): ExitScope =
        when (visitable) {
            is Graph -> visitGraph(visitable)
            is ContainerNode -> visitContainerNode(visitable)
            is BasicNode -> visitLogicNode(visitable)
            is RouteNode<*, *> -> visitRouteNode(visitable)
            is ControllerNode<*> -> visitControllerNode(visitable)
            is ConsumerPort<*> -> visitConsumerPort(visitable)
            is ProviderPort<*> -> visitProviderPort(visitable)
            is Edge<*> -> visitEdge(visitable)
            else -> super.visit(visitable)
        }

    protected open fun visitGraph(graph: Graph): ExitScope = NoOpExit
    protected open fun visitContainerNode(containerNode: ContainerNode): ExitScope = NoOpExit
    protected open fun visitLogicNode(basicNode: BasicNode): ExitScope = NoOpExit
    protected open fun visitRouteNode(routeNode: RouteNode<*, *>): ExitScope = NoOpExit
    protected open fun visitControllerNode(controllerNode: ControllerNode<*>): ExitScope = NoOpExit
    protected override fun visitConsumerPort(port: ConsumerPort<*>): ExitScope = NoOpExit
    protected override fun visitProviderPort(port: ProviderPort<*>): ExitScope = NoOpExit
    protected override fun visitEdge(edge: Edge<*>): ExitScope = NoOpExit
}

class HierarchyVisitor : Visitor() {
    lateinit var rootMap: MutableMap<String, Any>
    private val mapStack = ArrayDeque<MutableMap<String, Any>>()

    private fun getElementLabel(visitable: Visitable): String = when (visitable) {
        is Graph -> "[Graph] ${visitable.label.ifEmpty { visitable.id.toString() }}"
        is ContainerNode -> "[GraphNode] ${visitable.label.ifEmpty { visitable.id.toString() }}"
        is BasicNode -> "[LogicNode] ${visitable::class.simpleName}"
        is RouteNode<*, *> -> "[RouteNode] ${visitable.pattern}"
        is ControllerNode<*> -> "[StatefulNode] ${visitable::class.simpleName}"
        is ConsumerPort<*> -> "[ConsumerPort] ${visitable.key}"
        is ProviderPort<*> -> "[ProviderPort] ${visitable.key}"
        is Edge<*> -> "[Edge] ${visitable.id.toString().take(8)}..."
        else -> "[Visitable] ${visitable.hashCode()}"
    }

    @Suppress("UNCHECKED_CAST")
    private fun processVisit(visitable: Visitable): ExitScope {
        val currentMap = mutableMapOf<String, Any>("element" to getElementLabel(visitable))

        if (mapStack.isNotEmpty()) {
            val parentMap = mapStack.last()
            val childrenList = parentMap.getOrPut("children") {
                mutableListOf<Map<String, Any>>()
            } as MutableList<Map<String, Any>>
            childrenList.add(currentMap)
        } else {
            rootMap = currentMap
        }

        mapStack.add(currentMap)
        return { mapStack.removeLast() }
    }

    override fun visitGraph(graph: Graph) = processVisit(graph)
    override fun visitContainerNode(containerNode: ContainerNode) = processVisit(containerNode)
    override fun visitLogicNode(basicNode: BasicNode) = processVisit(basicNode)
    override fun visitRouteNode(routeNode: RouteNode<*, *>) = processVisit(routeNode)
    override fun visitControllerNode(controllerNode: ControllerNode<*>) = processVisit(controllerNode)
    override fun visitConsumerPort(port: ConsumerPort<*>) = processVisit(port)
    override fun visitProviderPort(port: ProviderPort<*>) = processVisit(port)
    override fun visitEdge(edge: Edge<*>) = processVisit(edge)
}
