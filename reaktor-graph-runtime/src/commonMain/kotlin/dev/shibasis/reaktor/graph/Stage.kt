package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.PortCapability
import dev.shibasis.reaktor.portgraph.port.PortDelegate
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.service.contract.Effect
import kotlin.properties.PropertyDelegateProvider

data class StageInvocation<In, Out>(val input: In, val execute: suspend (In) -> Out)

class Stage<In, Out> internal constructor(internal val activity: Activity<StageInvocation<In, Out>, Out>) {
    suspend operator fun invoke(input: In, execute: suspend (In) -> Out): Out = activity(StageInvocation(input, execute))
}

fun <In, Out> Workflow.stage(name: String, effect: Effect, attributes: Map<String, String> = emptyMap()): Stage<In, Out> =
    Stage(activity<StageInvocation<In, Out>, Out>(name, effect, attributes) { it.execute(it.input) })

fun <In, Out> Workflow.call(name: String, stage: Stage<In, Out>): Stage<In, Out> = Stage(call(name, stage.activity))

fun <In, Out> PortCapability.consumes(stage: Stage<In, Out>) =
    PropertyDelegateProvider<PortCapability, PortDelegate<Stage<In, Out>>> { node, property ->
        val provider = stage.activity.provider
        val consumer = node.registerConsumer<suspend (StageInvocation<In, Out>) -> Out>(Key(property.name), provider.type)
        connect(consumer, provider).getOrThrow()
        val port = Stage(Activity(consumer, provider))
        PortDelegate { _, _ -> port }
    }
