package dev.shibasis.reaktor.portgraph.visitor

import dev.shibasis.reaktor.portgraph.edge.Edge
import dev.shibasis.reaktor.portgraph.graph.PortGraph
import dev.shibasis.reaktor.portgraph.node.PortNode
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import kotlin.js.JsExport

@JsExport
fun interface Selector {
    fun neighbors(visitable: Visitable): List<Visitable>
}

@JsExport
object StructuralSelector : Selector {
    override fun neighbors(visitable: Visitable): List<Visitable> = when (visitable) {
        is PortGraph<*, *> -> visitable.nodes
        is PortNode<*> -> buildList {
            addAll(visitable.providerPorts.flattenedValues())
            addAll(visitable.consumerPorts.flattenedValues())
        }
        is ConsumerPort<*> -> visitable.edge?.let { listOf(it) } ?: listOf()
        is ProviderPort<*> -> visitable.edges.values.toList()
        is Edge<*> -> listOf()
        else -> emptyList()
    }
}

@JsExport
object ConnectivitySelector : Selector {
    override fun neighbors(visitable: Visitable): List<Visitable> = when (visitable) {
        is PortNode<*> -> {
            visitable.consumerPorts
                .flattenedValues()
                .mapNotNull { consumer ->
                    val edge = consumer.edge ?: return@mapNotNull null
                    if (edge.source == visitable) edge.destination else edge.source
                }
                .map { it as Visitable }
        }
        else -> emptyList()
    }
}

@JsExport
fun interface Traverser {
    fun traverse(
        start: Visitable,
        selector: Selector,
        visitor: PortGraphVisitor
    )
}

@JsExport
object DepthFirstTraverser : Traverser {
    override fun traverse(start: Visitable, selector: Selector, visitor: PortGraphVisitor) {
        traverseRecursive(start, selector, visitor, mutableSetOf())
    }

    private fun traverseRecursive(
        current: Visitable,
        selector: Selector,
        visitor: PortGraphVisitor,
        visited: MutableSet<Visitable>
    ) {
        if (!visited.add(current)) return

        val onExit = visitor.visit(current)

        selector.neighbors(current).forEach { neighbor ->
            traverseRecursive(neighbor, selector, visitor, visited)
        }

        onExit()
    }
}

@JsExport
object BreadthFirstTraverser : Traverser {
    override fun traverse(start: Visitable, selector: Selector, visitor: PortGraphVisitor) {
        val visited = mutableSetOf<Visitable>()
        val queue = ArrayDeque<Visitable>()
        queue.add(start)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue

            visitor.visit(current)

            selector.neighbors(current).forEach { n ->
                if (!visited.contains(n)) {
                    queue.add(n)
                }
            }
        }
    }
}

typealias ExitScope = () -> Unit

@JsExport
open class PortGraphVisitor {
    protected val NoOpExit: ExitScope = {}

    open fun visit(visitable: Visitable): ExitScope =
        when (visitable) {
            is PortGraph<*, *> -> visitGraph(visitable)
            is PortNode<*> -> visitNode(visitable)
            is ConsumerPort<*> -> visitConsumerPort(visitable)
            is ProviderPort<*> -> visitProviderPort(visitable)
            is Edge<*> -> visitEdge(visitable)
            else -> NoOpExit
        }

    protected open fun visitGraph(graph: PortGraph<*, *>): ExitScope = NoOpExit
    protected open fun visitNode(node: PortNode<*>): ExitScope = NoOpExit
    protected open fun visitConsumerPort(port: ConsumerPort<*>): ExitScope = NoOpExit
    protected open fun visitProviderPort(port: ProviderPort<*>): ExitScope = NoOpExit
    protected open fun visitEdge(edge: Edge<*>): ExitScope = NoOpExit
}

@JsExport
class HierarchyVisitor : PortGraphVisitor() {
    lateinit var rootMap: MutableMap<String, Any>

    private val mapStack = ArrayDeque<MutableMap<String, Any>>()

    private fun getElementLabel(visitable: Visitable): String = when (visitable) {
        is PortGraph<*, *> -> "[PortGraph] ${visitable.label.ifEmpty { visitable.id.toString() }}"
        is PortNode<*> -> "[PortNode] ${visitable.label.ifEmpty { visitable.id.toString() }}"
        is ConsumerPort<*> -> "[ConsumerPort] ${visitable.key}"
        is ProviderPort<*> -> "[ProviderPort] ${visitable.key}"
        is Edge<*> -> "[Edge] ${visitable.id.toString().take(8)}..."
        else -> "[Visitable] ${visitable.hashCode()}"
    }

    @Suppress("UNCHECKED_CAST")
    private fun processVisit(visitable: Visitable): ExitScope {
        val currentMap = mutableMapOf<String, Any>(
            "element" to getElementLabel(visitable)
        )

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

    override fun visitGraph(graph: PortGraph<*, *>) = processVisit(graph)
    override fun visitNode(node: PortNode<*>) = processVisit(node)
    override fun visitConsumerPort(port: ConsumerPort<*>) = processVisit(port)
    override fun visitProviderPort(port: ProviderPort<*>) = processVisit(port)
    override fun visitEdge(edge: Edge<*>) = processVisit(edge)
}
