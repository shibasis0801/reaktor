package dev.shibasis.reaktor.work.definition

import dev.shibasis.reaktor.auth.kernel.AuthRequirement
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.graph.Workflow
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.step.Condition
import dev.shibasis.reaktor.graph.step.Output
import dev.shibasis.reaktor.graph.step.StepState
import kotlin.time.Duration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

sealed interface Spawns {
    val target: Durable<*, *>
}

class SignalNode<T : Any> internal constructor(
    graph: Graph,
    workflow: Workflow,
    name: String,
    serializer: KSerializer<T>,
    val timeout: Duration?,
    val sender: AuthRequirement?,
    override val on: Condition,
) : Output<T>(graph, workflow, name, serializer) {
    override val kind get() = NodeKind.Schedule
    override val role get() = "signal"
    override val endings get() = setOf(StepState.Succeeded, StepState.TimedOut, StepState.Skipped)
    override val details get() = listOfNotNull(timeout?.let { "timeout" to it.toString() }, sender?.let { "sender" to "authorized" }).toMap()
}

class TimerNode internal constructor(
    graph: Graph,
    workflow: Workflow,
    name: String,
    val after: Duration,
    override val on: Condition,
) : Output<Unit>(graph, workflow, name, Unit.serializer()) {
    init { require(!after.isNegative() && after.isFinite()) { "Timer '$name' needs a finite delay" } }

    override val kind get() = NodeKind.Schedule
    override val role get() = "timer"
    override val endings get() = setOf(StepState.Succeeded, StepState.Skipped)
    override val details get() = mapOf("after" to after.toString())
}

class CallNode<I : Any, O : Any> internal constructor(
    graph: Graph,
    workflow: Workflow,
    name: String,
    override val target: Durable<I, O>,
    val input: Output<I>,
    override val on: Condition,
) : Output<O>(graph, workflow, name, target.output.serializer), Spawns {
    override val inputs get() = listOf(input)
    override val child get() = target.workflowId
    override val kind get() = NodeKind.Workflow
    override val role get() = "call"
    override val details get() = mapOf("workflow" to target.workflowId)
}

class EachNode<I : Any, O : Any> internal constructor(
    graph: Graph,
    workflow: Workflow,
    name: String,
    override val target: Durable<I, O>,
    val items: Output<List<I>>,
    override val on: Condition,
) : Output<List<O>>(graph, workflow, name, ListSerializer(target.output.serializer)), Spawns {
    override val inputs get() = listOf(items)
    override val child get() = target.workflowId
    override val kind get() = NodeKind.Workflow
    override val role get() = "each"
    override val details get() = mapOf("workflow" to target.workflowId)
}

class LoopNode<I : Any, O : Any> internal constructor(
    graph: Graph,
    workflow: Workflow,
    name: String,
    override val target: Durable<I, O>,
    val seed: Output<I>,
    val limit: Int,
    internal val next: (O) -> I?,
    override val on: Condition,
) : Output<O>(graph, workflow, name, target.output.serializer), Spawns {
    init { require(limit in 1..MaxFanOut) { "Loop '$name' needs a limit between 1 and $MaxFanOut" } }

    override val inputs get() = listOf(seed)
    override val child get() = target.workflowId
    override val kind get() = NodeKind.Workflow
    override val role get() = "loop"
    override val details get() = mapOf("workflow" to target.workflowId, "limit" to limit.toString())

    internal fun following(result: String): String? =
        next(json.decodeFromString(target.output.serializer, result))?.let { json.encodeToString(target.input.serializer, it) }
}

internal const val MaxFanOut = 256
