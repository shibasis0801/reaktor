package dev.shibasis.reaktor.auth.runtime.nodes

import dev.shibasis.reaktor.auth.api.MintPatRequest
import dev.shibasis.reaktor.auth.api.MintPatResponse
import dev.shibasis.reaktor.auth.api.VerifyPatRequest
import dev.shibasis.reaktor.auth.api.VerifyPatResponse
import dev.shibasis.reaktor.auth.AuthAuditEventType
import dev.shibasis.reaktor.auth.AuthCredentialType
import dev.shibasis.reaktor.auth.AuthGrantType
import dev.shibasis.reaktor.auth.runtime.AUTH_RUNTIME_CONFIG_DEPENDENCY
import dev.shibasis.reaktor.auth.runtime.AuthPat
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeConfig
import dev.shibasis.reaktor.auth.runtime.DEFAULT_TOKEN_AUDIENCE
import dev.shibasis.reaktor.auth.runtime.bearerToken
import dev.shibasis.reaktor.auth.runtime.canMintPat
import dev.shibasis.reaktor.auth.runtime.normalizedScopes
import dev.shibasis.reaktor.auth.runtime.ports.AuthAuditSink
import dev.shibasis.reaktor.auth.runtime.ports.AuthPersonalTokens
import dev.shibasis.reaktor.auth.runtime.scopeList
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.di.dependency
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides

class AuthPatNode(graph: Graph) : BasicNode(graph), AuthPat {
    private val config by dependency<AuthRuntimeConfig>(AUTH_RUNTIME_CONFIG_DEPENDENCY)
    val personalTokensPort by consumes<AuthPersonalTokens>()
    val auditPort by consumes<AuthAuditSink>()
    val patPort by provides<AuthPat>(this)

    override suspend fun mint(request: MintPatRequest): MintPatResponse {
        if (!request.canMintPat()) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    subjectPrincipalId = request.principalId,
                    appId = request.appId,
                    credentialType = AuthCredentialType.PERSONAL_ACCESS_TOKEN.wireName,
                    grantType = AuthGrantType.MINT_PAT.wireName,
                    reason = "unauthorized_pat_mint",
                )
            }
            return MintPatResponse("", statusCode = StatusCode.UNAUTHORIZED)
        }

        val name = request.name.trim().take(100).ifBlank { "mcp-service-token" }
        val scopes = request.scopes.normalizedScopes(default = listOf("mcp:read"))
        val audiences = request.allowedAudiences.normalizedScopes(default = listOf(DEFAULT_TOKEN_AUDIENCE))
        val (pat, rawToken) = personalTokensPort.suspended {
            createPersonalAccessToken(
                principalId = request.principalId,
                name = name,
                scopes = scopes,
                appId = request.appId,
                allowedAudiences = audiences,
                expiresInDays = request.expiresInDays,
            )
        }
        auditPort.suspended {
            auditSuccess(
                request = request,
                eventType = AuthAuditEventType.TOKEN_MINT,
                subjectPrincipalId = request.principalId,
                appId = request.appId,
                credentialType = AuthCredentialType.PERSONAL_ACCESS_TOKEN.wireName,
                grantType = AuthGrantType.MINT_PAT.wireName,
                tokenId = pat.id,
                audience = audiences.joinToString(" "),
                scopes = scopes,
            )
        }
        return MintPatResponse(rawToken)
    }

    override suspend fun verify(request: VerifyPatRequest): VerifyPatResponse {
        val pat = personalTokensPort.suspended { verifyPersonalAccessToken(request.rawToken) }
        if (pat == null) {
            auditPort.suspended {
                auditFailure(
                    request = request,
                    credentialType = AuthCredentialType.PERSONAL_ACCESS_TOKEN.wireName,
                    grantType = AuthGrantType.VERIFY_PAT.wireName,
                    reason = "invalid_pat",
                )
            }
        }
        return VerifyPatResponse(
            isValid = pat != null,
            tokenId = pat?.id,
            name = pat?.name,
            scopes = pat?.scopeList().orEmpty(),
            statusCode = if (pat == null) StatusCode.UNAUTHORIZED else StatusCode.OK,
        )
    }

    private suspend fun MintPatRequest.canMintPat(): Boolean {
        val token = bearerToken() ?: return false
        val bootstrapToken = config.patBootstrapToken?.takeIf { it.isNotBlank() }

        if (bootstrapToken != null && token == bootstrapToken) return true

        return personalTokensPort.suspended { verifyPersonalAccessToken(token) }?.canMintPat() == true
    }
}
