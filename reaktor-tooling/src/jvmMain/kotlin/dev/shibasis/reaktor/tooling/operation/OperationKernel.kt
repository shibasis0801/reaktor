package dev.shibasis.reaktor.tooling.operation

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.mcp.ToolDefinition
import dev.shibasis.reaktor.mcp.ToolNode
import dev.shibasis.reaktor.tooling.*
import dev.shibasis.reaktor.code.planFingerprint
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
)

data class ModelVerdict(val allowed: Boolean, val enforced: Boolean, val rule: String, val record: JsonObject)

fun interface OperationModel {
    fun decide(caller: CallCaller, provider: String, operation: OperationId): ModelVerdict?
}

class OperationKernel(
    private val graph: Graph,
    private val authority: OperationAuthority,
    private val json: Json = OperationJson,
    private val preferred: Map<OperationId, OperationId> = emptyMap(),
) {
    private val replies = object : LinkedHashMap<Pair<CallCaller, String>, Outcome>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<CallCaller, String>, Outcome>) = size > ReplayLimit
    }

    private val own = listOf(
        operation<CatalogQuery, List<CatalogEntry>>("operation.catalog", "Lists the operations this workspace offers, with their effect, the preferred ones first; " +
            "an entry with prefer is kept for older callers and the operation it names does the same work. Describe one before calling it with unfamiliar arguments.") { query ->
            catalog().filter { query.domain == null || it.id.domain == query.domain }
                .filter { entry -> query.query.isNullOrBlank() || query.query.lowercase().split(' ').all { word -> word in entry.id.value || word in entry.summary.lowercase() } }
        },
        operation<DescribeRequest, OperationDescriptor>("operation.describe", "One operation's input and output schema and its effect.") { request ->
            describe(request.operation) ?: error("Unknown operation ${request.operation}")
        },
    ).map { it.entry("kernel") }

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
        val started = System.nanoTime()
        val entry = entries().firstOrNull { it.catalog.id == invocation.operation }
            ?: return Outcome.Failed("Unknown operation ${invocation.operation}. operation.catalog lists them.")
        val descriptor = entry.catalog
        val key = invocation.requestId?.let { invocation.caller to it }
        key?.let { synchronized(replies) { replies[it] } }?.let { return it }

        var verdict: ModelVerdict? = null
        fun record(outcome: String, detail: String? = null) = authority.log?.record(
            descriptor.provider, descriptor.id.value, invocation.arguments, (System.nanoTime() - started) / 1_000_000, outcome, detail, invocation.caller, verdict?.record,
        )

        val seat = invocation.caller.seat
        val effect = runCatching { entry.effect(invocation.arguments, invocation.caller) }
            .getOrElse { failure -> return Outcome.Failed(failure.message ?: "Could not classify ${descriptor.id}").also { record("failed", it.message) } }
        verdict = authority.model?.let { model -> runCatching { model.decide(invocation.caller, descriptor.provider, descriptor.id) }.getOrNull() }
        verdict?.takeIf { it.enforced && !it.allowed }?.let { denied -> return Outcome.Refused(effect, denied.rule).also { record("refused", denied.rule) } }
        val ownPlan = runCatching { entry.ownPlan(invocation.arguments, invocation.caller) }.getOrDefault(false)
        val confirmationPhrase = runCatching { entry.confirmationPhrase?.invoke(invocation.arguments) }.getOrElse { failure ->
            return Outcome.Failed(failure.message ?: "Could not validate the reviewed approval").also { record("failed", it.message) }
        }
        var approval: DoorApproval? = null
        when (val decision = entry.decide(effect, authority.policy.seat(seat), ownPlan)) {
            DoorDecision.Allowed -> Unit
            is DoorDecision.Refused -> return Outcome.Refused(effect, decision.reason).also { record("refused", decision.reason) }
            is DoorDecision.Ask -> {
                val approvals = authority.approvals
                    ?: return Outcome.Refused(effect, "${effect.name} needs a person's approval and this host cannot ask for one").also { record("refused", "no approvals") }
                val spent = approvals.spend(seat, descriptor.provider, descriptor.id.value, invocation.arguments, confirmationPhrase)
                if (spent == null) {
                    approvals.denied(seat, descriptor.provider, descriptor.id.value, invocation.arguments)?.let { denied ->
                        return Outcome.Denied(denied.id, denied.decidedBy).also { record("denied", denied.id) }
                    }
                    val before = approvals.pending().map(DoorApproval::id).toSet()
                    val waiting = approvals.request(seat, descriptor.provider, descriptor.id.value, effect, invocation.arguments, confirmationPhrase)
                    if (waiting.id !in before) runCatching { authority.onApprovalNeeded(waiting) }
                    return Outcome.ApprovalRequired(waiting.id, effect, waiting.expiresAtEpochMillis).also { record("approval-required", waiting.id) }
                }
                approval = spent
            }
        }

        val receipt = authority.runs?.takeIf { effect != SafetyClass.ReadOnly && effect != SafetyClass.LiveRead }?.let { (workspace, sink) ->
            val now = System.currentTimeMillis()
            val fingerprint = planFingerprint(listOf(descriptor.provider, descriptor.id.value,
                invocation.caller.seat.orEmpty(), invocation.caller.runId.orEmpty(), invocation.arguments.toString()))
            val id = RunId("kernel-${java.util.UUID.randomUUID()}")
            val references = listOfNotNull(
                approval?.let { ArtifactReference(ArtifactId("approval-${it.id}"), "approval", it.id) },
                invocation.caller.runId?.let { ArtifactReference(ArtifactId("parent-$it"), "parent-run", it) },
            )
            val run = TaskRun(id, workspace, TaskId(descriptor.id.value), PlanId(fingerprint), RunStatus.Running,
                now, startedAtEpochMillis = now, artifacts = references)
            val plan = TaskPlan(run.planId, workspace, run.taskId, TaskInvocation(run.taskId), SafetyPolicy(effect),
                listOf(descriptor.id.value), "", fingerprint, now)
            try {
                val event = RunEvent.Started(id, 0, now, run, plan)
                sink.append(event)
                authority.log?.append(event)
            } catch (failure: Exception) {
                return Outcome.Failed("Run receipt unavailable; ${descriptor.id} was not executed").also { record("failed", it.message) }
            }
            run
        }
        suspend fun finishReceipt(status: RunStatus) {
            receipt?.let { run ->
                val now = System.currentTimeMillis()
                val event = RunEvent.Completed(run.id, 1, now, run.copy(status = status,
                    finishedAtEpochMillis = now, exitCode = if (status == RunStatus.Succeeded) 0 else if (status == RunStatus.Cancelled) 130 else 1))
                requireNotNull(authority.runs).second.append(event)
                authority.log?.append(event)
            }
        }
        var outcome = try {
            Outcome.Completed(entry.execute(OperationCall(invocation, approval, receipt?.id), invocation.arguments))
        } catch (cancelled: CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { finishReceipt(RunStatus.Cancelled) }
            throw cancelled
        } catch (invalid: InvalidArguments) {
            Outcome.Failed("Invalid arguments for ${descriptor.id}: ${invalid.message}. operation.describe {operation: ${descriptor.id}} shows what it takes")
        } catch (unreadable: SerializationException) {
            Outcome.Failed("${descriptor.id} read its arguments but could not decode other data it depends on: ${unreadable.message}. " +
                "The program that wrote that data is likely newer or older than this ${descriptor.provider} build")
        } catch (failure: Exception) {
            Outcome.Failed(failure.message ?: failure::class.simpleName.orEmpty())
        }
        try { finishReceipt(if (outcome is Outcome.Completed) RunStatus.Succeeded else RunStatus.Failed) }
        catch (failure: Exception) {
            outcome = Outcome.Failed("${descriptor.id} executed, but its final receipt could not be saved; inspect the run before retrying")
        }
        record(if (outcome is Outcome.Completed) "ok" else "failed", listOfNotNull(effect.name, approval?.let { "approval ${it.id} by ${it.decidedBy}" },
            (outcome as? Outcome.Failed)?.message).joinToString("; "))
        if (key != null && effect != SafetyClass.ReadOnly) synchronized(replies) { replies[key] = outcome }
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
                Entry(CatalogEntry(id, definition.description, descriptor.effect, provider), { descriptor }, { _, _ -> descriptor.effect }, { _, _ -> false }) { _, arguments ->
                    val result = node.execute(definition.name, arguments)
                    if (result.isError) error(result.data.errorText()) else result.data
                }
            }
        }
        return own + typed.filterNot { it.catalog.id in OwnIds } + legacy
    }

    private fun Operation<*, *>.entry(provider: String) =
        Entry(CatalogEntry(id, summary, effect, provider, subject), { describe(provider) }, effectFor, appliesOwnPlan, confirmationPhraseFor) { call, arguments -> execute(call, arguments, json) }

    private class Entry(
        val catalog: CatalogEntry,
        val describe: () -> OperationDescriptor,
        val effect: (JsonObject, CallCaller) -> SafetyClass,
        val ownPlan: suspend (JsonObject, CallCaller) -> Boolean,
        val confirmationPhrase: ((JsonObject) -> String?)? = null,
        val execute: suspend (OperationCall, JsonObject) -> JsonElement,
    ) {
        fun decide(effect: SafetyClass, seat: dev.shibasis.reaktor.tooling.mcp.door.SeatPolicy, ownPlan: Boolean = false): DoorDecision =
            DoorAuthority.decide(effect, seat, ownPlan).let { decision ->
                if (decision == DoorDecision.Allowed && confirmationPhrase != null) DoorDecision.Ask(effect) else decision
            }
    }

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
            openWorld -> SafetyClass.UnknownRemoteEffect
            else -> SafetyClass.LocalArtifactWrite
        }

        fun JsonElement.errorText(): String =
            (this as? JsonObject)?.get("error")?.jsonPrimitive?.content ?: toString()
    }
}
