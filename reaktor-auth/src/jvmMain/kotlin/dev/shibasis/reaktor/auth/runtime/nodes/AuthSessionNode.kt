package dev.shibasis.reaktor.auth.runtime.nodes

import dev.shibasis.reaktor.auth.api.LogoutAllRequest
import dev.shibasis.reaktor.auth.api.LogoutAllResponse
import dev.shibasis.reaktor.auth.api.LogoutRequest
import dev.shibasis.reaktor.auth.api.LogoutResponse
import dev.shibasis.reaktor.auth.api.MeRequest
import dev.shibasis.reaktor.auth.api.MeResponse
import dev.shibasis.reaktor.auth.api.RefreshRequest
import dev.shibasis.reaktor.auth.api.RefreshResponse
import dev.shibasis.reaktor.auth.api.TokenSet
import dev.shibasis.reaktor.auth.AuthAuditEventType
import dev.shibasis.reaktor.auth.AuthCredentialType
import dev.shibasis.reaktor.auth.AuthGrantType
import dev.shibasis.reaktor.auth.jwt.JwtMinter
import dev.shibasis.reaktor.auth.jwt.JwtVerifier
import dev.shibasis.reaktor.auth.jwt.authActorSubject
import dev.shibasis.reaktor.auth.jwt.authAppId
import dev.shibasis.reaktor.auth.jwt.authPermissions
import dev.shibasis.reaktor.auth.jwt.authPrincipalKind
import dev.shibasis.reaktor.auth.jwt.authRoles
import dev.shibasis.reaktor.auth.jwt.authScopes
import dev.shibasis.reaktor.auth.jwt.authSessionId
import dev.shibasis.reaktor.auth.jwt.toAuthContext
import dev.shibasis.reaktor.auth.kernel.AuthDefaults
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.runtime.AUTH_RUNTIME_CONFIG_DEPENDENCY
import dev.shibasis.reaktor.auth.runtime.AuthSessions
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeConfig
import dev.shibasis.reaktor.auth.runtime.bearerToken
import dev.shibasis.reaktor.auth.runtime.normalizedAuthNames
import dev.shibasis.reaktor.auth.runtime.ports.AuthAuditSink
import dev.shibasis.reaktor.auth.runtime.ports.AuthPrincipalDirectory
import dev.shibasis.reaktor.auth.runtime.ports.AuthSessionLifecycle
import dev.shibasis.reaktor.auth.services.uuid
import dev.shibasis.reaktor.auth.toSnapshot
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.di.dependency
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides

