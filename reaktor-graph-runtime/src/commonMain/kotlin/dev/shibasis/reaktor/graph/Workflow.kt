package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.Graph as childGraph
import dev.shibasis.reaktor.graph.core.GraphOwner
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.ShapeAttributes
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.Type
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.contract.CallAttributes
import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.activity
import kotlinx.coroutines.withContext

class Activity<In, Out> internal constructor(
    private val port: ConsumerPort<suspend (In) -> Out>,
    internal val provider: ProviderPort<suspend (In) -> Out>,
) {
    private val wire = WireIdentity(port)

    suspend operator fun invoke(request: In): Out = port.suspended {
        val execute = port.impl ?: error("Activity '${port.key.key}' is not wired")
        withContext(CallAttributes(mapOf(ServiceCall.EdgeAttribute to wire()))) { execute(request) }
    }
}

class ActivityNode<In, Out>(
    graph: Graph,
    val activityId: String,
    override val label: String,
    val effect: Effect,
    private val attributes: Map<String, String> = emptyMap(),
    execute: suspend (In) -> Out,
) : BasicNode(graph), ShapeAttributes {
    override val kind = NodeKind.Interactor
    private val type = Type("activity:$activityId")
    private val identity = NodeIdentity(this, type)
    val run = registerProvider<suspend (In) -> Out>(Key("run"), type) { request ->
        activity(activityId.substringBeforeLast('/'), activityId.substringAfterLast('/'), request, effect,
            identity() + (ServiceCall.ActivationAttribute to rootOf(graph).id.toString()), graph.serviceInterceptors(), execute)
    }
    override val shapeAttributes get() = attributes + mapOf("activity" to activityId, "effect" to effect.name)
}

open class Workflow(graph: Graph, val workflowId: String, override val label: String,
    override val kind: NodeKind = NodeKind.Workflow, private val attributes: Map<String, String> = emptyMap(),
) : BasicNode(graph), ShapeAttributes, GraphOwner {
    override val childGraph = graph.childGraph { Graph(parentGraph = it, label = label) }
    override val shapeAttributes get() = attributes + mapOf("workflow" to workflowId)
    private val names = mutableSetOf<String>()

    fun <In, Out> activity(name: String, effect: Effect, attributes: Map<String, String> = emptyMap(), execute: suspend (In) -> Out): Activity<In, Out> {
        require(name.isNotBlank() && names.add(name)) { "Duplicate or empty activity '$workflowId/$name'" }
        val node = childGraph.Node { ActivityNode(it, "$workflowId/$name", name, effect, attributes, execute) }
        val consumer = registerConsumer<suspend (In) -> Out>(Key(name), node.run.type)
        connect(consumer, node.run).getOrThrow()
        return Activity(consumer, node.run)
    }

    fun <In, Out> call(name: String, activity: Activity<In, Out>): Activity<In, Out> {
        require(name.isNotBlank() && names.add(name)) { "Duplicate or empty activity '$workflowId/$name'" }
        val consumer = registerConsumer<suspend (In) -> Out>(Key(name), activity.provider.type)
        connect(consumer, activity.provider).getOrThrow()
        return Activity(consumer, activity.provider)
    }
}
