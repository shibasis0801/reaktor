package dev.shibasis.reaktor.graph.step

sealed class Condition {
    abstract val text: String
    abstract val nodes: List<Output<*>>
    open val handles: List<Output<*>> get() = emptyList()
    internal open val choices: List<Condition> get() = emptyList()

    internal abstract fun decide(state: (Output<*>) -> StepState, verdict: (Condition) -> Boolean): Boolean?
    internal abstract fun about(node: Output<*>): String?

    fun evaluate(outcomes: Outcomes): Boolean? = decide(outcomes::state) { (it as Matches<*>).holds(outcomes) }

    infix fun and(other: Condition): Condition = AllOf(this, other)
    infix fun or(other: Condition): Condition = AnyOf(this, other)
    operator fun not(): Condition = Not(this)

    data object Always : Condition() {
        override val text = "always"
        override val nodes: List<Output<*>> get() = emptyList()
        override fun decide(state: (Output<*>) -> StepState, verdict: (Condition) -> Boolean) = true
        override fun about(node: Output<*>): String? = null
    }
}

interface Outcomes {
    fun state(node: Output<*>): StepState
    fun <T : Any> value(node: Output<T>): T?
}

internal class Ended(private val node: Output<*>, private val state: StepState, private val word: String) : Condition() {
    override val text get() = "${node.name} $word"
    override val nodes get() = listOf(node)
    override val handles get() = if (state == StepState.Failed || state == StepState.TimedOut) listOf(node) else emptyList()
    override fun decide(state: (Output<*>) -> StepState, verdict: (Condition) -> Boolean): Boolean? =
        state(node).let { if (it.settled) it == this.state else null }
    override fun about(node: Output<*>) = word.takeIf { node === this.node }
}

internal class Matches<T : Any>(private val node: Output<T>, private val description: String, private val predicate: (T) -> Boolean) : Condition() {
    override val text get() = "${node.name} matches $description"
    override val nodes get() = listOf(node)
    override val choices get() = listOf(this)
    override fun decide(state: (Output<*>) -> StepState, verdict: (Condition) -> Boolean): Boolean? {
        val ended = state(node)
        if (!ended.settled) return null
        return ended == StepState.Succeeded && verdict(this)
    }
    override fun about(node: Output<*>) = "matches $description".takeIf { node === this.node }

    fun holds(outcomes: Outcomes): Boolean = outcomes.value(node)?.let(predicate) == true
}

internal class AllOf(private val first: Condition, private val second: Condition) : Condition() {
    override val text get() = "(${first.text}) and (${second.text})"
    override val nodes get() = first.nodes + second.nodes
    override val handles get() = first.handles + second.handles
    override val choices get() = first.choices + second.choices
    override fun decide(state: (Output<*>) -> StepState, verdict: (Condition) -> Boolean): Boolean? {
        val left = first.decide(state, verdict)
        val right = second.decide(state, verdict)
        return when {
            left == false || right == false -> false
            left == true && right == true -> true
            else -> null
        }
    }
    override fun about(node: Output<*>) = listOfNotNull(first.about(node), second.about(node)).joinToString(" and ").ifEmpty { null }
}

internal class AnyOf(private val first: Condition, private val second: Condition) : Condition() {
    override val text get() = "(${first.text}) or (${second.text})"
    override val nodes get() = first.nodes + second.nodes
    override val handles get() = first.handles + second.handles
    override val choices get() = first.choices + second.choices
    override fun decide(state: (Output<*>) -> StepState, verdict: (Condition) -> Boolean): Boolean? {
        val left = first.decide(state, verdict)
        val right = second.decide(state, verdict)
        return when {
            left == true || right == true -> true
            left == false && right == false -> false
            else -> null
        }
    }
    override fun about(node: Output<*>) = listOfNotNull(first.about(node), second.about(node)).joinToString(" or ").ifEmpty { null }
}

internal class Not(private val condition: Condition) : Condition() {
    override val text get() = "not (${condition.text})"
    override val nodes get() = condition.nodes
    override val choices get() = condition.choices
    override fun decide(state: (Output<*>) -> StepState, verdict: (Condition) -> Boolean): Boolean? =
        condition.decide(state, verdict)?.not()
    override fun about(node: Output<*>) = condition.about(node)?.let { "not $it" }
}
