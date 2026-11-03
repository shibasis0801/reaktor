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

class PlanDesign(plans: List<WorkPlan>, code: Boolean = false, links: (String) -> List<String> = { emptyList() }) : AutoCloseable {
    val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koinApplication {}), label = "Design")
    val scopes: List<PlanDesignScope> = plans.map { plan -> graph.Graph { parent -> PlanDesignScope(parent, plan, code, links) } }

    fun shape(): GraphShape = graph.shape().stable(buildMap {
        put(graph.id.toString(), "design")
        scopes.forEach { scope ->
            val thread = scope.plan.thread
            put(scope.id.toString(), thread)
            scope.code?.let { put(it.id.toString(), "$thread code") }
            scope.boxes.forEach { (item, node) -> put(node.id.toString(), "$thread#$item") }
            scope.refs.forEach { (ref, node) -> put(node.id.toString(), "$thread ref $ref") }
        }
    })

    override fun close() = graph.close()

    companion object {
        const val WaitsOn = "waits on"

        fun shape(plans: List<WorkPlan>, code: Boolean = false, links: (String) -> List<String> = { emptyList() }): GraphShape = PlanDesign(plans, code, links).use { it.shape() }

        fun count(port: String): Int? = port.takeIf { it.startsWith("$WaitsOn ·") }?.substringAfter("$WaitsOn · ")?.substringBefore(' ')?.toIntOrNull()
    }
}

class PlanDesignScope(parent: Graph, val plan: WorkPlan, includeCode: Boolean = false, links: (String) -> List<String> = { emptyList() }) : Graph(parentGraph = parent, label = plan.title) {
    private val components = plan.components()
    private val chosen: List<PlanItem> = components.filter { it.parent == null || plan.item(it.parent) == null }.let { roots ->
        roots.singleOrNull()?.let { only -> components.filter { it.parent == only.id }.ifEmpty { null } } ?: roots
    }
    private val owner: Map<String, String> = chosen.flatMap { box -> plan.under(box.id).map { it to box.id } }.toMap()

    val boxes: Map<String, PlanBoxNode> = chosen.associate { box -> box.id to Node { PlanBoxNode(it, plan, box) } }

    private val touched: Map<String, List<String>> = if (!includeCode) emptyMap() else chosen.associate { box ->
        box.id to plan.under(box.id).mapNotNull(plan::item).flatMap { it.refs }.flatMap { ref -> if (ref.startsWith("module:")) listOf(ref) else links(ref).filter { it.startsWith("module:") } }.distinct()
    }
    val code: PlanCode? = if (touched.values.all { it.isEmpty() }) null else Graph { PlanCode(it) }
    val refs: Map<String, ReaktorRefNode> = touched.values.flatten().distinct().associateWith { ref -> code!!.Node { ReaktorRefNode(it, plan, ref) } }

    init {
        val waits = HashMap<Pair<String, String>, Int>()
        val relations = LinkedHashMap<Pair<String, String>, LinkedHashSet<String>>()
        plan.items.forEach { item ->
            val from = owner[item.id] ?: return@forEach
            item.after.mapNotNull(owner::get).filter { it != from }.forEach { to -> waits.merge(from to to, 1, Int::plus) }
            item.relations.forEach { relation -> owner[relation.to]?.takeIf { it != from }?.let { to -> relations.getOrPut(from to to) { LinkedHashSet() } += relation.label } }
        }
        waits.forEach { (pair, count) -> boxes.getValue(pair.first).waits(boxes.getValue(pair.second), count) }
        relations.forEach { (pair, labels) -> boxes.getValue(pair.first).relates(boxes.getValue(pair.second), labels.joinToString(", ")) }
        touched.forEach { (box, modules) -> modules.forEach { ref -> boxes.getValue(box).touches(refs.getValue(ref)) } }
    }
}

class PlanBoxNode(graph: Graph, val plan: WorkPlan, val item: PlanItem) : BasicNode(graph), ShapeAttributes {
    override val label: String = item.title
    override val kind: NodeKind = NodeKind.Container
    val provided = registerProvider(Key(item.id), BoxType, this)

    override val shapeAttributes: Map<String, String>
        get() = buildMap {
            put("plan", plan.thread); put("item", item.id); put("design", "component"); put("kind", item.kind.label); put("status", plan.state(item.id).label)
            put("face", "prose")
            val parents = plan.items.mapNotNullTo(HashSet()) { it.parent }
            val work = plan.under(item.id).minus(item.id).filter { it !in parents }.map(plan::state).filter { it != PlanStatus.Dropped }
            if (work.isNotEmpty()) put("parts", "${work.count { it == PlanStatus.Done }} of ${work.size} done")
            work.count { it == PlanStatus.Doing }.takeIf { it > 0 }?.let { put("doing", it.toString()) }
            if (item.detail.isNotBlank()) put("detail", item.detail.take(300))
        }

    fun waits(target: PlanBoxNode, count: Int) {
        connect(registerConsumer<PlanBoxNode>(Key("${PlanDesign.WaitsOn} · $count → ${target.item.id}"), BoxType), target.provided).getOrThrow()
    }

    fun relates(target: PlanBoxNode, labels: String) {
        connect(registerConsumer<PlanBoxNode>(Key("$labels → ${target.item.id}"), BoxType), target.provided).getOrThrow()
    }

    fun touches(module: ReaktorRefNode) {
        connect(registerConsumer<ReaktorRefNode>(Key(module.ref), PlanItemNode.RefType), module.provided).getOrThrow()
    }

    companion object {
        val BoxType = Type.create(PlanBoxNode::class)
    }
}
