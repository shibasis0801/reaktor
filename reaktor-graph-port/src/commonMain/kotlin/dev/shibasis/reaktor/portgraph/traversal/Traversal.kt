package dev.shibasis.reaktor.portgraph.traversal

import dev.shibasis.reaktor.portgraph.Unique
import dev.shibasis.reaktor.portgraph.graph.PortGraph
import dev.shibasis.reaktor.portgraph.node.PortNode
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.PortCapability
import dev.shibasis.reaktor.portgraph.port.flattenedValues

fun interface Relation {
    fun next(element: Visitable): Sequence<Visitable>
}

sealed interface Step {
    val element: Visitable
    val parent: Visitable?
    val depth: Int

    data class Enter(override val element: Visitable, override val parent: Visitable?, override val depth: Int) : Step
    data class Leave(override val element: Visitable, override val parent: Visitable?, override val depth: Int) : Step
}

object Members : Relation {
    override fun next(element: Visitable): Sequence<Visitable> = when (element) {
        is PortGraph<*, *> -> element.members().asSequence()
        is PortNode<*> -> element.members().asSequence()
        else -> emptySequence()
    }
}

object Contains : Relation {
    override fun next(element: Visitable): Sequence<Visitable> = when (element) {
        is ConsumerPort<*> -> listOfNotNull(element.edge).asSequence()
        is PortCapability -> element.ports() + Members.next(element)
        else -> Members.next(element)
    }
}

object Upstream : Relation {
    override fun next(element: Visitable): Sequence<Visitable> =
        (element as? PortCapability)?.consumerPorts?.flattenedValues().orEmpty().asSequence()
            .mapNotNull { it.edge?.destination as? Visitable }
            .distinct()
}

object Downstream : Relation {
    override fun next(element: Visitable): Sequence<Visitable> =
        (element as? PortCapability)?.providerPorts?.flattenedValues().orEmpty().asSequence()
            .flatMap { it.edges.values }
            .mapNotNull { it.source as? Visitable }
            .distinct()
}

fun Visitable.depthFirst(relation: Relation, enter: (Visitable) -> Boolean = { true }): Sequence<Step> =
    Sequence { DepthFirst(this, relation, enter) }

fun Visitable.breadthFirst(relation: Relation, enter: (Visitable) -> Boolean = { true }): Sequence<Step.Enter> = sequence {
    val seen = hashSetOf(this@breadthFirst)
    val queue = ArrayDeque(listOf(Step.Enter(this@breadthFirst, null, 0)))
    while (queue.isNotEmpty()) {
        val step = queue.removeFirst()
        yield(step)
        if (enter(step.element)) relation.next(step.element).forEach { if (seen.add(it)) queue.addLast(Step.Enter(it, step.element, step.depth + 1)) }
    }
}

fun <T : Visitable> Collection<T>.topological(after: Relation = Upstream): List<T> {
    val elements = toList()
    val index = HashMap<Visitable, Int>().apply { elements.forEachIndexed { at, element -> put(element, at) } }
    val before = elements.map { element -> after.next(element).mapNotNullTo(LinkedHashSet()) { index[it] } }
    val waiting = IntArray(elements.size) { before[it].size }
    val unblocks = List(elements.size) { mutableListOf<Int>() }
    before.forEachIndexed { at, prerequisites -> prerequisites.forEach { unblocks[it] += at } }
    val ready = elements.indices.filterTo(mutableListOf()) { waiting[it] == 0 }
    val order = ArrayList<T>(elements.size)
    while (ready.isNotEmpty()) {
        val next = ready.removeAt(0)
        order += elements[next]
        unblocks[next].forEach { dependent -> if (--waiting[dependent] == 0) ready.add(-(ready.binarySearch(dependent) + 1), dependent) }
    }
    require(order.size == elements.size) {
        "These wait on each other, so they have no order: " +
            cycle(before, waiting).zipWithNext { waits, on -> "${elements[waits].name()} waits on ${elements[on].name()}" }.joinToString(", ")
    }
    return order
}

private fun cycle(before: List<Set<Int>>, waiting: IntArray): List<Int> {
    val stuck = waiting.indices.filter { waiting[it] > 0 }.toSet()
    val path = mutableListOf<Int>()
    val position = HashMap<Int, Int>()
    var at = stuck.first()
    while (at !in position) {
        position[at] = path.size
        path += at
        at = before[at].first { it in stuck }
    }
    return path.subList(position.getValue(at), path.size) + at
}

private fun Visitable.name(): String = (this as? Unique)?.label?.takeIf { it.isNotBlank() }?.let { "'$it'" } ?: toString()

private fun PortCapability.ports(): Sequence<Visitable> =
    providerPorts.flattenedValues().asSequence() + consumerPorts.flattenedValues().asSequence()

private class DepthFirst(
    private val root: Visitable,
    private val relation: Relation,
    private val enter: (Visitable) -> Boolean,
) : AbstractIterator<Step>() {
    private class Frame(val step: Step.Enter, val next: Iterator<Visitable>)

    private val seen = hashSetOf(root)
    private val frames = ArrayDeque<Frame>()
    private var opening: Step.Enter? = null
    private var started = false

    override fun computeNext() {
        if (!started) {
            started = true
            return emit(Step.Enter(root, null, 0))
        }
        opening?.let { step ->
            opening = null
            if (!enter(step.element)) return setNext(step.leave())
            frames.addLast(Frame(step, relation.next(step.element).iterator()))
        }
        val frame = frames.lastOrNull() ?: return done()
        while (frame.next.hasNext()) {
            val element = frame.next.next()
            if (seen.add(element)) return emit(Step.Enter(element, frame.step.element, frame.step.depth + 1))
        }
        frames.removeLast()
        setNext(frame.step.leave())
    }

    private fun emit(step: Step.Enter) {
        opening = step
        setNext(step)
    }

    private fun Step.Enter.leave() = Step.Leave(element, parent, depth)
}
