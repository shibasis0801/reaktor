package dev.shibasis.reaktor.work.definition

import dev.shibasis.reaktor.auth.kernel.AuthRequirement
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.graph.Workflow
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.ShapeAttributes
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.Type
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.fingerprint
import dev.shibasis.reaktor.work.run.StepState
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration

sealed class Output<T : Any>(
    graph: Graph,
    workflow: Workflow,
    val name: String,
    val serializer: KSerializer<T>,
) : BasicNode(graph), ShapeAttributes {
    init {
        require(graph === workflow.childGraph) { "Node '$name' must live in the graph of workflow '${workflow.workflowId}'" }
        require(name.isNotBlank() && name.none { it == '/' || it == '#' }) { "Node name '$name' may not contain '/' or '#'" }
    }

    val workflowId: String = workflow.workflowId
    override val label: String get() = name
    internal val provider: ProviderPort<Output<*>> = registerProvider(Key("value"), Type(serializer.descriptor.serialName), this)
    internal open val inputs: List<Output<*>> get() = emptyList()
    internal open val on: Condition get() = Condition.Always
    internal open val target: Durable<*, *>? get() = null
    internal abstract val role: String
    internal open val details: Map<String, String> get() = emptyMap()

    val succeeded: Condition get() = Ended(this, StepState.Succeeded, "succeeded")
    val failed: Condition get() = Ended(this, StepState.Failed, "failed")
    val skipped: Condition get() = Ended(this, StepState.Skipped, "skipped")
    val timedOut: Condition get() = Ended(this, StepState.TimedOut, "timed out")

    fun matches(description: String, predicate: (T) -> Boolean): Condition = Matches(this, description, predicate)

    override val shapeAttributes: Map<String, String>
        get() = details + ("work" to role) + listOfNotNull(on.takeIf { it != Condition.Always }?.let { "on" to it.text })

    internal fun structure(): String = listOf(
        role, name, serializer.descriptor.serialName, serializer.descriptor.fingerprint(),
        inputs.joinToString(",") { it.name }, on.text, target?.workflowId.orEmpty(),
    ).joinToString("\u0000")

    internal fun wire() {
        val references = inputs + on.nodes
        require(references.all { it.graph === graph && it !== this }) {
            "Node '$name' of workflow '$workflowId' may only depend on other nodes of its own workflow"
        }
        inputs.distinct().forEach { link(Key(it.name), it) }
        on.nodes.distinct().filter { it !in inputs }.forEach { link(Key("on:${it.name}"), it) }
    }

    private fun link(key: Key, source: Output<*>) {
        connect(registerConsumer<Output<*>>(key, source.provider.type), source.provider).getOrThrow()
    }
}

class InputNode<T : Any> internal constructor(
    graph: Graph,
    workflow: Workflow,
    name: String,
    serializer: KSerializer<T>,
) : Output<T>(graph, workflow, name, serializer) {
    override val kind get() = NodeKind.Node
    override val role get() = "input"
}

class StepNode<T : Any> internal constructor(
    graph: Graph,
    workflow: Workflow,
    name: String,
    serializer: KSerializer<T>,
    override val inputs: List<Output<*>>,
    override val on: Condition,
    val effect: Effect,
    val timeout: Duration,
    internal val body: suspend StepScope.(List<Any?>) -> T,
    internal val reconcile: (suspend StepScope.(List<Any?>) -> T?)?,
    internal val undo: (suspend StepScope.(T) -> Unit)?,
) : Output<T>(graph, workflow, name, serializer) {
    init { require(timeout.isPositive() && timeout.isFinite()) { "Step '$name' needs a positive, finite timeout" } }

    override val kind get() = NodeKind.Interactor
    override val role get() = "step"
    override val details get() = mapOf("effect" to effect.name, "timeout" to timeout.toString())

    internal suspend fun reverse(scope: StepScope, result: String) {
        undo?.invoke(scope, json.decodeFromString(serializer, result))
    }
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
    override val details get() = mapOf("after" to after.toString())
}

class CallNode<I : Any, O : Any> internal constructor(
    graph: Graph,
    workflow: Workflow,
    name: String,
    override val target: Durable<I, O>,
    val input: Output<I>,
    override val on: Condition,
) : Output<O>(graph, workflow, name, target.output.serializer) {
    override val inputs get() = listOf(input)
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
) : Output<List<O>>(graph, workflow, name, ListSerializer(target.output.serializer)) {
    override val inputs get() = listOf(items)
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
) : Output<O>(graph, workflow, name, target.output.serializer) {
    init { require(limit in 1..MaxFanOut) { "Loop '$name' needs a limit between 1 and $MaxFanOut" } }

    override val inputs get() = listOf(seed)
    override val kind get() = NodeKind.Workflow
    override val role get() = "loop"
    override val details get() = mapOf("workflow" to target.workflowId, "limit" to limit.toString())

    internal fun following(result: String): String? =
        next(json.decodeFromString(target.output.serializer, result))?.let { json.encodeToString(target.input.serializer, it) }
}

class EitherNode<T : Any> internal constructor(
    graph: Graph,
    workflow: Workflow,
    name: String,
    val options: List<Output<T>>,
    override val on: Condition,
) : Output<T>(graph, workflow, name, options.first().serializer) {
    init {
        require(options.size >= 2 && options.distinct().size == options.size) { "Either '$name' needs two or more distinct options" }
        require(options.all { it.serializer.descriptor == serializer.descriptor }) { "The options of either '$name' must share one type" }
    }

    override val inputs get() = options
    override val kind get() = NodeKind.Node
    override val role get() = "either"
}

internal const val MaxFanOut = 256
