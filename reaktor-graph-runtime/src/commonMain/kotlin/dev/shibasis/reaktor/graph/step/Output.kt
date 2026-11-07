package dev.shibasis.reaktor.graph.step

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.graph.NodeIdentity
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
import kotlinx.serialization.KSerializer
import kotlin.time.Duration

abstract class Output<T : Any>(
    graph: Graph,
    workflow: Workflow,
    val name: String,
    internal val format: KSerializer<T>?,
    val typeName: String = checkNotNull(format) { "Node '$name' needs a type name when its values cannot be stored" }.descriptor.serialName,
) : BasicNode(graph), ShapeAttributes {
    init {
        require(graph === workflow.childGraph) { "Node '$name' must live in the graph of workflow '${workflow.workflowId}'" }
        require(name.isNotBlank() && name.none { it == '/' || it == '#' }) { "Node name '$name' may not contain '/' or '#'" }
    }

    val workflowId: String = workflow.workflowId
    override val label: String get() = name
    val storable: Boolean get() = format != null
    val serializer: KSerializer<T>
        get() = format ?: error("'$name' in workflow '$workflowId' holds a $typeName, which cannot be stored, so the workflow can only run inline")
    internal val provider: ProviderPort<Output<*>> = registerProvider(Key("value"), Type(typeName), this)
    open val inputs: List<Output<*>> get() = emptyList()
    open val on: Condition get() = Condition.Always
    open val child: String? get() = null
    open val endings: Set<StepState> get() = setOf(StepState.Succeeded, StepState.Failed, StepState.Skipped)
    abstract val role: String
    open val details: Map<String, String> get() = emptyMap()

    val succeeded: Condition get() = Ended(this, StepState.Succeeded, "succeeded")
    val failed: Condition get() = Ended(this, StepState.Failed, "failed")
    val skipped: Condition get() = Ended(this, StepState.Skipped, "skipped")
    val timedOut: Condition get() = Ended(this, StepState.TimedOut, "timed out")

    fun matches(description: String, predicate: (T) -> Boolean): Condition = Matches(this, description, predicate)

    override fun condition(port: Key): String? =
        port.key.takeIf { it.startsWith(ConditionPort) }?.removePrefix(ConditionPort)?.let { source -> on.nodes.firstOrNull { it.name == source } }?.let(on::about)

    override val shapeAttributes: Map<String, String>
        get() = details + ("work" to role) + listOfNotNull(on.takeIf { it != Condition.Always }?.let { "on" to it.text })

    fun structure(): String = listOf(
        role, name, typeName, format?.descriptor?.fingerprint().orEmpty(),
        inputs.joinToString(",") { it.name }, on.text, child.orEmpty(),
    ).joinToString("\u0000")

    internal fun wire() {
        val references = inputs + on.nodes
        require(references.all { it.graph === graph && it !== this }) {
            "Node '$name' of workflow '$workflowId' may only depend on other nodes of its own workflow"
        }
        inputs.distinct().forEach { link(Key(it.name), it) }
        on.nodes.distinct().filter { it !in inputs }.forEach { link(Key(ConditionPort + it.name), it) }
    }

    private fun link(key: Key, source: Output<*>) {
        connect(registerConsumer<Output<*>>(key, source.provider.type), source.provider).getOrThrow()
    }

    private companion object {
        const val ConditionPort = "on:"
    }
}

class InputNode<T : Any> internal constructor(
    graph: Graph,
    workflow: Workflow,
    name: String,
    format: KSerializer<T>?,
    typeName: String,
) : Output<T>(graph, workflow, name, format, typeName) {
    override val kind get() = NodeKind.Node
    override val role get() = "input"
    override val endings get() = setOf(StepState.Succeeded)
}

class StepNode<T : Any> internal constructor(
    graph: Graph,
    workflow: Workflow,
    name: String,
    format: KSerializer<T>?,
    typeName: String,
    override val inputs: List<Output<*>>,
    override val on: Condition,
    val effect: Effect,
    val timeout: Duration,
    private val attributes: Map<String, String>,
    val body: suspend StepScope.(List<Any?>) -> T?,
    val reconcile: (suspend StepScope.(List<Any?>) -> T?)?,
    val undo: (suspend StepScope.(T) -> Unit)?,
) : Output<T>(graph, workflow, name, format, typeName) {
    init { require(timeout.isPositive() && timeout.isFinite()) { "Step '$name' needs a positive, finite timeout" } }

    internal val identity = NodeIdentity(this, provider.type)

    override val kind get() = NodeKind.Interactor
    override val role get() = "step"
    override val endings get() = setOf(StepState.Succeeded, StepState.Failed, StepState.TimedOut, StepState.Skipped)
    override val details get() = attributes + mapOf("effect" to effect.name, "timeout" to timeout.toString())

    suspend fun reverse(scope: StepScope, result: String) {
        undo?.invoke(scope, json.decodeFromString(serializer, result))
    }
}

class EitherNode<T : Any> internal constructor(
    graph: Graph,
    workflow: Workflow,
    name: String,
    val options: List<Output<T>>,
    override val on: Condition,
) : Output<T>(graph, workflow, name, options.first().format, options.first().typeName) {
    init {
        require(options.size >= 2 && options.distinct().size == options.size) { "Either '$name' needs two or more distinct options" }
        require(options.all { it.typeName == typeName }) { "The options of either '$name' must share one type" }
    }

    override val inputs get() = options
    override val kind get() = NodeKind.Node
    override val role get() = "either"
    override val endings get() = setOf(StepState.Succeeded, StepState.Skipped)
}
