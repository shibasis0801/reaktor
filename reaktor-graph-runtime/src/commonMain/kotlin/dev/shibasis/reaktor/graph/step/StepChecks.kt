package dev.shibasis.reaktor.graph.step

import dev.shibasis.reaktor.graph.Workflow

fun Workflow.problems(result: Output<*>? = null): List<String> {
    val nodes = steps()
    val succeeds = HashMap<Output<*>, Boolean>()
    val problems = mutableListOf<String>()
    for (node in nodes) {
        val endings = { it: Output<*> -> if (succeeds[it] == true) it.endings else setOf(StepState.Skipped) }
        val reached = possible(node.waitsOn, node.on.choices, endings) { state, verdict -> node.readiness(state, verdict) == true }
        succeeds[node] = reached != false && StepState.Succeeded in node.endings
        when {
            reached != false -> Unit
            possible(node.on.nodes.distinct(), node.on.choices, { it.endings }) { state, verdict -> node.on.decide(state, verdict) == true } == false ->
                problems += "The condition of '${node.name}' in workflow '$workflowId' can never hold: ${node.on.text}"
            else -> problems += "No run of workflow '$workflowId' can reach '${node.name}', because what it waits on can never succeed"
        }
    }
    if (result != null) {
        val used = nodes.flatMapTo(HashSet()) { it.waitsOn }
        nodes.filter { it !== result && it !in used && it !is InputNode<*> }
            .forEach { problems += "Nothing in workflow '$workflowId' uses the result of '${it.name}'" }
    }
    return problems
}

fun Workflow.check(result: Output<*>? = null) {
    val problems = problems(result)
    require(problems.isEmpty()) { problems.joinToString("\n") }
}

private fun possible(
    nodes: List<Output<*>>,
    choices: List<Condition>,
    endings: (Output<*>) -> Set<StepState>,
    holds: ((Output<*>) -> StepState, (Condition) -> Boolean) -> Boolean,
): Boolean? {
    val options = nodes.map { endings(it).toList() }
    val combinations = options.fold(1L) { total, it -> total * it.size } shl choices.size
    if (combinations > MaxCombinations) return null
    val assigned = HashMap<Output<*>, StepState>()
    for (combination in 0 until combinations) {
        var rest = combination
        nodes.forEachIndexed { at, node -> options[at].let { assigned[node] = it[(rest % it.size).toInt()]; rest /= it.size } }
        val verdicts = choices.withIndex().associate { (at, choice) -> choice to (rest shr at and 1L == 1L) }
        if (holds({ assigned[it] ?: StepState.Pending }) { verdicts[it] == true }) return true
    }
    return false
}

private const val MaxCombinations = 1L shl 16