class AuthSessionNode(
    graph: Graph,
) : BasicNode(graph), AuthSessions {
    private val config by dependency<AuthRuntimeConfig>(AUTH_RUNTIME_CONFIG_DEPENDENCY)
    val sessionLifecyclePort by consumes<AuthSessionLifecycle>()
    val principalDirectoryPort by consumes<AuthPrincipalDirectory>()
    val jwtMinterPort by consumes<JwtMinter>()
    val jwtVerifierPort by consumes<JwtVerifier>()
    val auditPort by consumes<AuthAuditSink>()
    val sessionsPort by provides<AuthSessions>(this)

    override suspend fun refresh(request: RefreshRequest): RefreshResponse {
        val refreshToken = request.refreshToken.trim().ifEmpty { request.bearerToken().orEmpty() }
        if (refreshToken.isEmpty()) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    credentialType = AuthCredentialType.REFRESH_TOKEN.wireName,
                    grantType = AuthGrantType.REFRESH_TOKEN.wireName,
                    reason = "missing_refresh_token",
                )
            }
            return RefreshResponse(statusCode = StatusCode.UNAUTHORIZED)
        }
        val rotated = sessionLifecyclePort { rotate(refreshToken) }
        if (rotated == null) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    credentialType = AuthCredentialType.REFRESH_TOKEN.wireName,
                    grantType = AuthGrantType.REFRESH_TOKEN.wireName,
                    reason = "invalid_refresh_token",
                )
            }
            return RefreshResponse(statusCode = StatusCode.UNAUTHORIZED)
        }

        val principal = principalDirectoryPort.suspended {
            getPrincipal(request, rotated.session.principalId.uuid())
        }.getOrNull()
        val principalKind = principal?.kind ?: PrincipalKind.USER
        val isAnonymous = principal != null && principalKind == PrincipalKind.USER && principal.identityId == null
        val credentialType =
            if (isAnonymous) AuthCredentialType.ANONYMOUS.wireName else AuthCredentialType.ACCESS_TOKEN.wireName
        val permissions = (principalDirectoryPort.suspended {
            getPrincipalPermissions(request, rotated.session.principalId.uuid(), rotated.session.appId.uuid())
        }.getOrElse { emptyList() } + if (isAnonymous) config.anonymousPermissions else emptyList())
            .normalizedAuthNames()
        val roles = (principalDirectoryPort.suspended {
            getPrincipalRoles(request, rotated.session.principalId.uuid(), rotated.session.appId.uuid())
        }.getOrElse { emptyList() } + if (isAnonymous) config.anonymousRoles else emptyList())
            .normalizedAuthNames()
        val scopes = (permissions + if (isAnonymous) config.anonymousScopes else emptyList())
            .normalizedAuthNames()
        val accessToken = jwtMinterPort {
            mintAccessToken(
                principalId = rotated.session.principalId,
                appId = rotated.session.appId,
                scopes = scopes,
                roles = roles,
                permissions = permissions,
                sessionId = rotated.session.id,
                identityId = principal?.identityId,
                tenantId = rotated.session.tenantId,
                contextId = rotated.session.contextId,
                principalKind = principalKind,
                credentialType = credentialType,
            )
        }

        auditPort.suspended {
            auditSuccess(
                request = request,
                eventType = AuthAuditEventType.TOKEN_REFRESH,
                subjectPrincipalId = rotated.session.principalId,
                appId = rotated.session.appId,
                tenantId = rotated.session.tenantId,
                contextId = rotated.session.contextId,
                sessionId = rotated.session.id,
                credentialType = AuthCredentialType.REFRESH_TOKEN.wireName,
                grantType = AuthGrantType.REFRESH_TOKEN.wireName,
                audience = rotated.session.appId,
                scopes = scopes,
            )
        }

        return RefreshResponse(
            tokenSet = TokenSet(
                accessToken = accessToken,
                refreshToken = rotated.rawRefreshToken,
                expiresInSeconds = AuthDefaults.ACCESS_TOKEN_TTL_SECONDS,
                sessionId = rotated.session.id,
                scopes = scopes,
            ),
        )
    }

    override suspend fun logout(request: LogoutRequest): LogoutResponse {
        val refreshToken = request.refreshToken.trim().ifEmpty { request.bearerToken().orEmpty() }
        val ok = refreshToken.isNotEmpty() && sessionLifecyclePort { revokeByRefreshToken(refreshToken) }
        auditPort.suspended {
            if (ok) {
                auditSuccess(
                    request = request,
                    eventType = AuthAuditEventType.TOKEN_REVOKE,
                    credentialType = AuthCredentialType.REFRESH_TOKEN.wireName,
                    grantType = AuthGrantType.LOGOUT.wireName,
                )
            } else {
                auditFailure(
                    request = request,
                    credentialType = AuthCredentialType.REFRESH_TOKEN.wireName,
                    grantType = AuthGrantType.LOGOUT.wireName,
                    reason = if (refreshToken.isEmpty()) "missing_refresh_token" else "invalid_refresh_token",
                )
            }
        }
        return LogoutResponse(success = ok, statusCode = if (ok) StatusCode.OK else StatusCode.UNAUTHORIZED)
    }

    override suspend fun me(request: MeRequest): MeResponse {
        val bearer = request.bearerToken()
            ?: return MeResponse(statusCode = StatusCode.UNAUTHORIZED)
        val claims = jwtVerifierPort { verifyReaktorSignature(bearer) }.getOrNull()
            ?: return MeResponse(statusCode = StatusCode.UNAUTHORIZED)

        return MeResponse(
            principalId = claims.subject,
            principalKind = claims.authPrincipalKind().name,
            appId = claims.authAppId(),
            audience = claims.audience.firstOrNull(),
            sessionId = claims.authSessionId(),
            scopes = claims.authScopes(),
            roles = claims.authRoles(),
            permissions = (claims.authPermissions() + claims.authScopes()).distinct(),
            actorId = claims.authActorSubject(),
            context = claims.toAuthContext().toSnapshot(),
        )
    }

    override suspend fun logoutAll(request: LogoutAllRequest): LogoutAllResponse {
        val bearer = request.bearerToken()
        if (bearer == null) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    credentialType = AuthCredentialType.ACCESS_TOKEN.wireName,
                    grantType = AuthGrantType.LOGOUT_ALL.wireName,
                    reason = "missing_access_token",
                )
            }
            return LogoutAllResponse(statusCode = StatusCode.UNAUTHORIZED)
        }
        val audience = request.audience.trim()
        if (audience.isEmpty()) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    credentialType = AuthCredentialType.ACCESS_TOKEN.wireName,
                    grantType = AuthGrantType.LOGOUT_ALL.wireName,
                    reason = "missing_audience",
                )
            }
            return LogoutAllResponse(statusCode = StatusCode.UNAUTHORIZED)
        }
        val claims = jwtVerifierPort { verifyReaktorToken(bearer, listOf(audience)) }.getOrNull()
        if (claims == null) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    credentialType = AuthCredentialType.ACCESS_TOKEN.wireName,
                    grantType = AuthGrantType.LOGOUT_ALL.wireName,
                    reason = "invalid_access_token_or_audience",
                )
            }
            return LogoutAllResponse(statusCode = StatusCode.UNAUTHORIZED)
        }
        val principalId = claims.subject
        if (principalId == null) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    appId = claims.authAppId(),
                    sessionId = claims.authSessionId(),
                    credentialType = AuthCredentialType.ACCESS_TOKEN.wireName,
                    grantType = AuthGrantType.LOGOUT_ALL.wireName,
                    reason = "missing_subject",
                )
            }
            return LogoutAllResponse(statusCode = StatusCode.UNAUTHORIZED)
        }
        val revoked = sessionLifecyclePort { revokeAllForPrincipal(principalId) }
        auditPort.suspended {
            auditSuccess(
                request = request,
                eventType = AuthAuditEventType.TOKEN_REVOKE,
                subjectPrincipalId = principalId,
                appId = claims.authAppId(),
                sessionId = claims.authSessionId(),
                credentialType = AuthCredentialType.ACCESS_TOKEN.wireName,
                grantType = AuthGrantType.LOGOUT_ALL.wireName,
                scopes = claims.authScopes(),
            )
        }
        return LogoutAllResponse(revokedSessions = revoked)
    }
}
