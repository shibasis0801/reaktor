package dev.shibasis.reaktor.auth.runtime

import dev.shibasis.reaktor.auth.api.AuthService
import dev.shibasis.reaktor.auth.fga.AccessDecision
import dev.shibasis.reaktor.auth.fga.AccessDecisionSink
import dev.shibasis.reaktor.auth.fga.AccessDecisions
import dev.shibasis.reaktor.auth.fga.RelationTuple
import dev.shibasis.reaktor.auth.fga.Subject
import dev.shibasis.reaktor.auth.graph.CodeAccess
import dev.shibasis.reaktor.auth.graph.CodeAccessPolicy
import dev.shibasis.reaktor.auth.graph.declared
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.ServiceCall
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject

class AuthAccess(
    val policy: () -> CodeAccessPolicy?,
    val decisions: AccessDecisionSink = AccessDecisions,
) {
    fun invoking(request: Request, subject: Subject, contextual: List<RelationTuple>, operation: String, tenant: String? = null): AccessDecision? =
        runCatching {
            policy()?.check(subject, (contextual + CodeAccess.declared(AuthService().id, AuthService().access, operation)).distinct(), AuthService().id, operation, tenant)
        }.getOrNull()?.also { decision ->
            request.attributes[ServiceCall.AccessAttribute] = decision.encodeForSpan()
            record(decision)
        }

    fun holding(subject: Subject, contextual: List<RelationTuple>, permission: String, tenant: String? = null): AccessDecision? =
        runCatching { policy()?.holds(subject, contextual, permission, AuthService().id, tenant) }.getOrNull()?.also(::record)

    private fun record(decision: AccessDecision) {
        runCatching { decisions.record(decision) }
    }
}

internal val AccessDecision?.audited: JsonElement
    get() = this?.let { buildJsonObject { put("access", Json.parseToJsonElement(it.encodeForSpan())) } } ?: JsonObject(emptyMap())
