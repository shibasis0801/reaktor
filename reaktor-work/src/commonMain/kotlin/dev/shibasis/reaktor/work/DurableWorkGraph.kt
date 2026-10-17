package dev.shibasis.reaktor.work

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.registerProvider
import dev.shibasis.reaktor.io.serialization.TextSerializer
import dev.shibasis.reaktor.service.schemaRef
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

@Serializable
data class WorkGraphRun(
    val id: String, val definition: String, val version: Int, val manifest: String,
    val input: String, val target: String, val state: WorkState = WorkState.RUNNING,
    val revision: Long = 0, val completed: Set<String> = emptySet(),
    val selectedBranches: Map<String, String> = emptyMap(), val reason: String? = null, val links: WorkLinks = WorkLinks(),
)

class GraphInputs internal constructor(private val values: Map<DurableGraphValue<*>, Any>) {
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(value: DurableGraphValue<T>): T = checkNotNull(values[value]) { "Undeclared or incomplete graph input" } as T
}

class DurableGraphValue<T : Any> internal constructor(
    internal val owner: Any, val id: String, internal val serializer: KSerializer<T>,
    internal val dependencies: List<DurableGraphValue<*>>, internal val port: ProviderPort<GraphWorkStep>?,
    internal val branch: Triple<DurableGraphValue<Boolean>, DurableGraphValue<T>, DurableGraphValue<T>>? = null,
)

interface GraphWorkStep {
    val definition: WorkDefinition<*>
    suspend fun admit(runtime: WorkRuntime, run: WorkGraphRun, values: GraphInputs, workId: String): WorkAdmission
}

/**
 * Bounded static durable plans. Node receipts live in WorkStore; this record retains plan identity,
 * input, control selection and result references. Recovery evaluates those explicit records.
 */
