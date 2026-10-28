package dev.shibasis.reaktor.tooling.operation

import dev.shibasis.reaktor.tooling.*
import dev.shibasis.reaktor.code.planFingerprint
import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.mcp.door.DoorApproval
import dev.shibasis.reaktor.tooling.mcp.door.DoorAuthority
import dev.shibasis.reaktor.tooling.mcp.door.DoorDecision
import dev.shibasis.reaktor.telemetry.export.ClientOperationContext
import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.tracing.data.StatusData
import io.opentelemetry.kotlin.tracing.model.Span
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

import dev.shibasis.reaktor.service.contract.Call
import dev.shibasis.reaktor.service.contract.Exchange
import dev.shibasis.reaktor.service.contract.Family
import dev.shibasis.reaktor.service.contract.Interceptor
import dev.shibasis.reaktor.service.contract.Next
import dev.shibasis.reaktor.service.contract.Position
import dev.shibasis.reaktor.service.contract.Side
import dev.shibasis.reaktor.service.contract.reject
import dev.shibasis.reaktor.service.contract.Unavailable
import kotlinx.serialization.json.jsonObject

@OptIn(ExperimentalApi::class)
class OperationGate internal constructor(
    private val authority: OperationAuthority,
    private val json: Json,
    private val resolve: (OperationId) -> Entry?,
) : Interceptor {
    private val replies = object : LinkedHashMap<Pair<CallCaller, String>, Outcome>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<CallCaller, String>, Outcome>) = size > ReplayLimit
    }

    interface Classifier {
        fun effect(operation: String, arguments: JsonObject, caller: CallCaller): SafetyClass
        suspend fun ownPlan(operation: String, arguments: JsonObject, caller: CallCaller): Boolean = false
    }

    override val position: Int get() = Position.Security
    override val sides: Set<Side> get() = setOf(Side.Server)
    override val families: Set<Family> get() = setOf(Family.Call)

    @Suppress("UNCHECKED_CAST")
    override suspend fun <In, R> intercept(exchange: Exchange<In, R>, next: Next<In, R>): R {
        val operation = exchange.operation as? Call<In, R> ?: return next(exchange)
        val declaredEffect = SafetyClass.valueOf(operation.effect.name)
        val caller = exchange.attributes[CallerAttribute] as? CallCaller
            ?: reject<Outcome>(Outcome.Refused(declaredEffect, "The connection has no authenticated caller"), 403, "Authenticated caller required")
        val id = OperationId("${exchange.contract.substringAfterLast('.')}.${exchange.name}")
        val arguments = json.encodeToJsonElement(operation.request, exchange.request).jsonObject
        val existing = resolve(id)
        val classifier = operation.service as? Classifier
        val provider = existing?.catalog?.provider ?: id.domain
        val descriptor = CatalogEntry(id, operation.summary, declaredEffect, provider, operation.request.descriptor.subject())
        val entry = Entry(descriptor,
            { OperationDescriptor(id, operation.summary, declaredEffect, provider, operation.request.descriptor.jsonSchema(), operation.response.descriptor.jsonSchema(), descriptor.subject) },
            { args, authenticated ->
                val classified = classifier?.effect(operation.name, args, authenticated)
                    ?: existing?.effect?.invoke(args, authenticated) ?: declaredEffect
                if (id.value == "change.apply" && classifier != null && classified == SafetyClass.ReadOnly) classified
                else maxOf(declaredEffect, classified)
            },
            { args, authenticated ->
                classifier?.ownPlan(operation.name, args, authenticated) ?: existing?.ownPlan?.invoke(args, authenticated) ?: false
            }, existing?.confirmationPhrase) { call, _ ->
            exchange.attributes[CallAttribute] = call
            json.encodeToJsonElement(operation.response, next(exchange))
        }
        val invocation = Invocation(id, arguments, caller, exchange.attributes[RequestIdAttribute] as? String)
        return when (val outcome = invoke(invocation, entry)) {
            is Outcome.Completed -> json.decodeFromJsonElement(operation.response, outcome.result)
            is Outcome.ApprovalRequired -> reject<Outcome>(outcome, 409, "Approval required")
            is Outcome.Denied -> reject<Outcome>(outcome, 403, "Approval denied")
            is Outcome.Refused -> reject<Outcome>(outcome, 403, outcome.reason)
            is Outcome.Failed -> throw Unavailable(outcome.message, 500)
        }
    }

    internal suspend fun invoke(invocation: Invocation, entry: Entry? = resolve(invocation.operation)): Outcome {
        val telemetry = authority.telemetry ?: return execute(invocation, entry, null)
        return telemetry.traceAsync(invocation.operation.value, buildMap {
            put("reaktor.operation.id", invocation.operation.value)
            put("reaktor.operation.effect", "unknown")
            invocation.caller.seat?.let { put("reaktor.operation.seat", it) }
            invocation.caller.runId?.let { put("reaktor.caller.run_id", it) }
        }) {
            val span = currentCoroutineContext()[ClientOperationContext]?.span
            try {
                execute(invocation, entry, span).also { outcome ->
                    val disposition = when (outcome) {
                        is Outcome.Completed -> "completed"
                        is Outcome.Failed -> "failed"
                        is Outcome.ApprovalRequired -> "approval-required"
                        is Outcome.Denied -> "denied"
                        is Outcome.Refused -> "refused"
                    }
                    span?.setStringAttribute("reaktor.operation.outcome", disposition)
                    if (outcome is Outcome.Failed || outcome is Outcome.Denied || outcome is Outcome.Refused)
                        span?.status = StatusData.Error(disposition)
                }
            } catch (cancelled: CancellationException) {
                span?.setStringAttribute("reaktor.operation.outcome", "cancelled")
                throw cancelled
            } catch (failure: Throwable) {
                span?.setStringAttribute("reaktor.operation.outcome", "failed")
                throw failure
            }
        }
    }

    private suspend fun execute(invocation: Invocation, offered: Entry?, span: Span?): Outcome {
        val entry = offered ?: return Outcome.Failed("Unknown operation ${invocation.operation}. operation.catalog lists them.")
        val started = System.nanoTime()
        val descriptor = entry.catalog
        span?.setStringAttribute("reaktor.operation.provider", descriptor.provider)
        val key = invocation.requestId?.let { invocation.caller to it }
        key?.let { synchronized(replies) { replies[it] } }?.let {
            span?.setBooleanAttribute("reaktor.operation.replayed", true)
            return it
        }

        var verdict: ModelVerdict? = null
        fun record(outcome: String, detail: String? = null) = authority.log?.record(
            descriptor.provider, descriptor.id.value, invocation.arguments, (System.nanoTime() - started) / 1_000_000, outcome, detail, invocation.caller, verdict?.record,
        )

        val seat = invocation.caller.seat
        val effect = runCatching { entry.effect(invocation.arguments, invocation.caller) }
            .getOrElse { failure -> return Outcome.Failed(failure.message ?: "Could not classify ${descriptor.id}").also { record("failed", it.message) } }
        span?.setStringAttribute("reaktor.operation.effect", effect.name)
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
        receipt?.let { span?.setStringAttribute("reaktor.run.id", it.id.value) }
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

    internal class Entry(
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

    companion object {
        const val CallerAttribute = "forward.reaktor.operation.caller"
        const val RequestIdAttribute = "forward.reaktor.operation.request_id"
        const val CallAttribute = "reaktor.operation.call"
        private const val ReplayLimit = 256
    }
}

