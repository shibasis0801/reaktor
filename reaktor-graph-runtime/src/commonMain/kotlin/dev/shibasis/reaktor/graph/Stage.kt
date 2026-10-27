package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.service.contract.Effect

data class StageInvocation<In, Out>(val input: In, val execute: suspend (In) -> Out)

class Stage<In, Out> internal constructor(private val activity: Activity<StageInvocation<In, Out>, Out>) {
    suspend operator fun invoke(input: In, execute: suspend (In) -> Out): Out = activity(StageInvocation(input, execute))
}

fun <In, Out> Workflow.stage(name: String, effect: Effect, attributes: Map<String, String> = emptyMap()): Stage<In, Out> =
    Stage(activity<StageInvocation<In, Out>, Out>(name, effect, attributes) { it.execute(it.input) })