class DurableWorkGraph<I : Any>(
    graph: Graph, val definitionId: String, val version: Int,
    private val inputSerializer: KSerializer<I>, private val database: ObjectDatabase,
    private val runtime: WorkRuntime,
) : BasicNode(graph) {
    private val nodes = mutableListOf<DurableGraphValue<*>>()
    private var sealed = false
    private val storeName get() = "${runtime.scope.storeName}:graphs:$definitionId"
    init {
        require(definitionId.isNotBlank() && version > 0 && graph === runtime.graph)
        runtime.installReconciler(definitionId, WorkReconciler { reconcile() })
        runtime.registerProvider<WorkGraphInspectionSource>(definitionId, object : WorkGraphInspectionSource {
            override fun definition(): WorkDagDefinition? = if (nodes.isEmpty()) null else WorkDagDefinition.decodeManifest(definitionId, version, manifest())
            override suspend fun inspectRuns(): Pair<List<WorkGraphRun>, List<String>> {
                val runs = mutableListOf<WorkGraphRun>()
                val unreadable = mutableListOf<String>()
                val codec = database.objectSerializer as TextSerializer
                database.exportRaw(storeName).forEach { row ->
                    try {
                        val run = codec.deserialize(WorkGraphRun.serializer(), row.payload)
                        require(run.id == row.key && run.definition == definitionId)
                        runs += run
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { unreadable += "$definitionId/${row.key}" }
                }
                return runs to unreadable
            }
        })
    }

    fun <P : Any, T : Any> step(
        id: String, definition: WorkDefinition<P>, output: KSerializer<T>,
        dependencies: List<DurableGraphValue<*>> = emptyList(), payload: GraphInputs.(I) -> P,
    ): DurableGraphValue<T> {
        validateNew(id, dependencies)
        val typedDefinition = definition
        val operation = object : GraphWorkStep {
            override val definition: WorkDefinition<*> = typedDefinition
            override suspend fun admit(runtime: WorkRuntime, run: WorkGraphRun, values: GraphInputs, workId: String): WorkAdmission =
                runtime.enqueue(workId, typedDefinition, values.payload(json.decodeFromString(inputSerializer, run.input)), links = run.links)
        }
        runtime.install(definition)
        return DurableGraphValue(this, id, output, dependencies, registerProvider<GraphWorkStep>(id, operation)).also { nodes += it }
    }

    fun <T : Any> branch(
        id: String, condition: DurableGraphValue<Boolean>, yes: DurableGraphValue<T>, no: DurableGraphValue<T>,
    ): DurableGraphValue<T> {
        val dependencies = listOf(condition, yes, no)
        validateNew(id, dependencies)
        require(yes.serializer.schemaRef() == no.serializer.schemaRef())
        return DurableGraphValue(this, id, yes.serializer, dependencies, null, Triple(condition, yes, no)).also { nodes += it }
    }

    private fun validateNew(id: String, dependencies: List<DurableGraphValue<*>>) {
        require(!sealed && id.isNotBlank() && nodes.none { it.id == id } && nodes.size < 64)
        require(dependencies.all { it.owner === this && it in nodes })
    }

    private fun manifest(): String = json.encodeToString(ListSerializer(String.serializer()),
        listOf("$definitionId:$version:${inputSerializer.schemaRef().fingerprint}") + nodes.map { node ->
            val definition = node.port?.impl?.definition
            json.encodeToString(ListSerializer(String.serializer()), listOf(node.id, node.serializer.schemaRef().fingerprint,
                definition?.id ?: "branch", definition?.version?.toString() ?: "", definition?.payloadSchema ?: "") + node.dependencies.map { it.id })
        })

    suspend fun admit(id: String, input: I, target: DurableGraphValue<*>, links: WorkLinks = WorkLinks()): WorkGraphRun {
        require(id.isNotBlank() && target.owner === this)
        nodes.mapNotNull { it.port?.impl?.definition }.forEach { runtime.validateAdmission(it) }
        sealed = true
        val encoded = json.encodeToString(inputSerializer, input)
        require(encoded.encodeToByteArray().size <= 65_536)
        val run = WorkGraphRun(id, definitionId, version, manifest(), encoded, target.id, links = links)
        if (!database.compareAndSet(storeName, id, null, run, WorkGraphRun.serializer())) {
            val existing = checkNotNull(get(id))
            require(existing.manifest == run.manifest && existing.input == run.input && existing.target == run.target) { "Conflicting graph run admission" }
        }
        advance(id)
        return checkNotNull(get(id))
    }

    suspend fun get(id: String): WorkGraphRun? = database.get(storeName, id, WorkGraphRun::class, WorkGraphRun.serializer())?.value

    suspend fun reconcile() {
        sealed = true
        database.getAll(storeName, WorkGraphRun::class, WorkGraphRun.serializer()).map { it.value }
            .filter { it.state == WorkState.RUNNING }.forEach { advance(it.id) }
    }

    suspend fun advance(id: String) {
        repeat(16) {
            val run = get(id) ?: return
            if (run.state != WorkState.RUNNING) return
            val after = if (run.manifest != manifest()) run.copy(state = WorkState.QUARANTINED, reason = "Pinned graph plan requires explicit migration")
            else evaluate(run)
            if (after == run || database.compareAndSet(storeName, id, run, after.copy(revision = run.revision + 1), WorkGraphRun.serializer())) return
        }
    }

    private suspend fun evaluate(run: WorkGraphRun): WorkGraphRun {
        val completed = run.completed.toMutableSet()
        val branches = run.selectedBranches.toMutableMap()
        val memo = mutableMapOf<DurableGraphValue<*>, Any?>()
        var failure: Pair<WorkState, String>? = null
        suspend fun resolve(value: DurableGraphValue<*>): Any? {
            if (value in memo) return memo[value]
            val result: Any? = if (value.branch != null) {
                val (condition, yes, no) = value.branch
                val decision = resolve(condition) as? Boolean
                if (decision == null) null else {
                    val selected = if (decision) yes else no
                    branches[value.id] = selected.id
                    resolve(selected)
                }
            } else {
                val inputs = value.dependencies.associateWith { resolve(it) }
                if (inputs.values.any { it == null } || failure != null) null else {
                    val workId = json.encodeToString(ListSerializer(String.serializer()), listOf(definitionId, run.id, value.id))
                    val admission = try {
                        checkNotNull(value.port).suspended { admit(runtime, run, GraphInputs(inputs.mapValues { checkNotNull(it.value) }), workId) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (denied: IllegalArgumentException) { failure = WorkState.BLOCKED to "Graph admission denied or input invalid"; null }
                    if (admission is WorkAdmission.Conflict) failure = WorkState.QUARANTINED to "Conflicting node intent"
                    val record = runtime.store.get(runtime.scope, workId)
                    when (record?.state) {
                        WorkState.SUCCEEDED -> try { json.decodeFromString(value.serializer, checkNotNull(record.receipt)) }
                            catch (invalid: Exception) { failure = WorkState.QUARANTINED to "Node result schema invalid"; null }
                        WorkState.FAILED, WorkState.BLOCKED, WorkState.UNKNOWN, WorkState.QUARANTINED, WorkState.CANCELLED -> {
                            failure = record.state to "Node ${value.id}: ${record.reason ?: record.state.name}"; null
                        }
                        else -> null
                    }
                }
            }
            memo[value] = result
            if (result != null) completed += value.id
            return result
        }
        val target = nodes.single { it.id == run.target }
        val result = resolve(target)
        return run.copy(completed = completed, selectedBranches = branches,
            state = failure?.first ?: if (result != null) WorkState.SUCCEEDED else WorkState.RUNNING, reason = failure?.second)
    }
}
