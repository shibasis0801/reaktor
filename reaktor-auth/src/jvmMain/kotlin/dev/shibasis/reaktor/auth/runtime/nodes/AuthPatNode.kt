package dev.shibasis.reaktor.auth.runtime.nodes

import dev.shibasis.reaktor.auth.api.MintPatRequest
import dev.shibasis.reaktor.auth.api.MintPatResponse
import dev.shibasis.reaktor.auth.api.VerifyPatRequest
import dev.shibasis.reaktor.auth.api.VerifyPatResponse
import dev.shibasis.reaktor.auth.AuthAuditEventType
import dev.shibasis.reaktor.auth.PersonalAccessToken
import dev.shibasis.reaktor.auth.AuthCredentialType
import dev.shibasis.reaktor.auth.AuthGrantType
import dev.shibasis.reaktor.auth.runtime.AUTH_RUNTIME_CONFIG_DEPENDENCY
import dev.shibasis.reaktor.auth.runtime.AuthPat
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeConfig
import dev.shibasis.reaktor.auth.runtime.DEFAULT_TOKEN_AUDIENCE
import dev.shibasis.reaktor.auth.runtime.audienceAllowed
import dev.shibasis.reaktor.auth.runtime.bearerToken
import dev.shibasis.reaktor.auth.runtime.canMintPat
import dev.shibasis.reaktor.auth.runtime.normalizedScopes
import dev.shibasis.reaktor.auth.runtime.ports.AuthAuditSink
import dev.shibasis.reaktor.auth.runtime.ports.AuthPersonalTokens
import dev.shibasis.reaktor.auth.runtime.reach
import dev.shibasis.reaktor.auth.runtime.scopeList
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.di.dependency
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AuthPatNode(graph: Graph) : BasicNode(graph), AuthPat {
    private val config by dependency<AuthRuntimeConfig>(AUTH_RUNTIME_CONFIG_DEPENDENCY)
    val personalTokensPort by consumes<AuthPersonalTokens>()
    val auditPort by consumes<AuthAuditSink>()
    val patPort by provides<AuthPat>(this)

    override suspend fun mint(request: MintPatRequest): MintPatResponse {
        val issuer = request.issuer() ?: return refuse(request, "unauthorized_pat_mint", StatusCode.UNAUTHORIZED)
        val name = request.name.trim().take(100).ifBlank { "mcp-service-token" }
        val scopes = request.scopes.normalizedScopes(default = listOf("mcp:read"))
        val audiences = request.allowedAudiences.normalizedScopes(default = listOf(DEFAULT_TOKEN_AUDIENCE))
        val holder = (issuer as? PatIssuer.Holder)?.pat
        holder?.let { request.overreach(it, scopes, audiences) }?.let { return overreach(request, it, holder) }
        val appId = request.appId ?: holder?.appId
        val (pat, rawToken) = personalTokensPort.suspended {
            createPersonalAccessToken(
                principalId = request.principalId,
                name = name,
                scopes = scopes,
                contextId = holder?.contextId,
                appId = appId,
                allowedAudiences = audiences,
                expiresInDays = request.expiresInDays,
            )
        }
        auditPort.suspended {
            auditSuccess(
                request = request,
                eventType = AuthAuditEventType.TOKEN_MINT,
                subjectPrincipalId = request.principalId,
                appId = appId,
                contextId = holder?.contextId,
                credentialType = AuthCredentialType.PERSONAL_ACCESS_TOKEN.wireName,
                grantType = AuthGrantType.MINT_PAT.wireName,
                tokenId = pat.id,
                audience = audiences.joinToString(" "),
                scopes = scopes,
                data = issuer.audited,
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

    private suspend fun refuse(request: MintPatRequest, reason: String, status: StatusCode): MintPatResponse {
        auditPort.suspended {
            auditFailure(
                request = request,
                subjectPrincipalId = request.principalId,
                appId = request.appId,
                credentialType = AuthCredentialType.PERSONAL_ACCESS_TOKEN.wireName,
                grantType = AuthGrantType.MINT_PAT.wireName,
                reason = reason,
                scopes = request.scopes,
            )
        }
        return MintPatResponse("", statusCode = status)
    }

    private suspend fun overreach(request: MintPatRequest, reason: String, holder: PersonalAccessToken): MintPatResponse {
        auditPort.suspended {
            auditFailure(
                request = request,
                actorPrincipalId = holder.principalId,
                appId = holder.appId,
                contextId = holder.contextId,
                credentialType = AuthCredentialType.PERSONAL_ACCESS_TOKEN.wireName,
                grantType = AuthGrantType.MINT_PAT.wireName,
                tokenId = holder.id,
                reason = reason,
                scopes = request.scopes,
                data = buildJsonObject {
                    put("issuer", PatIssuer.Holder.Kind)
                    put("requestedPrincipal", request.principalId)
                    put("requestedApp", request.appId)
                },
            )
        }
        return MintPatResponse("", statusCode = StatusCode.FORBIDDEN)
    }

    private suspend fun MintPatRequest.issuer(): PatIssuer? {
        val token = bearerToken() ?: return null
        val bootstrap = config.patBootstrapToken?.takeIf { it.isNotBlank() }
        if (bootstrap != null && MessageDigest.isEqual(token.encodeToByteArray(), bootstrap.encodeToByteArray())) return PatIssuer.Bootstrap
        return personalTokensPort.suspended { verifyPersonalAccessToken(token) }?.takeIf { it.canMintPat() }?.let(PatIssuer::Holder)
    }

    private fun MintPatRequest.overreach(issuer: PersonalAccessToken, scopes: List<String>, audiences: List<String>): String? {
        val outlives = issuer.expiresAt?.let { limit -> expiresInDays?.let { Clock.System.now() + it.days }?.let { it > limit } ?: true } ?: false
        return when {
            principalId != issuer.principalId -> "pat_target_not_self"
            appId != null && appId != issuer.appId -> "pat_app_not_issuers"
            !scopes.all { issuer.reach.covers(it) } -> "pat_scope_exceeds_issuer"
            !audiences.all { issuer.audienceAllowed(it) } -> "pat_audience_exceeds_issuer"
            outlives -> "pat_outlives_issuer"
            else -> null
        }
    }
}

private sealed interface PatIssuer {
    val kind: String
    val audited: JsonObject get() = buildJsonObject { put("issuer", kind) }

    data object Bootstrap : PatIssuer {
        override val kind = "bootstrap"
    }

    data class Holder(val pat: PersonalAccessToken) : PatIssuer {
        override val kind = Kind

        companion object {
            const val Kind = "holder"
        }
    }
}
