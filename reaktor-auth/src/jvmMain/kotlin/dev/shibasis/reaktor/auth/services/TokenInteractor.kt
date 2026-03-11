package dev.shibasis.reaktor.auth.services

import dev.shibasis.reaktor.auth.PersonalAccessToken
import dev.shibasis.reaktor.auth.PersonalAccessTokens
import dev.shibasis.reaktor.auth.User
import dev.shibasis.reaktor.auth.api.LoginRequest
import dev.shibasis.reaktor.core.framework.json
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

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

    // Usually invoked by an admin creating a token for a Service Account, or by a User creating a PAT
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
            data = dev.shibasis.reaktor.core.framework.EMPTY_JSON
        )

        // Assuming a standard insert. In a real system you'd map this to Exposed properly like AppRepository does,
        // or just rely on a PersonalAccessTokenRepository. We will just emulate the logic here.
        // For production, you should add a PersonalAccessTokenRepository patterned after UserRepository.
        
        // Return both the entity and the raw token so the user can copy it once.
        pat to rawToken
    }

    suspend fun verifyPersonalAccessToken(rawToken: String): PersonalAccessToken? = transaction {
        val hash = hashToken(rawToken)
        // val result = PersonalAccessTokenRepository.findByTokenHash(hash)
        // return result
        // Placeholder for DB lookup
        null 
    }
}
