package dev.shibasis.reaktor.auth.runtime.nodes

import dev.shibasis.reaktor.auth.AuthAuditEventType
import dev.shibasis.reaktor.auth.AuthAuditOutcome
import dev.shibasis.reaktor.auth.runtime.ports.AuthAuditEventDraft
import dev.shibasis.reaktor.auth.runtime.ports.AuthAuditSink
import dev.shibasis.reaktor.service.Request
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal suspend fun AuthAuditSink.audit(
    request: Request,
    eventType: AuthAuditEventType,
    outcome: AuthAuditOutcome,
    actorPrincipalId: String? = null,
    subjectPrincipalId: String? = null,
    appId: String? = null,
    tenantId: String? = null,
    contextId: String? = null,
    sessionId: String? = null,
    credentialType: String? = null,
    grantType: String? = null,
    tokenId: String? = null,
    audience: String? = null,
    scopes: List<String> = emptyList(),
    reason: String? = null,
    data: JsonElement = JsonObject(emptyMap()),
) {
    record(
        request,
        AuthAuditEventDraft(
            eventType = eventType,
            outcome = outcome,
            actorPrincipalId = actorPrincipalId,
            subjectPrincipalId = subjectPrincipalId,
            appId = appId,
            tenantId = tenantId,
            contextId = contextId,
            sessionId = sessionId,
            credentialType = credentialType,
            grantType = grantType,
            tokenId = tokenId,
            audience = audience,
            scopes = JsonArray(scopes.map(::JsonPrimitive)),
            reason = reason,
            data = data,
        )
    )
}

internal suspend fun AuthAuditSink.auditSuccess(
    request: Request,
    eventType: AuthAuditEventType,
    actorPrincipalId: String? = null,
    subjectPrincipalId: String? = null,
    appId: String? = null,
    tenantId: String? = null,
    contextId: String? = null,
    sessionId: String? = null,
    credentialType: String? = null,
    grantType: String? = null,
    tokenId: String? = null,
    audience: String? = null,
    scopes: List<String> = emptyList(),
    data: JsonElement = JsonObject(emptyMap()),
) = audit(
    request = request,
    eventType = eventType,
    outcome = AuthAuditOutcome.SUCCESS,
    actorPrincipalId = actorPrincipalId,
    subjectPrincipalId = subjectPrincipalId,
    appId = appId,
    tenantId = tenantId,
    contextId = contextId,
    sessionId = sessionId,
    credentialType = credentialType,
    grantType = grantType,
    tokenId = tokenId,
    audience = audience,
    scopes = scopes,
    data = data,
)

internal suspend fun AuthAuditSink.auditFailure(
    request: Request,
    reason: String,
    actorPrincipalId: String? = null,
    subjectPrincipalId: String? = null,
    appId: String? = null,
    tenantId: String? = null,
    contextId: String? = null,
    sessionId: String? = null,
    credentialType: String? = null,
    grantType: String? = null,
    tokenId: String? = null,
    audience: String? = null,
    scopes: List<String> = emptyList(),
    data: JsonElement = JsonObject(emptyMap()),
) = audit(
    request = request,
    eventType = AuthAuditEventType.AUTH_FAILURE,
    outcome = AuthAuditOutcome.FAILURE,
    actorPrincipalId = actorPrincipalId,
    subjectPrincipalId = subjectPrincipalId,
    appId = appId,
    tenantId = tenantId,
    contextId = contextId,
    sessionId = sessionId,
    credentialType = credentialType,
    grantType = grantType,
    tokenId = tokenId,
    audience = audience,
    scopes = scopes,
    reason = reason,
    data = data,
)
