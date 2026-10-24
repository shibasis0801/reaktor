package dev.shibasis.reaktor.auth.transport

import dev.shibasis.reaktor.auth.fga.AccessDecision
import dev.shibasis.reaktor.auth.fga.AccessDecisionSink
import dev.shibasis.reaktor.auth.fga.AccessMode
import dev.shibasis.reaktor.auth.fga.ObjectRef
import dev.shibasis.reaktor.auth.fga.RelationTuple
import dev.shibasis.reaktor.auth.fga.Subject
import dev.shibasis.reaktor.auth.graph.CodeAccess
import dev.shibasis.reaktor.auth.graph.CodeAccessPolicy
import dev.shibasis.reaktor.service.ServiceCall
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

class ObjectAccess internal constructor(
    private val policy: CodeAccessPolicy,
    val caller: Subject,
    private val carried: List<RelationTuple>,
    private val contract: String,
    private val tenant: String?,
    private val attributes: MutableMap<String, Any?>,
    private val decisions: AccessDecisionSink,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ObjectAccess>

    suspend fun decide(relation: String, target: ObjectRef, facts: List<RelationTuple> = emptyList(), subject: Subject = caller): AccessDecision {
        val known = if (subject == caller) carried else CodeAccess.contextual(subject)
        val decision = runCatching { policy.relates(subject, (known + facts).distinct(), relation, target, contract, tenant) }.getOrElse { failure ->
            AccessDecision(subject.toString(), relation, target.toString(), false, runCatching { policy.mode(contract) }.getOrDefault(AccessMode.Enforce),
                "The access model could not answer: ${failure.message ?: failure::class.simpleName}", atMillis = Clock.System.now().toEpochMilliseconds())
        }
        if (!decision.allowed) attributes[ServiceCall.AccessAttribute] = decision.encodeForSpan()
        val call = currentCoroutineContext()[ServiceCall]
        runCatching { decisions.record(decision.copy(trace = call?.trace?.traceId, span = call?.trace?.spanId)) }
        return decision
    }
}

suspend fun objectAccess(): ObjectAccess? = currentCoroutineContext()[ObjectAccess]
