package dev.shibasis.reaktor.auth.services

import dev.shibasis.reaktor.auth.PersonalAccessToken
import dev.shibasis.reaktor.auth.PersonalAccessTokens
import dev.shibasis.reaktor.auth.encodeTokenPolicyList
import dev.shibasis.reaktor.auth.jwt.JwtMinter
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.parseTokenPolicyList
import dev.shibasis.reaktor.core.framework.EMPTY_JSON
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class TokenInteractor(
    private val jwtMinter: JwtMinter,
) {
    /**
     * Tier-explicit transaction — see ExposedAdapter.databaseFor. A bare `transaction { }` binds to
     * Exposed's environment-blind default database, so a PAT minted or verified for one tier would
     * hit the other tier's table.
     */
    private fun <T> txn(database: Database?, block: () -> T): T =
        if (database != null) transaction(database) { block() } else transaction { block() }


    private fun hashToken(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(rawToken.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(hashBytes)
    }

    /**
     * Mint a PAT in the GitHub/Stripe-grade format: `rkt_<base64url 32-byte secret><crc32>`.
     * The trailing 8-hex CRC32 lets secret-scanners (and us) reject a malformed/truncated token
     * **offline** — before any DB lookup. The full string is what gets SHA-256 hashed at rest.
     */
    private fun generateSecureToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val body = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return body + crc32Hex(body)
    }

    private companion object {
        const val TOKEN_PREFIX = "rkt_"
        const val CHECKSUM_LEN = 8
    }

    private fun crc32Hex(input: String): String {
        val crc = CRC32().apply { update(input.toByteArray(Charsets.UTF_8)) }.value
        return crc.toString(16).padStart(CHECKSUM_LEN, '0')
    }

    /**
     * Offline structural check: `rkt_` tokens must carry a valid trailing CRC32.
     */
    private fun checksumValid(rawToken: String): Boolean {
        if (!rawToken.startsWith(TOKEN_PREFIX)) return false
        if (rawToken.length <= TOKEN_PREFIX.length + CHECKSUM_LEN) return false
        val body = rawToken.dropLast(CHECKSUM_LEN)
        val checksum = rawToken.takeLast(CHECKSUM_LEN)
        return crc32Hex(body).equals(checksum, ignoreCase = true)
    }

    private fun PersonalAccessToken.scopeList(): List<String> =
        parseTokenPolicyList(scopes)

    /**
     * Create a Personal Access Token for a principal or bootstrap service.
     * Returns (PAT entity, raw token string). The raw token is shown once and never stored.
     */
    suspend fun createPersonalAccessToken(
        principalId: String?,
        name: String,
        scopes: List<String>,
        contextId: String? = null,
        appId: String? = null,
        allowedAudiences: List<String> = listOf("manna-mcp"),
        expiresInDays: Int? = null,
        database: Database? = null,
    ): Pair<PersonalAccessToken, String> = txn(database) {

        val rawToken = generateSecureToken()
        val tokenHash = hashToken(rawToken)
        val id = UUID.randomUUID().toString()

        val pat = PersonalAccessToken(
            id = id,
            principalId = principalId,
            name = name,
            tokenHash = tokenHash,
            scopes = encodeTokenPolicyList(scopes),
            contextId = contextId,
            expiresAt = expiresInDays?.let { Clock.System.now() + it.days },
            lastUsedAt = null,
            revokedAt = null,
            appId = appId,
            allowedAudiences = allowedAudiences.takeIf { it.isNotEmpty() }?.let(::encodeTokenPolicyList),
            data = EMPTY_JSON
        )

        PersonalAccessTokens.insert {
            // Set the id explicitly so the returned DTO's id matches the row (the column auto-generates
            // otherwise, leaving callers — e.g. revoke-by-id — pointing at a non-existent id).
            it[PersonalAccessTokens.id] = PersonalAccessTokens.entityId(UUID.fromString(id))
            it.fields(pat)
        }

        pat to rawToken
    }

    /**
     * Verify a raw PAT token. Returns the PAT entity if valid, null otherwise.
     * A token is invalid if it doesn't exist, is revoked, or is expired.
     */
    suspend fun verifyPersonalAccessToken(rawToken: String, database: Database? = null): PersonalAccessToken? = txn(database) {
        // Reject structurally-invalid rkt_ tokens before touching the DB.
        if (!checksumValid(rawToken)) return@txn null
        val hash = hashToken(rawToken)

        val pat = PersonalAccessTokens
            .selectAll()
            .where { PersonalAccessTokens.tokenHash eq hash }
            .map { PersonalAccessTokens.toDto(it) }
            .firstOrNull()
            ?: return@txn null

        // Check revocation
        if (pat.revokedAt != null) return@txn null

        // Check expiry
        val expiresAt = pat.expiresAt
        val now = Clock.System.now()
        if (expiresAt != null && now > expiresAt) return@txn null

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
        val scopes = pat.scopeList()
        return jwtMinter.mintPatAccessToken(
            patId = pat.id,
            subject = pat.principalId ?: "pat:${pat.id}",
            name = pat.name,
            audience = audience,
            scopes = scopes,
            appId = pat.appId,
            contextId = pat.contextId,
            principalKind = if (pat.principalId == null) PrincipalKind.SERVICE else PrincipalKind.USER,
            ttlSeconds = ttlSeconds,
        )
    }

    /**
     * Revoke a PAT by its ID.
     */
    suspend fun revokePersonalAccessToken(tokenId: String, database: Database? = null): Boolean = txn(database) {
        val updated = PersonalAccessTokens.update(
            where = { PersonalAccessTokens.id eq UUID.fromString(tokenId) }
        ) {
            it[PersonalAccessTokens.revokedAt] = Clock.System.now()
        }
        updated > 0
    }
}
