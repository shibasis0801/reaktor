package dev.shibasis.reaktor.auth.runtime.nodes

import dev.shibasis.reaktor.auth.PersonalAccessToken
import dev.shibasis.reaktor.auth.jwt.JwtMinter
import dev.shibasis.reaktor.auth.runtime.ports.AuthPersonalTokens
import dev.shibasis.reaktor.auth.services.TokenInteractor
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides

class AuthPersonalTokenNode(graph: Graph) : BasicNode(graph), AuthPersonalTokens {
    val jwtMinterPort by consumes<JwtMinter>()
    val personalTokensPort by provides<AuthPersonalTokens>(this)

    val tokens: TokenInteractor by lazy {
        TokenInteractor(jwtMinterPort { this })
    }

    override suspend fun createPersonalAccessToken(
        principalId: String?,
        name: String,
        scopes: List<String>,
        contextId: String?,
        appId: String?,
        allowedAudiences: List<String>,
        expiresInDays: Int?,
    ): Pair<PersonalAccessToken, String> = tokens.createPersonalAccessToken(
        principalId = principalId,
        name = name,
        scopes = scopes,
        contextId = contextId,
        appId = appId,
        allowedAudiences = allowedAudiences,
        expiresInDays = expiresInDays,
    )

    override suspend fun verifyPersonalAccessToken(rawToken: String): PersonalAccessToken? =
        tokens.verifyPersonalAccessToken(rawToken)

    override fun createAccessToken(pat: PersonalAccessToken, audience: String, ttlSeconds: Int): String =
        tokens.createAccessToken(pat, audience, ttlSeconds)

    override suspend fun revokePersonalAccessToken(tokenId: String): Boolean =
        tokens.revokePersonalAccessToken(tokenId)
}
