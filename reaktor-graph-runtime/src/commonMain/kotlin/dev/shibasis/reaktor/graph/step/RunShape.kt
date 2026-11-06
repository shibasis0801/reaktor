package dev.shibasis.reaktor.graph.step

import dev.shibasis.reaktor.graph.core.GraphShape
import kotlinx.serialization.Serializable

@Serializable
data class StepShape(
    val state: StepState,
    val attempt: Int = 0,
    val startedAtMillis: Long? = null,
    val endedAtMillis: Long? = null,
    val dueAtMillis: Long? = null,
    val reason: String? = null,
)

@Serializable
data class RunShape(val id: String, val workflow: String, val state: String, val steps: Map<String, StepShape>) {
    val subject: String? get() = Attention.firstNotNullOfOrNull { wanted -> steps.entries.firstOrNull { it.value.state == wanted }?.key }

    private companion object {
        val Attention = listOf(StepState.Running, StepState.Waiting, StepState.Undoing, StepState.Failed, StepState.TimedOut)
    }
}

fun GraphShape.run(id: String, workflow: String, state: String, steps: Map<String, StepShape>): RunShape {
    val named = stepsOf(workflow)
    return RunShape(id, workflow, state, steps.mapNotNull { (name, step) -> named[name]?.let { it to step } }.toMap())
}

private fun GraphShape.stepsOf(workflow: String): Map<String, String> {
    val scope = nodes.firstOrNull { it.attributes["workflow"] == workflow }?.let { owner -> scopes.firstOrNull { it.container == owner.id }?.id }
        ?: scopes.singleOrNull()?.id?.takeIf { only -> nodes.all { it.scope == only && WorkAttribute in it.attributes } }
    return nodes.filter { it.scope == scope && WorkAttribute in it.attributes }.associate { it.label to it.id }
}

private const val WorkAttribute = "work"
