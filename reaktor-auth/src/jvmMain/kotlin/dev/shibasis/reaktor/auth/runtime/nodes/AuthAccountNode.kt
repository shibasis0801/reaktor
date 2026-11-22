package dev.shibasis.reaktor.auth.runtime.nodes

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.auth.api.AccountDeletionOptions
import dev.shibasis.reaktor.auth.api.DeactivateAccountRequest
import dev.shibasis.reaktor.auth.jwt.UserAuthenticationProvider
import dev.shibasis.reaktor.auth.kernel.AuthProviderKind
import dev.shibasis.reaktor.auth.runtime.APPLE_ISSUER
import dev.shibasis.reaktor.auth.runtime.AUTH_RUNTIME_CONFIG_DEPENDENCY
import dev.shibasis.reaktor.auth.runtime.AppleTokenRevoker
import dev.shibasis.reaktor.auth.runtime.AppleTokenHttp
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
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
import dev.shibasis.reaktor.graph.di.dependency
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
    private val config by dependency<AuthRuntimeConfig>(AUTH_RUNTIME_CONFIG_DEPENDENCY)
    private val appleRevoker by lazy {
        AppleTokenRevoker(config.appleRevocationClients, verifyIdentity = { token, clientId ->
            val claims = jwtVerifierPort {
                getProcessor(UserAuthenticationProvider.Apple(listOf(clientId))).process(token, null)
            }
            claims.subject?.takeIf {
                claims.issuer == APPLE_ISSUER && clientId in claims.audience &&
                    claims.expirationTime?.after(java.util.Date()) == true
            }
        }, post = AppleTokenHttp(config.appleRevocationHttpClient)::post)
    }

    override suspend fun deletionOptions(request: DeactivateAccountRequest): AccountDeletionOptions {
        val principalId = principalId(request) ?: return AccountDeletionOptions(statusCode = StatusCode.UNAUTHORIZED)
        val accounts = principalDirectoryPort.suspended { providerAccounts(request, principalId.uuid()) }
            .getOrElse { return AccountDeletionOptions(statusCode = StatusCode.INTERNAL_SERVER_ERROR) }
        return AccountDeletionOptions(appleLinked = accounts.any { it.provider == AuthProviderKind.APPLE && it.issuer == APPLE_ISSUER })
    }

    private fun principalId(request: DeactivateAccountRequest): String? {
        val bearer = request.bearerToken() ?: return null
        val audience = request.audience.trim().takeIf(String::isNotEmpty) ?: return null
        return jwtVerifierPort { verifyReaktorToken(bearer, listOf(audience)) }.getOrNull()?.subject
    }

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

        val accounts = principalDirectoryPort.suspended { providerAccounts(request, principalId.uuid()) }.getOrDefault(emptyList())
        if (accounts.any { it.provider == AuthProviderKind.APPLE && it.issuer == APPLE_ISSUER }) {
            val revoked = try {
                withTimeoutOrNull(15_000) { appleRevoker.revoke(request, accounts) } ?: false
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                false
            }
            if (!revoked) Logger.w { "Apple token revocation failed: count=1" }
        }
        sessionLifecyclePort { revokeAllForPrincipal(principalId, request.environment) }
        val deactivated = principalDirectoryPort.suspended {
            softDeleteAccount(request, principalId.uuid())
        }.getOrDefault(false)

        return DeactivateAccountResponse(deactivated = deactivated)
    }
}
