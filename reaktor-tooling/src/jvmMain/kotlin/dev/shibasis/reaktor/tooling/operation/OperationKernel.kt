package dev.shibasis.reaktor.tooling.operation

import dev.shibasis.reaktor.tooling.operation.OperationGate.Entry
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.ContractRole
import dev.shibasis.reaktor.graph.contracts
import dev.shibasis.reaktor.graph.consumes
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.service.contract.Call
import dev.shibasis.reaktor.service.contract.CallAttributes
import dev.shibasis.reaktor.service.contract.CallFailure
import dev.shibasis.reaktor.service.contract.Rejected
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.mcp.ToolDefinition
import dev.shibasis.reaktor.mcp.ToolNode
import dev.shibasis.reaktor.tooling.*
import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.mcp.door.CallLog
import dev.shibasis.reaktor.tooling.mcp.door.DoorApproval
import dev.shibasis.reaktor.tooling.mcp.door.DoorApprovals
import dev.shibasis.reaktor.tooling.mcp.door.DoorDecision
import dev.shibasis.reaktor.tooling.mcp.door.DoorPolicy
import dev.shibasis.reaktor.telemetry.export.ClientOperations
import io.opentelemetry.kotlin.ExperimentalApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

open class OperationNode(graph: Graph, val provider: String, private val declared: List<Operation<*, *>> = emptyList()) : BasicNode(graph) {
    open val operations: List<Operation<*, *>> get() = declared
    init { require(declared.distinctBy { it.id }.size == declared.size) { "Duplicate operation ids from $provider" } }
}

class OperationAuthority(
    val policy: DoorPolicy = DoorPolicy(),
    val approvals: DoorApprovals? = null,
    val log: CallLog? = null,
    val onApprovalNeeded: (DoorApproval) -> Unit = {},
    val model: OperationModel? = null,
    val runs: Pair<WorkspaceId, RunEventSink>? = null,
    val telemetry: ClientOperations? = null,
)

data class ModelVerdict(val allowed: Boolean, val enforced: Boolean, val rule: String, val record: JsonObject)

fun interface OperationModel {
    fun decide(caller: CallCaller, provider: String, operation: OperationId): ModelVerdict?
}

