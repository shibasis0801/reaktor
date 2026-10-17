package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.*
import dev.shibasis.reaktor.auth.kernel.*
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.service.Environment
import kotlin.time.Clock

/** The host supplies fresh session headers; reusable authority resolution owns no login UI. */
class RemoteAuthorityClient(
    private val service: AuthService,
    private val sourceAppId: String,
    private val environment: Environment,
    private val sessionHeaders: suspend () -> Map<String, String>,
) : AuthorityClient {
    private var closed = false
    override suspend fun grants(): List<AuthorityGrant> {
        check(!closed) { "Authority client is closed" }
        val response = service.authorityGrants(AuthorityGrantsRequest(sourceAppId,
            headers = sessionHeaders().toMutableMap(), environment = environment))
        check(!closed && response.statusCode == StatusCode.OK) { "Reaktor login is unavailable or has been revoked" }
        return response.grants
    }

    override suspend fun resolve(target: AuthorityTarget, permission: String): AuthorityResolution {
        check(!closed) { "Authority client is closed" }
        val response = service.authorityResolve(AuthorityResolveRequest(sourceAppId, target, permission,
            headers = sessionHeaders().toMutableMap(), environment = environment))
        check(!closed && response.statusCode == StatusCode.OK) { "The current Reaktor session cannot authorize this tenant operation" }
        val resolution = requireNotNull(response.resolution) { "The authority service returned no credential" }
        val context = resolution.context
        check(resolution.environment == environment.name && resolution.expiresAtEpochMillis > Clock.System.now().toEpochMilliseconds() &&
            context.appId == target.appId && context.audience == target.appId && context.tenantId == target.tenantId && context.contextId == target.contextId) {
            "Authority resolution does not match the requested environment, application and tenant"
        }
        check(LocalAuthorizer.authorize(context, AuthRequirement(permissions = setOf(PermissionRef(name = permission)),
            principalKinds = setOf(PrincipalKind.USER))) is AuthDecision.Allow) { "Authority resolution does not grant the requested operation" }
        return resolution
    }

    override fun close() { closed = true }
}
