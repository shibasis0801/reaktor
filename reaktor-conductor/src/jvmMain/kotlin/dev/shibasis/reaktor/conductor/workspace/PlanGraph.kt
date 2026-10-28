package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.ShapeAttributes
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.Type
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import org.koin.dsl.koinApplication

class PlanGraph(plans: List<WorkPlan>, links: (String) -> List<String> = { emptyList() }) : AutoCloseable {
    val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koinApplication {}), label = "Plans")
    val scopes: List<PlanScope> = plans.map { plan -> graph.Graph { parent -> PlanScope(parent, plan, links) } }

    fun shape(): GraphShape {
        val shape = graph.shape()
        val ids = buildMap {
            put(graph.id.toString(), "plans")
            scopes.forEach { scope ->
                val thread = scope.plan.thread
                put(scope.id.toString(), thread)
                scope.items.forEach { (item, node) -> put(node.id.toString(), "$thread#$item") }
                scope.refs.forEach { (ref, node) -> put(node.id.toString(), "$thread ref $ref") }
            }
        }
        fun stable(id: String) = ids[id] ?: id
        return shape.copy(
            scopes = shape.scopes.map { it.copy(id = stable(it.id), parent = it.parent?.let(::stable), container = it.container?.let(::stable), backStack = it.backStack.map(::stable)) },
            nodes = shape.nodes.map { it.copy(id = stable(it.id), scope = stable(it.scope), attachedTo = it.attachedTo?.let(::stable)) },
            wires = shape.wires.map { it.copy(consumer = stable(it.consumer), provider = stable(it.provider)) },
            routes = shape.routes.map { it.copy(from = stable(it.from), to = stable(it.to)) },
        )
    }

    override fun close() = graph.close()

    companion object {
        fun shape(plans: List<WorkPlan>, links: (String) -> List<String> = { emptyList() }): GraphShape = PlanGraph(plans, links).use { it.shape() }
    }
}

class PlanScope(parent: Graph, val plan: WorkPlan, links: (String) -> List<String> = { emptyList() }) : Graph(parentGraph = parent, label = plan.title) {
    val items: Map<String, PlanItemNode> = plan.items.associate { item -> item.id to Node { PlanItemNode(it, plan, item) } }
    private val linked: Map<String, List<String>> = plan.items.flatMap { it.refs }.distinct().associateWith { ref -> links(ref).filter { it != ref } }
    val refs: Map<String, ReaktorRefNode> = (linked.keys + linked.values.flatten()).distinct().associateWith { ref -> Node { ReaktorRefNode(it, plan, ref) } }

    init {
        items.values.forEach { it.wire(items, refs) }
        linked.forEach { (ref, targets) -> refs.getValue(ref).wire(targets.mapNotNull(refs::get)) }
    }
}

class PlanItemNode(graph: Graph, val plan: WorkPlan, val item: PlanItem) : BasicNode(graph), ShapeAttributes {
    override val label: String = item.title
    override val kind: NodeKind = NodeKind.Workflow
    val provided = registerProvider(Key(item.id), ItemType, this)

    override val shapeAttributes: Map<String, String>
        get() = buildMap {
            put("plan", plan.thread); put("item", item.id); put("status", item.status.label)
            item.seat?.let(plan::seat)?.let { put("seat", "${it.profile} · ${it.id}") }
            item.by?.let { put("by", it.name) }
            if (item.detail.isNotBlank()) put("detail", item.detail.take(300))
        }

    fun wire(items: Map<String, PlanItemNode>, refs: Map<String, ReaktorRefNode>) {
        item.after.forEach { id -> items[id]?.let { upstream -> connect(registerConsumer<PlanItemNode>(Key("after $id"), ItemType), upstream.provided).getOrThrow() } }
        item.parent?.let(items::get)?.let { parent -> connect(registerConsumer<PlanItemNode>(Key("part of"), ItemType), parent.provided).getOrThrow() }
        item.refs.forEach { ref -> refs[ref]?.let { node -> connect(registerConsumer<ReaktorRefNode>(Key(ref), RefType), node.provided).getOrThrow() } }
    }

    companion object {
        val ItemType = Type.create(PlanItemNode::class)
        val RefType = Type.create(ReaktorRefNode::class)
    }
}

class ReaktorRefNode(graph: Graph, val plan: WorkPlan, val ref: String) : BasicNode(graph), ShapeAttributes {
    val kindName: String = ref.substringBefore(':')
    override val label: String = ref.substringAfter(':').let { target -> if (kindName == "file" || kindName == "module") target.substringAfterLast('/') else target }
    override val kind: NodeKind = when (kindName) {
        "graph" -> NodeKind.Container
        "op" -> NodeKind.Service
        "module" -> NodeKind.Store
        else -> NodeKind.External
    }
    val provided = registerProvider(Key(ref), PlanItemNode.RefType, this)

    override val shapeAttributes: Map<String, String> get() = mapOf("ref" to ref, "link" to kindName, "plan" to plan.thread)

    fun wire(targets: List<ReaktorRefNode>) = targets.forEach { target -> connect(registerConsumer<ReaktorRefNode>(Key("in ${target.ref}"), PlanItemNode.RefType), target.provided).getOrThrow() }
}
