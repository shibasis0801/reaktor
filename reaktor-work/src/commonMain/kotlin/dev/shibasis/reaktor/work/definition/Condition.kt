package dev.shibasis.reaktor.work.definition

import dev.shibasis.reaktor.work.run.StepState

sealed class Condition {
    abstract val text: String
    internal abstract val nodes: List<Output<*>>
    internal open val handles: List<Output<*>> get() = emptyList()
    internal abstract fun evaluate(outcomes: Outcomes): Boolean?

    infix fun and(other: Condition): Condition = AllOf(this, other)
    infix fun or(other: Condition): Condition = AnyOf(this, other)
    operator fun not(): Condition = Not(this)

    data object Always : Condition() {
        override val text = "always"
        override val nodes: List<Output<*>> get() = emptyList()
        override fun evaluate(outcomes: Outcomes) = true
    }
}

internal interface Outcomes {
    fun state(node: Output<*>): StepState
    fun <T : Any> value(node: Output<T>): T?
}

internal class Ended(private val node: Output<*>, private val state: StepState, private val word: String) : Condition() {
    override val text get() = "${node.name} $word"
    override val nodes get() = listOf(node)
    override val handles get() = if (state == StepState.Failed || state == StepState.TimedOut) listOf(node) else emptyList()
    override fun evaluate(outcomes: Outcomes): Boolean? = outcomes.state(node).let { if (it.settled) it == state else null }
}

internal class Matches<T : Any>(private val node: Output<T>, private val description: String, private val predicate: (T) -> Boolean) : Condition() {
    override val text get() = "${node.name} matches $description"
    override val nodes get() = listOf(node)
    override fun evaluate(outcomes: Outcomes): Boolean? {
        val state = outcomes.state(node)
        if (!state.settled) return null
        return state == StepState.Succeeded && outcomes.value(node)?.let(predicate) == true
    }
}

internal class AllOf(private val first: Condition, private val second: Condition) : Condition() {
    override val text get() = "(${first.text}) and (${second.text})"
    override val nodes get() = first.nodes + second.nodes
    override val handles get() = first.handles + second.handles
    override fun evaluate(outcomes: Outcomes): Boolean? {
        val left = first.evaluate(outcomes)
        val right = second.evaluate(outcomes)
        return when {
            left == false || right == false -> false
            left == true && right == true -> true
            else -> null
        }
    }
}

internal class AnyOf(private val first: Condition, private val second: Condition) : Condition() {
    override val text get() = "(${first.text}) or (${second.text})"
    override val nodes get() = first.nodes + second.nodes
    override val handles get() = first.handles + second.handles
    override fun evaluate(outcomes: Outcomes): Boolean? {
        val left = first.evaluate(outcomes)
        val right = second.evaluate(outcomes)
        return when {
            left == true || right == true -> true
            left == false && right == false -> false
            else -> null
        }
    }
}

internal class Not(private val condition: Condition) : Condition() {
    override val text get() = "not (${condition.text})"
    override val nodes get() = condition.nodes
    override fun evaluate(outcomes: Outcomes): Boolean? = condition.evaluate(outcomes)?.not()
}
