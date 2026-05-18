package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.PersonalAccessToken
import dev.shibasis.reaktor.auth.services.LoginInteractor
import dev.shibasis.reaktor.auth.services.TokenInteractor
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.graph.service.Request
import dev.shibasis.reaktor.graph.service.PostHandler
import kotlinx.serialization.decodeFromString
import org.springframework.stereotype.Component

@Component
class AuthServer(
    private val loginInteractor: LoginInteractor,
    private val tokenInteractor: TokenInteractor
): AuthService() {
    private companion object {
        const val MIN_PAT_ACCESS_TOKEN_TTL_SECONDS = 60
        const val MAX_PAT_ACCESS_TOKEN_TTL_SECONDS = 15 * 60
    }

    override val login = PostHandler("/sign-in") {
        loginInteractor.login(it)
    }

    override val mintPat = PostHandler<MintPatRequest, MintPatResponse>("/pat/mint") { req ->
        if (!req.canMintPat()) {
            return@PostHandler MintPatResponse("", statusCode = StatusCode.UNAUTHORIZED)
        }

        val name = req.name.trim().take(100).ifBlank { "mcp-service-token" }
        val scopes = req.scopes
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("mcp:read") }
        val (_, rawToken) = tokenInteractor.createPersonalAccessToken(null, name, scopes)
        MintPatResponse(rawToken)
    }

    override val verifyPat = PostHandler<VerifyPatRequest, VerifyPatResponse>("/pat/verify") { req ->
        val pat = tokenInteractor.verifyPersonalAccessToken(req.rawToken)
        VerifyPatResponse(
            isValid = pat != null,
            tokenId = pat?.id,
            name = pat?.name,
            scopes = pat?.scopeList().orEmpty(),
            statusCode = if (pat == null) StatusCode.UNAUTHORIZED else StatusCode.OK,
        )
    }

    override val token = PostHandler<TokenRequest, TokenResponse>("/token") { req ->
        if (req.grantType != "pat" && req.grantType != "personal_access_token") {
            return@PostHandler TokenResponse("", statusCode = StatusCode.BAD_REQUEST)
        }

        val rawToken = req.bearerToken()
            ?: req.rawToken.trim().takeIf { it.isNotEmpty() }
            ?: return@PostHandler TokenResponse("", statusCode = StatusCode.UNAUTHORIZED)
        val pat = tokenInteractor.verifyPersonalAccessToken(rawToken)
            ?: return@PostHandler TokenResponse("", statusCode = StatusCode.UNAUTHORIZED)

        val ttlSeconds = req.ttlSeconds.coerceIn(
            MIN_PAT_ACCESS_TOKEN_TTL_SECONDS,
            MAX_PAT_ACCESS_TOKEN_TTL_SECONDS,
        )
        val token = tokenInteractor.createAccessToken(
            pat = pat,
            audience = req.audience.trim().ifBlank { "manna-mcp" },
            ttlSeconds = ttlSeconds,
        )

        TokenResponse(
            accessToken = token,
            expiresInSeconds = ttlSeconds,
            tokenId = pat.id,
            scopes = pat.scopeList(),
        )
    }

    override val exchangePat = PostHandler<ExchangePatRequest, ExchangePatResponse>("/pat/exchange") { req ->
        val rawToken = req.bearerToken()
            ?: req.rawToken.trim().takeIf { it.isNotEmpty() }
            ?: return@PostHandler ExchangePatResponse("", statusCode = StatusCode.UNAUTHORIZED)
        val pat = tokenInteractor.verifyPersonalAccessToken(rawToken)
            ?: return@PostHandler ExchangePatResponse("", statusCode = StatusCode.UNAUTHORIZED)

        val ttlSeconds = req.ttlSeconds.coerceIn(
            MIN_PAT_ACCESS_TOKEN_TTL_SECONDS,
            MAX_PAT_ACCESS_TOKEN_TTL_SECONDS,
        )
        val token = tokenInteractor.createAccessToken(
            pat = pat,
            audience = req.audience.trim().ifBlank { "manna-mcp" },
            ttlSeconds = ttlSeconds,
        )

        ExchangePatResponse(
            accessToken = token,
            expiresInSeconds = ttlSeconds,
            tokenId = pat.id,
            scopes = pat.scopeList(),
        )
    }

    private suspend fun Request.canMintPat(): Boolean {
        val token = bearerToken() ?: return false
        val bootstrapToken = System.getenv("REAKTOR_PAT_BOOTSTRAP_TOKEN")
            ?.takeIf { it.isNotBlank() }

        if (bootstrapToken != null && token == bootstrapToken) return true

        val pat = tokenInteractor.verifyPersonalAccessToken(token) ?: return false
        return pat.scopeList().any { it == "*" || it == "auth:*" || it == "auth:pat:mint" }
    }

    private fun Request.bearerToken(): String? {
        val header = headers["Authorization"] ?: headers["authorization"] ?: return null
        val prefix = "Bearer "
        if (!header.startsWith(prefix, ignoreCase = true)) return null
        return header.drop(prefix.length).trim().takeIf { it.isNotEmpty() }
    }

    private fun PersonalAccessToken.scopeList(): List<String> =
        runCatching { json.decodeFromString<List<String>>(scopes) }
            .getOrDefault(emptyList())
}
