package dev.shibasis.reaktor.auth.services

import com.nimbusds.jwt.JWTClaimsSet
import dev.shibasis.reaktor.auth.AuthAuditEventType
import dev.shibasis.reaktor.auth.AuthAuditOutcome
import dev.shibasis.reaktor.auth.AuthCredentialType
import dev.shibasis.reaktor.auth.api.*
import dev.shibasis.reaktor.auth.jwt.*
import dev.shibasis.reaktor.auth.kernel.*
import dev.shibasis.reaktor.auth.runtime.bearerToken
import dev.shibasis.reaktor.auth.runtime.ports.*
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.service.Request
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/** Tenant switching re-resolves current database grants; it never copies a superadmin claim across tenants. */
class AuthorityService(
    private val verifier: JwtVerifier,
    private val minter: JwtMinter,
    private val directory: AuthAuthorityDirectory,
    private val audit: AuthAuditSink,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AuthAuthority {
    private data class Source(val claims: JWTClaimsSet, val active: ActiveAuthoritySession)

    private suspend fun source(request: Request, sourceAppId: String): Source? {
        val appId = runCatching { UUID.fromString(sourceAppId) }.getOrNull() ?: return null
        val token = request.bearerToken()?.takeIf { it.length <= 32_768 } ?: return null
        val claims = verifier.verifyReaktorToken(token, listOf(sourceAppId)).getOrNull() ?: return null
        if (claims.authPrincipalKind() != PrincipalKind.USER || claims.getClaim("act") != null ||
            claims.getStringClaim("credential_type") != AuthCredentialType.ACCESS_TOKEN.wireName ||
            (claims.expirationTime?.time ?: 0) <= nowMillis()) return null
        val principal = runCatching { UUID.fromString(claims.subject) }.getOrNull() ?: return null
        val session = runCatching { UUID.fromString(claims.authSessionId()) }.getOrNull() ?: return null
        val active = directory.snapshot(request, principal, session, appId).getOrNull() ?: return null
        return Source(claims, active)
    }

    override suspend fun grants(request: AuthorityGrantsRequest): AuthorityGrantsResponse {
        val source = source(request, request.sourceAppId) ?: return AuthorityGrantsResponse(statusCode = StatusCode.UNAUTHORIZED)
        return AuthorityGrantsResponse(source.active.grants.map { it.grant })
    }

    override suspend fun resolve(request: AuthorityResolveRequest): AuthorityResolveResponse {
        if (request.permission.isBlank() || request.permission.length > 200 || request.permission.any(Char::isISOControl))
            return AuthorityResolveResponse(statusCode = StatusCode.BAD_REQUEST)
        val source = source(request, request.sourceAppId) ?: return AuthorityResolveResponse(statusCode = StatusCode.UNAUTHORIZED)
        val grant = source.active.grants.singleOrNull { it.grant.target == request.target }
        val context = grant?.let {
            source.claims.toAuthContext().copy(appId = request.target.appId, audience = request.target.appId,
                principal = source.claims.toAuthContext().principal.copy(id = it.principalId),
                tenantId = request.target.tenantId, contextId = request.target.contextId,
                roles = it.grant.roles.map { role -> RoleRef(name = role) }.toSet(),
                permissions = it.permissions.map { permission -> PermissionRef(name = permission) }.toSet(),
                scopes = emptySet(), claims = JsonObject(emptyMap()))
        }
        val allowed = context != null && LocalAuthorizer.authorize(context,
            AuthRequirement(app = AppConstraint(request.target.appId), audience = Audience(request.target.appId),
                tenant = TenantConstraint(request.target.tenantId), permissions = setOf(PermissionRef(name = request.permission)),
                principalKinds = setOf(PrincipalKind.USER))) is AuthDecision.Allow
        val event = AuthAuditEventDraft(eventType = AuthAuditEventType.TOKEN_EXCHANGE,
            outcome = if (allowed) AuthAuditOutcome.SUCCESS else AuthAuditOutcome.FAILURE,
            actorPrincipalId = source.active.principal.id, subjectPrincipalId = grant?.principalId,
            appId = grant?.grant?.target?.appId ?: source.active.session.appId,
            tenantId = grant?.grant?.target?.tenantId, contextId = grant?.grant?.target?.contextId,
            sessionId = source.active.session.id, credentialType = AuthCredentialType.ACCESS_TOKEN.wireName,
            grantType = "tenant_authority", audience = request.target.appId, reason = if (allowed) "current_tenant_grant" else "tenant_permission_denied")
        // A minted privileged credential must have its audit record; storage failure fails closed.
        if (audit.record(request, event).isFailure) return AuthorityResolveResponse(statusCode = StatusCode.INTERNAL_SERVER_ERROR)
        if (!allowed) return AuthorityResolveResponse(statusCode = StatusCode.FORBIDDEN)
        val now = nowMillis()
        val expiresAt = minOf(now + 60_000, source.claims.expirationTime.time, source.active.session.expiresAt.toEpochMilliseconds())
        if (expiresAt <= now) return AuthorityResolveResponse(statusCode = StatusCode.UNAUTHORIZED)
        val token = minter.mintAccessToken(principalId = requireNotNull(grant).principalId,
            appId = request.target.appId, audience = request.target.appId, tenantId = request.target.tenantId,
            contextId = request.target.contextId, identityId = source.active.principal.identityId,
            sessionId = source.active.session.id, roles = grant.grant.roles,
            permissions = grant.permissions, scopes = listOf(request.permission), expiresAtEpochMillis = expiresAt)
        val issued = verifier.verifyReaktorToken(token, listOf(request.target.appId)).getOrNull()
            ?: return AuthorityResolveResponse(statusCode = StatusCode.INTERNAL_SERVER_ERROR)
        return AuthorityResolveResponse(AuthorityResolution(issued.toAuthContext(), request.environment.name, expiresAt, token))
    }
}
