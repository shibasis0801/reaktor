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
import kotlinx.serialization.json.JsonPrimitive
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
                scope.parts.forEach { (item, part) -> put(part.id.toString(), "$thread parts $item") }
                scope.code?.let { put(it.id.toString(), "$thread code") }
                scope.items.forEach { (item, node) -> put(node.id.toString(), "$thread#$item") }
                scope.refs.forEach { (ref, node) -> put(node.id.toString(), "$thread ref $ref") }
            }
        }
        fun stable(id: String) = ids[id] ?: id
        val rank = ids.values.withIndex().associate { it.value to it.index }
        fun rank(id: String) = rank[id] ?: Int.MAX_VALUE
        return shape.copy(
            scopes = shape.scopes.map { it.copy(id = stable(it.id), parent = it.parent?.let(::stable), container = it.container?.let(::stable), backStack = it.backStack.map(::stable)) }
                .sortedBy { rank(it.id) },
            nodes = shape.nodes.map { it.copy(id = stable(it.id), scope = stable(it.scope), attachedTo = it.attachedTo?.let(::stable)) }.sortedBy { rank(it.id) },
            wires = shape.wires.map { it.copy(consumer = stable(it.consumer), provider = stable(it.provider)) }.sortedWith(compareBy({ rank(it.consumer) }, { it.consumerPort })),
            routes = shape.routes.map { it.copy(from = stable(it.from), to = stable(it.to)) },
        )
    }

    override fun close() = graph.close()

    companion object {
        fun shape(plans: List<WorkPlan>, links: (String) -> List<String> = { emptyList() }): GraphShape = PlanGraph(plans, links).use { it.shape() }
    }
}

class PlanScope(parent: Graph, val plan: WorkPlan, links: (String) -> List<String> = { emptyList() }) : Graph(parentGraph = parent, label = plan.title) {
    private val containers: Set<String> = plan.items.mapNotNullTo(HashSet()) { it.parent }.filterTo(HashSet()) { plan.item(it) != null }
    private val made = LinkedHashMap<String, PlanPart>()
    val parts: Map<String, PlanPart> get() = made

    private fun scopeOf(id: String?, seen: Set<String> = emptySet()): Graph {
        val item = id?.let(plan::item)?.takeIf { it.id !in seen } ?: return this
        made[item.id]?.let { return it }
        return scopeOf(item.parent, seen + item.id).Graph { PlanPart(it, plan, item) }.also { made[item.id] = it }
    }

    val items: Map<String, PlanItemNode> = plan.items.associate { item ->
        item.id to scopeOf(if (item.id in containers) item.id else item.parent).Node { PlanItemNode(it, plan, item) }
    }
    private val linked: Map<String, List<String>> = buildMap {
        val queue = ArrayDeque(plan.items.flatMap { it.refs }.distinct().map { it to 0 })
        while (queue.isNotEmpty()) {
            val (ref, depth) = queue.removeFirst()
            if (ref in this) continue
            val targets = if (depth < LinkDepth) links(ref).filter { it != ref } else emptyList()
            put(ref, targets)
            targets.forEach { queue.addLast(it to depth + 1) }
        }
    }
    val code: PlanCode? = if (linked.isEmpty()) null else Graph { PlanCode(it) }
    val refs: Map<String, ReaktorRefNode> = (linked.keys + linked.values.flatten()).distinct().associateWith { ref -> code!!.Node { ReaktorRefNode(it, plan, ref) } }

    init {
        items.values.forEach { it.wire(items, refs) }
        linked.forEach { (ref, targets) -> refs.getValue(ref).wire(targets.mapNotNull(refs::get)) }
    }
}

private const val LinkDepth = 2

class PlanPart(parent: Graph, val plan: WorkPlan, val item: PlanItem) : Graph(parentGraph = parent, label = item.title)

class PlanCode(parent: Graph) : Graph(parentGraph = parent, label = "In Reaktor")

class PlanItemNode(graph: Graph, val plan: WorkPlan, val item: PlanItem) : BasicNode(graph), ShapeAttributes {
    override val label: String = item.title
    override val kind: NodeKind = NodeKind.Workflow
    val provided = registerProvider(Key(item.id), ItemType, this)

    override val shapeAttributes: Map<String, String>
        get() = buildMap {
            put("plan", plan.thread); put("item", item.id); put("kind", item.kind.label); put("status", plan.state(item.id).label); put("priority", item.priority.name)
            if (item.reason.isNotBlank()) put("reason", item.reason)
            item.replacement?.let { put("replacement", it) }
            plan.parts(item.id).map { plan.state(it.id) }.filter { it != PlanStatus.Dropped }.takeIf { it.isNotEmpty() }?.let { open ->
                put("parts", "${open.count { it == PlanStatus.Done }} of ${open.size} done")
            }
            item.seat?.let(plan::seat)?.let { put("seat", "${it.profile} · ${it.id}") }
            item.by?.let { put("by", it.name) }
            if (item.detail.isNotBlank()) put("detail", item.detail.take(300))
        }

    fun wire(items: Map<String, PlanItemNode>, refs: Map<String, ReaktorRefNode>) {
        item.after.forEach { id -> items[id]?.let { upstream -> connect(registerConsumer<PlanItemNode>(Key("after $id"), ItemType), upstream.provided).getOrThrow() } }
        item.replacement?.let(items::get)?.let { target ->
            connect(registerConsumer<PlanItemNode>(Key("replaced by"), ItemType), target.provided).getOrThrow()
        }
        val taken = (item.after.map { "after $it" } + item.refs + "replaced by").toMutableSet()
        val labels = item.relations.groupingBy { it.label }.eachCount()
        item.relations.forEach { relation ->
            items[relation.to]?.takeIf { relation.to != item.id }?.let { target ->
                val port = (if (labels.getValue(relation.label) > 1) "${relation.label} ${relation.to}" else relation.label)
                    .let { if (it in taken) "${relation.label} → ${relation.to}" else it }.also(taken::add)
                connect(registerConsumer<PlanItemNode>(Key(port), ItemType), target.provided).getOrThrow()
            }
        }
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

    override val shapeAttributes: Map<String, String> get() = buildMap {
        put("ref", ref); put("link", kindName); put("plan", plan.thread)
        explore()?.let { put("explore", it) }
    }

    private fun explore(): String? {
        val target = JsonPrimitive(ref.substringAfter(':').trim()).toString()
        return when (kindName) {
            "symbol" -> """atlas_query {"from":{"name":$target},"follow":[{"rel":"references","dir":"in"}]}"""
            "graph" -> """atlas_query {"preset":"explain","from":{"name":$target}}"""
            "file" -> """atlas_read {"path":$target,"part":"outline"}"""
            "op" -> """kernel {"operation":"operation.describe","arguments":{"operation":$target}}"""
            else -> null
        }
    }

    fun wire(targets: List<ReaktorRefNode>) = targets.forEach { target -> connect(registerConsumer<ReaktorRefNode>(Key("in ${target.ref}"), PlanItemNode.RefType), target.provided).getOrThrow() }
}
