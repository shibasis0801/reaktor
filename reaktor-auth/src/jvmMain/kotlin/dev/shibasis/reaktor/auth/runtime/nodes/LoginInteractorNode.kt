package dev.shibasis.reaktor.auth.runtime.nodes

import dev.shibasis.reaktor.auth.api.AnonymousAuthRequest
import dev.shibasis.reaktor.auth.api.LoginRequest
import dev.shibasis.reaktor.auth.api.LoginResponse
import dev.shibasis.reaktor.auth.api.TokenSet
import dev.shibasis.reaktor.auth.AuthAuditEventType
import dev.shibasis.reaktor.auth.AuthCredentialType
import dev.shibasis.reaktor.auth.AuthGrantType
import dev.shibasis.reaktor.auth.jwt.JwtMinter
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthDefaults
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.IdentityStatus
import dev.shibasis.reaktor.auth.kernel.MembershipStatus
import dev.shibasis.reaktor.auth.kernel.PermissionRef
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.auth.kernel.PrincipalStatus
import dev.shibasis.reaktor.auth.kernel.RoleRef
import dev.shibasis.reaktor.auth.runtime.AUTH_RUNTIME_CONFIG_DEPENDENCY
import dev.shibasis.reaktor.auth.runtime.AuthLogin
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeConfig
import dev.shibasis.reaktor.auth.runtime.normalizedAuthNames
import dev.shibasis.reaktor.auth.runtime.ports.AuthAppCatalog
import dev.shibasis.reaktor.auth.runtime.ports.AuthAuditSink
import dev.shibasis.reaktor.auth.runtime.ports.AuthExternalIdentityVerifier
import dev.shibasis.reaktor.auth.runtime.ports.AuthPrincipalDirectory
import dev.shibasis.reaktor.auth.runtime.ports.AuthSessionLifecycle
import dev.shibasis.reaktor.auth.services.uuid
import dev.shibasis.reaktor.auth.toAuthProviderKind
import dev.shibasis.reaktor.auth.toSnapshot
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.di.dependency
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class LoginInteractorNode(graph: Graph) : BasicNode(graph), AuthLogin {
    private val config by dependency<AuthRuntimeConfig>(AUTH_RUNTIME_CONFIG_DEPENDENCY)
    val appCatalogPort by consumes<AuthAppCatalog>()
    val principalDirectoryPort by consumes<AuthPrincipalDirectory>()
    val externalIdentityVerifierPort by consumes<AuthExternalIdentityVerifier>()
    val jwtMinterPort by consumes<JwtMinter>()
    val sessionLifecyclePort by consumes<AuthSessionLifecycle>()
    val auditPort by consumes<AuthAuditSink>()
    val loginPort by provides<AuthLogin>(this)

    override suspend fun anonymous(request: AnonymousAuthRequest): LoginResponse {
        val requestedAppId = runCatching { request.appId.uuid() }.getOrNull()
        if (requestedAppId == null) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    credentialType = AuthCredentialType.ANONYMOUS.wireName,
                    grantType = AuthGrantType.ANONYMOUS.wireName,
                    reason = "invalid_app_id",
                )
            }
            return LoginResponse.Failure.InvalidAppId
        }

        val app = appCatalogPort.suspended { findById(request, requestedAppId) }
            .getOrNull()
        if (app == null) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    credentialType = AuthCredentialType.ANONYMOUS.wireName,
                    grantType = AuthGrantType.ANONYMOUS.wireName,
                    reason = "invalid_app_id",
                )
            }
            return LoginResponse.Failure.InvalidAppId
        }

        val resolved = principalDirectoryPort.suspended {
            resolveAnonymousLogin(
                request = request,
                app = app,
                profile = request.profile,
                tenantId = request.tenantHint,
                contextId = request.contextHint,
            )
        }.getOrElse {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    appId = app.id,
                    credentialType = AuthCredentialType.ANONYMOUS.wireName,
                    grantType = AuthGrantType.ANONYMOUS.wireName,
                    reason = "principal_resolution_failed",
                )
            }
            return LoginResponse.Failure.ServerError(it.message ?: "Unable to resolve anonymous principal")
        }

        if (
            resolved.principal.status != PrincipalStatus.ACTIVE ||
            resolved.membership.status != MembershipStatus.ACTIVE
        ) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    subjectPrincipalId = resolved.principal.id,
                    appId = app.id,
                    tenantId = resolved.membership.tenantId,
                    contextId = resolved.membership.contextId,
                    credentialType = AuthCredentialType.ANONYMOUS.wireName,
                    grantType = AuthGrantType.ANONYMOUS.wireName,
                    reason = "principal_unavailable",
                )
            }
            return LoginResponse.Failure.PrincipalUnavailable
        }

        val permissions = (resolved.permissions + config.anonymousPermissions)
            .normalizedAuthNames()
        val roles = (resolved.roles + config.anonymousRoles)
            .normalizedAuthNames()
        val scopes = (permissions + config.anonymousScopes)
            .normalizedAuthNames()
        val session = sessionLifecyclePort {
            createSession(
                principalId = resolved.principal.id,
                appId = app.id,
                tenantId = resolved.membership.tenantId,
                contextId = resolved.membership.contextId,
                environment = request.environment,
            )
        }
        val context = AuthContext(
            principal = PrincipalRef(resolved.principal.id, PrincipalKind.USER),
            identityId = null,
            appId = app.id,
            tenantId = resolved.membership.tenantId,
            contextId = resolved.membership.contextId,
            sessionId = session.sessionId,
            issuer = AuthDefaults.ISSUER,
            audience = app.id,
            scopes = scopes.map { PermissionRef(name = it) }.toSet(),
            roles = roles.map { RoleRef(name = it) }.toSet(),
            permissions = permissions.map { PermissionRef(name = it) }.toSet(),
            method = AuthMethod.ANONYMOUS,
        )
        val accessToken = jwtMinterPort {
            mintAccessToken(
                principalId = resolved.principal.id,
                appId = app.id,
                scopes = scopes,
                roles = roles,
                permissions = permissions,
                sessionId = session.sessionId,
                identityId = null,
                tenantId = resolved.membership.tenantId,
                contextId = resolved.membership.contextId,
                principalKind = PrincipalKind.USER,
                credentialType = AuthCredentialType.ANONYMOUS.wireName,
            )
        }

        auditPort.suspended {
            auditSuccess(
                request = request,
                eventType = AuthAuditEventType.TOKEN_MINT,
                subjectPrincipalId = resolved.principal.id,
                appId = app.id,
                tenantId = resolved.membership.tenantId,
                contextId = resolved.membership.contextId,
                sessionId = session.sessionId,
                credentialType = AuthCredentialType.ANONYMOUS.wireName,
                grantType = AuthGrantType.ANONYMOUS.wireName,
                audience = app.id,
                scopes = scopes,
            )
        }

        return LoginResponse.Success(
            context = context.toSnapshot(),
            profile = resolved.membership.profile,
            tokenSet = TokenSet(
                accessToken = accessToken,
                refreshToken = session.rawRefreshToken,
                expiresInSeconds = AuthDefaults.ACCESS_TOKEN_TTL_SECONDS,
                sessionId = session.sessionId,
                audience = app.id,
                scopes = scopes,
            ),
        )
    }

    override suspend fun login(request: LoginRequest): LoginResponse {
        val requestedAppId = runCatching { request.appId.uuid() }.getOrNull()
        if (requestedAppId == null) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    credentialType = AuthCredentialType.EXTERNAL_LOGIN.wireName,
                    grantType = AuthGrantType.LOGIN.wireName,
                    reason = "invalid_app_id",
                )
            }
            return LoginResponse.Failure.InvalidAppId
        }

        val app = appCatalogPort.suspended { findById(request, requestedAppId) }
            .getOrNull()
        if (app == null) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    credentialType = AuthCredentialType.EXTERNAL_LOGIN.wireName,
                    grantType = AuthGrantType.LOGIN.wireName,
                    reason = "invalid_app_id",
                )
            }
            return LoginResponse.Failure.InvalidAppId
        }

        val authenticated = externalIdentityVerifierPort.suspended { verify(request) }
            .getOrNull()
        if (authenticated == null) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    appId = app.id,
                    credentialType = AuthCredentialType.EXTERNAL_LOGIN.wireName,
                    grantType = AuthGrantType.LOGIN.wireName,
                    reason = "invalid_id_token",
                )
            }
            return LoginResponse.Failure.InvalidIdToken
        }

        val resolved = principalDirectoryPort.suspended {
            resolveExternalLogin(
                request = request,
                app = app,
                provider = request.provider.toAuthProviderKind(),
                issuer = authenticated.issuer,
                subject = authenticated.subject,
                email = authenticated.email,
                emailVerified = authenticated.emailVerified,
                profile = request.resolvedProfile(),
                tenantId = request.tenantHint,
                contextId = request.contextHint,
            )
        }.getOrElse {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    appId = app.id,
                    credentialType = AuthCredentialType.EXTERNAL_LOGIN.wireName,
                    grantType = AuthGrantType.LOGIN.wireName,
                    reason = "principal_resolution_failed",
                )
            }
            return LoginResponse.Failure.ServerError(it.message ?: "Unable to resolve auth principal")
        }

        if (
            resolved.identity.status != IdentityStatus.ACTIVE ||
            resolved.principal.status != PrincipalStatus.ACTIVE ||
            resolved.membership.status != MembershipStatus.ACTIVE
        ) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    subjectPrincipalId = resolved.principal.id,
                    appId = app.id,
                    tenantId = resolved.membership.tenantId,
                    contextId = resolved.membership.contextId,
                    credentialType = AuthCredentialType.EXTERNAL_LOGIN.wireName,
                    grantType = AuthGrantType.LOGIN.wireName,
                    reason = "principal_unavailable",
                )
            }
            return LoginResponse.Failure.PrincipalUnavailable
        }

        val permissions = resolved.permissions.distinct().sorted()
        val roles = resolved.roles.distinct().sorted()
        val session = sessionLifecyclePort {
            createSession(
                principalId = resolved.principal.id,
                appId = app.id,
                tenantId = resolved.membership.tenantId,
                contextId = resolved.membership.contextId,
                environment = request.environment,
            )
        }
        val context = AuthContext(
            principal = PrincipalRef(resolved.principal.id, PrincipalKind.USER),
            identityId = resolved.identity.id,
            appId = app.id,
            tenantId = resolved.membership.tenantId,
            contextId = resolved.membership.contextId,
            sessionId = session.sessionId,
            issuer = AuthDefaults.ISSUER,
            audience = app.id,
            scopes = permissions.map { PermissionRef(name = it) }.toSet(),
            roles = roles.map { RoleRef(name = it) }.toSet(),
            permissions = permissions.map { PermissionRef(name = it) }.toSet(),
            method = AuthMethod.EXTERNAL_LOGIN,
        )
        val accessToken = jwtMinterPort {
            mintAccessToken(
                principalId = resolved.principal.id,
                appId = app.id,
                scopes = permissions,
                roles = roles,
                permissions = permissions,
                sessionId = session.sessionId,
                identityId = resolved.identity.id,
                tenantId = resolved.membership.tenantId,
                contextId = resolved.membership.contextId,
                principalKind = PrincipalKind.USER,
            )
        }

        auditPort.suspended {
            auditSuccess(
                request = request,
                eventType = AuthAuditEventType.TOKEN_MINT,
                subjectPrincipalId = resolved.principal.id,
                appId = app.id,
                tenantId = resolved.membership.tenantId,
                contextId = resolved.membership.contextId,
                sessionId = session.sessionId,
                credentialType = AuthCredentialType.EXTERNAL_LOGIN.wireName,
                grantType = AuthGrantType.LOGIN.wireName,
                audience = app.id,
                scopes = permissions,
            )
        }

        return LoginResponse.Success(
            context = context.toSnapshot(),
            profile = resolved.membership.profile,
            tokenSet = TokenSet(
                accessToken = accessToken,
                refreshToken = session.rawRefreshToken,
                expiresInSeconds = AuthDefaults.ACCESS_TOKEN_TTL_SECONDS,
                sessionId = session.sessionId,
                audience = app.id,
                scopes = permissions,
            ),
        )
    }

    private fun LoginRequest.resolvedProfile(): JsonElement {
        val profileObject = profile as? JsonObject
        val fields = linkedMapOf<String, JsonElement>()
        profileObject?.let { fields.putAll(it) }
        givenName?.takeIf { it.isNotBlank() }?.let { fields["givenName"] = JsonPrimitive(it) }
        familyName?.takeIf { it.isNotBlank() }?.let { fields["familyName"] = JsonPrimitive(it) }
        return if (fields.isEmpty()) profile else JsonObject(fields)
    }
}
