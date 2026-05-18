package dev.shibasis.reaktor.auth.services

import dev.shibasis.reaktor.auth.PersonalAccessToken
import dev.shibasis.reaktor.auth.PersonalAccessTokens
import dev.shibasis.reaktor.core.framework.EMPTY_JSON
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.auth.kernel.AuthDefaults
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock

@Component
class TokenInteractor {

    private fun hashToken(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(rawToken.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(hashBytes)
    }

    private fun generateSecureToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return "rak_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun jwtSecret(): ByteArray {
        val secret = System.getenv("REAKTOR_AUTH_JWT_SECRET")
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("REAKTOR_AUTH_JWT_SECRET is required to issue PAT access JWTs")
        return secret.toByteArray(Charsets.UTF_8)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun signJwt(input: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(jwtSecret(), "HmacSHA256"))
        return base64Url(mac.doFinal(input.toByteArray(Charsets.UTF_8)))
    }

    private fun PersonalAccessToken.scopeList(): List<String> =
        runCatching { json.decodeFromString<List<String>>(scopes) }
            .getOrDefault(emptyList())

    /**
     * Create a Personal Access Token for a user or service account.
     * Returns (PAT entity, raw token string). The raw token is shown once and never stored.
     */
    suspend fun createPersonalAccessToken(
        userId: String?,
        name: String,
        scopes: List<String>,
        contextId: String? = null
    ): Pair<PersonalAccessToken, String> = transaction {
        val rawToken = generateSecureToken()
        val tokenHash = hashToken(rawToken)
        val id = UUID.randomUUID().toString()

        val pat = PersonalAccessToken(
            id = id,
            userId = userId,
            name = name,
            tokenHash = tokenHash,
            scopes = json.encodeToString(scopes),
            contextId = contextId,
            expiresAt = null,
            lastUsedAt = null,
            revokedAt = null,
            data = EMPTY_JSON
        )

        PersonalAccessTokens.insert { it.fields(pat) }

        pat to rawToken
    }

    /**
     * Verify a raw PAT token. Returns the PAT entity if valid, null otherwise.
     * A token is invalid if it doesn't exist, is revoked, or is expired.
     */
    suspend fun verifyPersonalAccessToken(rawToken: String): PersonalAccessToken? = transaction {
        val hash = hashToken(rawToken)

        val pat = PersonalAccessTokens
            .selectAll()
            .where { PersonalAccessTokens.tokenHash eq hash }
            .map { PersonalAccessTokens.toDto(it) }
            .firstOrNull()
            ?: return@transaction null

        // Check revocation
        if (pat.revokedAt != null) return@transaction null

        // Check expiry
        val expiresAt = pat.expiresAt
        val now = Clock.System.now()
        if (expiresAt != null && now > expiresAt) return@transaction null

        // Update last_used_at
        PersonalAccessTokens.update(
            where = { PersonalAccessTokens.tokenHash eq hash }
        ) {
            it[PersonalAccessTokens.lastUsedAt] = now
        }

        pat
    }

    fun createAccessToken(
        pat: PersonalAccessToken,
        audience: String,
        ttlSeconds: Int
    ): String {
        val nowSeconds = Clock.System.now().epochSeconds
        val expiresAtSeconds = nowSeconds + ttlSeconds
        val header = buildJsonObject {
            put("alg", JsonPrimitive("HS256"))
            put("typ", JsonPrimitive("JWT"))
        }
        val payload = buildJsonObject {
            put("iss", JsonPrimitive(AuthDefaults.ISSUER))
            put("aud", JsonPrimitive(audience))
            put("sub", JsonPrimitive("pat:${pat.id}"))
            put("jti", JsonPrimitive(UUID.randomUUID().toString()))
            put("pat_id", JsonPrimitive(pat.id))
            put("credential_type", JsonPrimitive("pat"))
            put("principal_type", JsonPrimitive(if (pat.userId == null) "service" else "user"))
            put("name", JsonPrimitive(pat.name))
            put("scopes", JsonArray(pat.scopeList().map(::JsonPrimitive)))
            put("scp", JsonArray(pat.scopeList().map(::JsonPrimitive)))
            put("iat", JsonPrimitive(nowSeconds))
            put("exp", JsonPrimitive(expiresAtSeconds))
        }
        val encodedHeader = base64Url(json.encodeToString(header).toByteArray(Charsets.UTF_8))
        val encodedPayload = base64Url(json.encodeToString(payload).toByteArray(Charsets.UTF_8))
        val signingInput = "$encodedHeader.$encodedPayload"
        return "$signingInput.${signJwt(signingInput)}"
    }

    /**
     * Revoke a PAT by its ID.
     */
    suspend fun revokePersonalAccessToken(tokenId: String): Boolean = transaction {
        val updated = PersonalAccessTokens.update(
            where = { PersonalAccessTokens.id eq UUID.fromString(tokenId) }
        ) {
            it[PersonalAccessTokens.revokedAt] = Clock.System.now()
        }
        updated > 0
    }
}
