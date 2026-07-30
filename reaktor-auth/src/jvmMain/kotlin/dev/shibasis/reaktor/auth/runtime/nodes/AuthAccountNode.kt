package dev.shibasis.reaktor.auth.runtime.nodes

import dev.shibasis.reaktor.auth.api.DeactivateAccountRequest
import dev.shibasis.reaktor.auth.api.DeactivateAccountResponse
import dev.shibasis.reaktor.auth.jwt.JwtVerifier
import dev.shibasis.reaktor.auth.runtime.AuthAccount
import dev.shibasis.reaktor.auth.runtime.bearerToken
import dev.shibasis.reaktor.auth.runtime.ports.AuthPrincipalDirectory
import dev.shibasis.reaktor.auth.runtime.ports.AuthSessionLifecycle
import dev.shibasis.reaktor.auth.services.uuid
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides

/**
 * Account lifecycle capability. `deactivate` performs the grace-period soft
 * delete: authenticate the caller from their access token, revoke every session
 * + refresh token, then mark the principal + identity SOFT_DELETED (stamping
 * deactivated_at). The hard purge (Phase 3) runs later off that timestamp.
 */
class AuthAccountNode(graph: Graph) : BasicNode(graph), AuthAccount {
    private val sessionLifecyclePort by consumes<AuthSessionLifecycle>()
    private val principalDirectoryPort by consumes<AuthPrincipalDirectory>()
    private val jwtVerifierPort by consumes<JwtVerifier>()
    val accountPort by provides<AuthAccount>(this)

    override suspend fun deactivate(request: DeactivateAccountRequest): DeactivateAccountResponse {
        val bearer = request.bearerToken()
            ?: return DeactivateAccountResponse(statusCode = StatusCode.UNAUTHORIZED)
        val audience = request.audience.trim()
        if (audience.isEmpty()) {
            return DeactivateAccountResponse(statusCode = StatusCode.UNAUTHORIZED)
        }
        val claims = jwtVerifierPort { verifyReaktorToken(bearer, listOf(audience)) }.getOrNull()
            ?: return DeactivateAccountResponse(statusCode = StatusCode.UNAUTHORIZED)
        val principalId = claims.subject
            ?: return DeactivateAccountResponse(statusCode = StatusCode.UNAUTHORIZED)

        // Self-only by construction: principalId comes from the caller's own token.
        sessionLifecyclePort { revokeAllForPrincipal(principalId) }
        val deactivated = principalDirectoryPort {
            softDeleteAccount(request, principalId.uuid())
        }.getOrDefault(false)

        return DeactivateAccountResponse(deactivated = deactivated)
    }
}
