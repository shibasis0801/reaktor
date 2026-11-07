package dev.shibasis.reaktor.graph.step

import dev.shibasis.reaktor.graph.Workflow
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.portgraph.traversal.Relation
import dev.shibasis.reaktor.portgraph.traversal.Visitable
import dev.shibasis.reaktor.portgraph.traversal.topological

object Ready : Relation {
    override fun next(element: Visitable): Sequence<Visitable> = (element as? Output<*>)?.waitsOn?.asSequence() ?: emptySequence()
}

val Output<*>.waitsOn: List<Output<*>> get() = (inputs + on.nodes).distinct()

fun Workflow.steps(): List<Output<*>> = stepsOf(childGraph)

internal fun stepsOf(graph: Graph): List<Output<*>> = graph.nodes.filterIsInstance<Output<*>>().topological(Ready)

fun Output<*>.readiness(outcomes: Outcomes): Boolean? = readiness(outcomes::state) { (it as Matches<*>).holds(outcomes) }

internal fun Output<*>.readiness(state: (Output<*>) -> StepState, verdict: (Condition) -> Boolean): Boolean? {
    val condition = on.decide(state, verdict)
    val inputs = if (this is EitherNode<*>) oneSucceeded(options, state) else allSucceeded(inputs, state)
    return when {
        condition == false || inputs == false -> false
        condition == true && inputs == true -> true
        else -> null
    }
}

private fun allSucceeded(inputs: List<Output<*>>, state: (Output<*>) -> StepState): Boolean? = when {
    inputs.any { state(it).settled && state(it) != StepState.Succeeded } -> false
    inputs.all { state(it) == StepState.Succeeded } -> true
    else -> null
}

private fun oneSucceeded(options: List<Output<*>>, state: (Output<*>) -> StepState): Boolean? =
    if (options.any { !state(it).settled }) null else options.any { state(it) == StepState.Succeeded }
