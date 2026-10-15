package dev.shibasis.reaktor.tooling.operation

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.mcp.ToolDefinition
import dev.shibasis.reaktor.mcp.ToolNode
import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.mcp.door.CallLog
import dev.shibasis.reaktor.tooling.mcp.door.DoorApproval
import dev.shibasis.reaktor.tooling.mcp.door.DoorApprovals
import dev.shibasis.reaktor.tooling.mcp.door.DoorAuthority
import dev.shibasis.reaktor.tooling.mcp.door.DoorDecision
import dev.shibasis.reaktor.tooling.mcp.door.DoorPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

open class OperationNode(graph: Graph, val provider: String, val operations: List<Operation<*, *>>) : BasicNode(graph) {
    init { require(operations.distinctBy { it.id }.size == operations.size) { "Duplicate operation ids from $provider" } }
}

class OperationAuthority(
    val policy: DoorPolicy = DoorPolicy(),
    val approvals: DoorApprovals? = null,
    val log: CallLog? = null,
    val onApprovalNeeded: (DoorApproval) -> Unit = {},
)

class OperationKernel(
    private val graph: Graph,
    private val authority: OperationAuthority,
    private val json: Json = OperationJson,
) {
    private val replies = object : LinkedHashMap<Pair<String?, String>, Outcome>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String?, String>, Outcome>) = size > ReplayLimit
    }

    private val own = listOf(
        operation<CatalogQuery, List<CatalogEntry>>("operation.catalog", "Lists the operations this workspace offers, with their effect. Describe one before calling it with unfamiliar arguments.") { query ->
            catalog().filter { query.domain == null || it.id.domain == query.domain }
                .filter { entry -> query.query.isNullOrBlank() || query.query.lowercase().split(' ').all { word -> word in entry.id.value || word in entry.summary.lowercase() } }
        },
        operation<DescribeRequest, OperationDescriptor>("operation.describe", "One operation's input and output schema and its effect.") { request ->
            describe(request.operation) ?: error("Unknown operation ${request.operation}")
        },
    ).map { it.entry("kernel") }

    fun catalog(): List<CatalogEntry> = entries().map { it.catalog }.sortedBy { it.id.value }

    fun describe(id: OperationId): OperationDescriptor? = entries().firstOrNull { it.catalog.id == id }?.describe?.invoke()

    suspend fun invoke(invocation: Invocation): Outcome {
        val started = System.nanoTime()
        val entry = entries().firstOrNull { it.catalog.id == invocation.operation }
            ?: return Outcome.Failed("Unknown operation ${invocation.operation}. operation.catalog lists them.")
        val descriptor = entry.catalog
        val key = invocation.requestId?.let { invocation.caller.seat to it }
        key?.let { synchronized(replies) { replies[it] } }?.let { return it }

        fun record(outcome: String, detail: String? = null) = authority.log?.record(
            descriptor.provider, descriptor.id.value, invocation.arguments, (System.nanoTime() - started) / 1_000_000, outcome, detail, invocation.caller,
        )

        val seat = invocation.caller.seat
        var approvedBy: String? = null
        when (val decision = DoorAuthority.decide(descriptor.effect, authority.policy.seat(seat))) {
            DoorDecision.Allowed -> Unit
            is DoorDecision.Refused -> return Outcome.Refused(descriptor.effect, decision.reason).also { record("refused", decision.reason) }
            is DoorDecision.Ask -> {
                val approvals = authority.approvals
                    ?: return Outcome.Refused(descriptor.effect, "${descriptor.effect.name} needs a person's approval and this host cannot ask for one").also { record("refused", "no approvals") }
                val spent = approvals.spend(seat, descriptor.provider, descriptor.id.value, invocation.arguments)
                if (spent == null) {
                    approvals.denied(seat, descriptor.provider, descriptor.id.value, invocation.arguments)?.let { denied ->
                        return Outcome.Denied(denied.id, denied.decidedBy).also { record("denied", denied.id) }
                    }
                    val before = approvals.pending().map(DoorApproval::id).toSet()
                    val waiting = approvals.request(seat, descriptor.provider, descriptor.id.value, descriptor.effect, invocation.arguments)
                    if (waiting.id !in before) runCatching { authority.onApprovalNeeded(waiting) }
                    return Outcome.ApprovalRequired(waiting.id, descriptor.effect, waiting.expiresAtEpochMillis).also { record("approval-required", waiting.id) }
                }
                approvedBy = "approval ${spent.id} by ${spent.decidedBy}"
            }
        }

        val outcome = try {
            Outcome.Completed(entry.execute(OperationCall(invocation), invocation.arguments))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: SerializationException) {
            Outcome.Failed("Invalid arguments for ${descriptor.id}: ${invalid.message}")
        } catch (failure: Exception) {
            Outcome.Failed(failure.message ?: failure::class.simpleName.orEmpty())
        }
        record(if (outcome is Outcome.Completed) "ok" else "failed", listOfNotNull(descriptor.effect.name, approvedBy, (outcome as? Outcome.Failed)?.message).joinToString("; "))
        if (key != null && descriptor.effect != SafetyClass.ReadOnly) synchronized(replies) { replies[key] = outcome }
        return outcome
    }

    private fun entries(): List<Entry> {
        val nodes = graph.nodes
        val typed = nodes.filterIsInstance<OperationNode>().flatMap { node -> node.operations.map { it.entry(node.provider) } }
        val taken = (own + typed).mapTo(mutableSetOf()) { it.catalog.id }
        val legacy = nodes.filterIsInstance<ToolNode>().filterNot { it.isClosed }.flatMap { node ->
            node.definitions.mapNotNull { definition ->
                val id = legacyId(definition.name)?.takeIf(taken::add) ?: return@mapNotNull null
                val provider = node.label.ifBlank { node::class.simpleName ?: "graph" }
                val descriptor = OperationDescriptor(id, definition.description, definition.effect(), provider, definition.inputSchema, definition.outputSchema)
                Entry(CatalogEntry(id, definition.description, descriptor.effect, provider), { descriptor }) { _, arguments ->
                    val result = node.execute(definition.name, arguments)
                    if (result.isError) error(result.data.errorText()) else result.data
                }
            }
        }
        return own + typed.filterNot { it.catalog.id in OwnIds } + legacy
    }

    private fun Operation<*, *>.entry(provider: String) =
        Entry(CatalogEntry(id, summary, effect, provider, subject), { describe(provider) }) { call, arguments -> execute(call, arguments, json) }

    private class Entry(val catalog: CatalogEntry, val describe: () -> OperationDescriptor, val execute: suspend (OperationCall, JsonObject) -> JsonElement)

    private companion object {
        const val ReplayLimit = 256
        val OwnIds = setOf(OperationId("operation.catalog"), OperationId("operation.describe"))

        fun legacyId(name: String): OperationId? {
            val dotted = name.lowercase().replace('_', '.').let { if ('.' in it) it else "tool.$it" }
            return dotted.takeIf(OperationId.Pattern::matches)?.let(::OperationId)
        }

        fun ToolDefinition.effect() = when {
            readOnly -> SafetyClass.ReadOnly
            destructive -> SafetyClass.Destructive
            else -> SafetyClass.UnknownRemoteEffect
        }

        fun JsonElement.errorText(): String =
            (this as? JsonObject)?.get("error")?.jsonPrimitive?.content ?: toString()
    }
}
