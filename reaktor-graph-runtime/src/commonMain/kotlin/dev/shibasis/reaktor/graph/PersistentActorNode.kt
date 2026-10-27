package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.ShapeAttributes
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.Type
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.decision

interface ActorStateStore<In, State> {
    suspend fun <Out> transact(input: In, execute: suspend (State) -> Out): Out
}

data class ActorTransition(val message: String, val from: Set<String>, val to: Set<String>)

class PersistentActorNode<In, State>(
    graph: Graph,
    id: String,
    label: String,
    storeName: String,
    store: ActorStateStore<In, State>,
    private val states: Set<String>,
    private val transitions: List<ActorTransition>,
    private val stateName: (State) -> String,
) : Workflow(graph, id, label, NodeKind.Actor, mapOf(
    "actor" to id, "stateStore" to storeName, "states" to states.sorted().joinToString(","),
    "transitions" to transitions.joinToString(";") { "${it.message}:${it.from.sorted().joinToString(",")}->${it.to.sorted().joinToString(",")}" },
)) {
    private val storeType = Type("actor-state:$id")
    private val provider = childGraph.Node {
        object : BasicNode(it), ShapeAttributes {
            override val label = storeName
            override val kind = NodeKind.Store
            override val shapeAttributes = mapOf("actorState" to id)
            val access = registerProvider<ActorStateStore<In, State>>(Key("state"), storeType, store)
        }
    }
    private val state = registerConsumer<ActorStateStore<In, State>>(Key("state"), storeType)

    init {
        require(states.isNotEmpty() && transitions.map { it.message }.distinct().size == transitions.size)
        require(transitions.all { it.from.isNotEmpty() && it.to.isNotEmpty() && states.containsAll(it.from + it.to) })
        connect(state, provider.access).getOrThrow()
    }

    fun <Out> transition(message: String, effect: Effect, execute: suspend (In, State) -> Out): Activity<In, Out> {
        val transition = requireNotNull(transitions.singleOrNull { it.message == message }) { "Unknown actor message $message" }
        return activity(message, effect) { input -> state.suspended {
            transact(input) { value ->
                val before = stateName(value)
                require(before in transition.from) { "Actor $workflowId cannot handle $message in $before" }
                decision("state-before", before)
                try {
                    val result = execute(input, value)
                    val after = stateName(value)
                    require(after in transition.to) { "Actor $workflowId cannot enter $after from $before" }
                    decision("state-after", after)
                    decision("supervision", "completed")
                    result
                } catch (failure: Throwable) {
                    decision("supervision", "failed")
                    throw failure
                }
            }
        } }
    }
}
