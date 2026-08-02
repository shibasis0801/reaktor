package dev.shibasis.reaktor.auth.services

import dev.shibasis.reaktor.auth.api.LoginRequest
import dev.shibasis.reaktor.auth.api.LoginResponse
import dev.shibasis.reaktor.auth.api.AnonymousAuthRequest
import dev.shibasis.reaktor.auth.api.TokenSet
import dev.shibasis.reaktor.auth.db.AppRepository
import dev.shibasis.reaktor.auth.db.AuthRepository
import dev.shibasis.reaktor.auth.jwt.JwtMinter
import dev.shibasis.reaktor.auth.jwt.JwtVerifier
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
import dev.shibasis.reaktor.auth.toAuthProviderKind
import dev.shibasis.reaktor.auth.toSnapshot
import dev.shibasis.reaktor.core.utils.info
import dev.shibasis.reaktor.core.utils.invoke
import dev.shibasis.reaktor.core.utils.logger
import dev.shibasis.reaktor.core.utils.read
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

fun String.uuid(): UUID = UUID.fromString(this)

open class LoginInteractor(
    private val authRepository: AuthRepository,
    private val appRepository: AppRepository,
    private val jwtVerifier: JwtVerifier,
    private val jwtMinter: JwtMinter,
    private val sessionRefreshService: SessionRefreshService,
    private val anonymousRoles: List<String> = listOf("guest"),
    private val anonymousScopes: List<String> = emptyList(),
    private val anonymousPermissions: List<String> = emptyList(),
) {
    private val logger = "Reaktor:LoginService".logger()

    open suspend fun anonymous(request: AnonymousAuthRequest): LoginResponse {
        val app = appRepository.findById(request, request.appId.uuid()).read()
            ?: return LoginResponse.Failure.InvalidAppId

        val resolved = authRepository.resolveAnonymousLogin(
            request = request,
            app = app,
            profile = request.profile,
            tenantId = request.tenantHint,
            contextId = request.contextHint,
        ).getOrElse {
            return LoginResponse.Failure.ServerError(it.message ?: "Unable to resolve anonymous principal")
        }

        if (
            resolved.principal.status != PrincipalStatus.ACTIVE ||
            resolved.membership.status != MembershipStatus.ACTIVE
        ) {
            return LoginResponse.Failure.PrincipalUnavailable
        }

        val permissions = (resolved.permissions + anonymousPermissions).normalizedAuthNames()
        val roles = (resolved.roles + anonymousRoles).normalizedAuthNames()
        val scopes = (permissions + anonymousScopes).normalizedAuthNames()
        val session = sessionRefreshService.createSession(
            principalId = resolved.principal.id,
            appId = app.id,
            tenantId = resolved.membership.tenantId,
            contextId = resolved.membership.contextId,
            // Tier-explicit: the session must land in the same database the principal was
            // resolved from. Without this it uses Exposed's default (prod), so a dev login
            // writes a dev app-id session into prod and trips session_app_id_fkey.
            database = authRepository.adapter.databaseFor(request.environment),
        )
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
        val accessToken = jwtMinter.mintAccessToken(
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
            credentialType = "anonymous",
        )

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

    open suspend fun login(request: LoginRequest): LoginResponse {
        logger { request }

        val app = appRepository.findById(request, request.appId.uuid()).read()
            ?: return LoginResponse.Failure.InvalidAppId
        logger { app }

        val authenticated = jwtVerifier(request).read()
            ?: return LoginResponse.Failure.InvalidIdToken
        logger.info { authenticated }

        val resolved = authRepository.resolveExternalLogin(
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
        ).getOrElse {
            return LoginResponse.Failure.ServerError(it.message ?: "Unable to resolve auth principal")
        }

        if (
            resolved.identity.status != IdentityStatus.ACTIVE ||
            resolved.principal.status != PrincipalStatus.ACTIVE ||
            resolved.membership.status != MembershipStatus.ACTIVE
        ) {
            return LoginResponse.Failure.PrincipalUnavailable
        }

        val permissions = resolved.permissions.distinct().sorted()
        val roles = resolved.roles.distinct().sorted()
        val session = sessionRefreshService.createSession(
            principalId = resolved.principal.id,
            appId = app.id,
            tenantId = resolved.membership.tenantId,
            contextId = resolved.membership.contextId,
            // Tier-explicit: the session must land in the same database the principal was
            // resolved from. Without this it uses Exposed's default (prod), so a dev login
            // writes a dev app-id session into prod and trips session_app_id_fkey.
            database = authRepository.adapter.databaseFor(request.environment),
        )
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
        val accessToken = jwtMinter.mintAccessToken(
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

private fun List<String>.normalizedAuthNames(): List<String> =
    map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()