@OptIn(ExperimentalApi::class)
class OperationKernel(
    private val graph: Graph,
    private val authority: OperationAuthority,
    private val json: Json = OperationJson,
    private val preferred: Map<OperationId, OperationId> = emptyMap(),
) {
    val gate = OperationGate(authority, json) { id -> legacyEntries().firstOrNull { it.catalog.id == id } }

    private val own = listOf(
        operation<CatalogQuery, List<CatalogEntry>>("operation.catalog", "Lists the operations this workspace offers, with their effect, the preferred ones first; " +
            "an entry with prefer is kept for older callers and the operation it names does the same work. Describe one before calling it with unfamiliar arguments.") { query ->
            catalog().filter { query.domain == null || it.id.domain == query.domain }
                .filter { entry -> query.query.isNullOrBlank() || query.query.lowercase().split(' ').all { word -> word in entry.id.value || word in entry.summary.lowercase() } }
        },
        operation<DescribeRequest, OperationDescriptor>("operation.describe", "One operation's input and output schema and its effect.") { request ->
            describe(request.operation) ?: error("Unknown operation ${request.operation}")
        },
    ).map { it.entry("kernel", graph.coroutineScope) }

    @Suppress("UNCHECKED_CAST")
    fun connectContracts() {
        val connected = graph.nodes.filterIsInstance<ContractCall>().mapTo(hashSetOf()) { it.call.call.id }
        graph.contracts().filter { it.role == ContractRole.Server && it.coroutineScope.isActive }.forEach { node ->
            node.api.operations.filterIsInstance<Call<*, *>>().filterNot { it.id in connected }.forEach { operation ->
                val client = ContractCall(graph, operation as Call<Any?, Any?>)
                graph.attach(client)
                connect(client, node)
            }
        }
    }

    fun catalog(): List<CatalogEntry> {
        val listed = entries().map { it.catalog }
        val present = listed.mapTo(hashSetOf()) { it.id }
        return listed.map { entry -> entry.copy(prefer = preferred[entry.id]?.takeIf { it in present }) }.sortedWith(compareBy({ it.prefer != null }, { it.id.value }))
    }

    fun describe(id: OperationId): OperationDescriptor? = entries().firstOrNull { it.catalog.id == id }?.describe?.invoke()

    fun access(seats: List<String>): List<OperationAccess> {
        val declared = entries().associateBy { it.catalog.id }
        return catalog().map { catalog ->
            OperationAccess(catalog.id, catalog.summary, catalog.effect, catalog.provider, seats.associateWith { seat ->
                when (declared.getValue(catalog.id).decide(catalog.effect, authority.policy.seat(seat))) {
                    DoorDecision.Allowed -> Access.Allowed
                    is DoorDecision.Ask -> if (authority.approvals == null) Access.Refused else Access.Ask
                    is DoorDecision.Refused -> Access.Refused
                }
            })
        }
    }

    suspend fun invoke(invocation: Invocation): Outcome {
        val contract = contractEntries().firstOrNull { it.catalog.id == invocation.operation } ?: return gate.invoke(invocation)
        return try {
            Outcome.Completed(contract.execute(OperationCall(invocation), invocation.arguments))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Rejected) {
            failure.problemAs<Outcome>() ?: Outcome.Failed(failure.message ?: "Rejected")
        } catch (failure: InvalidArguments) {
            Outcome.Failed("Invalid arguments for ${invocation.operation}: ${failure.message}. operation.describe {operation: ${invocation.operation}} shows what it takes")
        } catch (failure: CallFailure) {
            Outcome.Failed(failure.message ?: "Contract call failed")
        } catch (failure: Exception) {
            Outcome.Failed(failure.message ?: failure::class.simpleName.orEmpty())
        }
    }

    private fun entries(): List<Entry> {
        val contracts = contractEntries()
        val migrated = contracts.mapTo(hashSetOf()) { it.catalog.id }
        return contracts + legacyEntries().filterNot { it.catalog.id in migrated }
    }

    @Suppress("UNCHECKED_CAST")
    private fun contractEntries(): List<Entry> = graph.contracts()
        .filter { it.role == ContractRole.Server && it.coroutineScope.isActive }
        .flatMap { node -> node.api.operations.filterIsInstance<Call<*, *>>().map { declared ->
            val operation = declared as Call<Any?, Any?>
            val id = OperationId("${node.api.id.substringAfterLast('.')}.${operation.name}")
            val effect = SafetyClass.valueOf(operation.effect.name)
            val provider = id.domain
            val subject = operation.request.descriptor.subject()
            Entry(CatalogEntry(id, operation.summary, effect, provider, subject),
                { OperationDescriptor(id, operation.summary, effect, provider, operation.request.descriptor.jsonSchema(), operation.response.descriptor.jsonSchema(), subject) },
                { _, _ -> effect }, { _, _ -> false }) { call, arguments ->
                val request = try { json.decodeFromJsonElement(operation.request, arguments) }
                catch (failure: IllegalArgumentException) { throw InvalidArguments(failure) }
                val attributes = buildMap<String, Any?> {
                    put(OperationGate.CallerAttribute, call.invocation.caller)
                    call.invocation.requestId?.let { put(OperationGate.RequestIdAttribute, it) }
                }
                withContext(CallAttributes(attributes)) {
                    val client = graph.nodes.filterIsInstance<ContractCall>().singleOrNull { it.call.call.id == operation.id }
                        ?: error("The operation adapter for ${operation.id} has not been wired")
                    json.encodeToJsonElement(operation.response, client.call(request))
                }
            }
        } }

    private fun legacyEntries(): List<Entry> {
        val nodes = graph.nodes
        val typed = nodes.filterIsInstance<OperationNode>().filter { it.coroutineScope.isActive }
            .flatMap { node -> node.operations.map { it.entry(node.provider, node.coroutineScope) } }
        val taken = (own + typed).mapTo(mutableSetOf()) { it.catalog.id }
        val legacy = nodes.filterIsInstance<ToolNode>().filterNot { it.isClosed }.flatMap { node ->
            node.definitions.mapNotNull { definition ->
                val id = legacyId(definition.name)?.takeIf(taken::add) ?: return@mapNotNull null
                val provider = node.label.ifBlank { node::class.simpleName ?: "graph" }
                val descriptor = OperationDescriptor(id, definition.description, definition.effect(), provider, definition.inputSchema, definition.outputSchema)
                Entry(CatalogEntry(id, definition.description, descriptor.effect, provider), { descriptor }, { _, _ -> descriptor.effect }, { _, _ -> false }) { _, arguments ->
                    val result = node.execute(definition.name, arguments)
                    if (result.isError) error(result.data.errorText()) else result.data
                }
            }
        }
        return own + typed.filterNot { it.catalog.id in OwnIds } + legacy
    }

    private fun Operation<*, *>.entry(provider: String, scope: CoroutineScope) =
        Entry(CatalogEntry(id, summary, effect, provider, subject), { describe(provider) }, effectFor, appliesOwnPlan, confirmationPhraseFor) { call, arguments ->
            currentCoroutineContext().ensureActive()
            val invocation = scope.async(currentCoroutineContext().minusKey(Job)) { execute(call, arguments, json) }
            try { invocation.await() } finally { invocation.cancel() }
        }

    private class ContractCall(graph: Graph, operation: Call<Any?, Any?>) : BasicNode(graph) {
        override val label = "Operation adapter ${operation.id}"
        val call by consumes(operation)
    }

    private companion object {
        val OwnIds = setOf(OperationId("operation.catalog"), OperationId("operation.describe"))

        fun legacyId(name: String): OperationId? {
            val dotted = name.lowercase().replace('_', '.').let { if ('.' in it) it else "tool.$it" }
            return dotted.takeIf(OperationId.Pattern::matches)?.let(::OperationId)
        }

        fun ToolDefinition.effect() = when {
            readOnly -> SafetyClass.ReadOnly
            destructive -> SafetyClass.Destructive
            openWorld -> SafetyClass.UnknownRemoteEffect
            else -> SafetyClass.LocalArtifactWrite
        }

        fun JsonElement.errorText(): String =
            (this as? JsonObject)?.get("error")?.jsonPrimitive?.content ?: toString()
    }
}
