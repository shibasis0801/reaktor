package dev.shibasis.reaktor.auth.services

import dev.shibasis.reaktor.auth.RefreshToken
import dev.shibasis.reaktor.auth.RefreshTokens
import dev.shibasis.reaktor.auth.Session
import dev.shibasis.reaktor.auth.Sessions
import dev.shibasis.reaktor.core.framework.EMPTY_JSON
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.core.and
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

data class NewSession(val sessionId: String, val rawRefreshToken: String)
data class RotatedSession(val session: Session, val rawRefreshToken: String)

/**
 * Real session lifecycle + one-time-use refresh-token rotation with family reuse-detection (RFC 9700),
 * replacing the throwaway `UUID.randomUUID()` refresh. Refresh tokens are high-entropy, hashed at rest
 * (SHA-256), single-use; every refresh rotates to a new token in the same family. Replaying an
 * already-rotated token is treated as theft → the whole family is revoked, forcing re-auth.
 *
 * Anchored on principal sessions: user, service, agent, and actor sessions all use the same lifecycle.
 */
class SessionRefreshService {
    private val refreshTtl = 30.days

    /**
     * Run [block] against [database], or the Exposed default when null.
     *
     * Every session write MUST be tier-explicit. A bare `transaction { }` binds to Exposed's
     * default database, which is environment-blind: with stageDb and prodDb pointing at different
     * Supabase projects, a dev login would write its session row into prod (and only survives
     * because `session.app_id` has an FK the dev app-id cannot satisfy — a 500, not silent
     * corruption). Callers pass the database resolved from the request's X-Environment via
     * ExposedAdapter.databaseFor.
     *
     * Nullable so callers that genuinely have no request context keep the previous behaviour
     * instead of guessing a tier.
     */
    private fun <T> txn(database: Database?, block: () -> T): T =
        if (database != null) transaction(database) { block() } else transaction { block() }

    private fun hash(raw: String): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        )

    private fun randomRefreshToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return "rkr_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Create a session at login and issue the first (rotating) refresh token. Returns the session id + raw token. */
    fun createSession(
        principalId: String,
        appId: String,
        tenantId: String? = null,
        contextId: String? = null,
        database: Database? = null,
    ): NewSession = txn(database) {
        val now = Clock.System.now()
        val sessionId = UUID.randomUUID()
        val expiresAt = now + refreshTtl
        Sessions.insert {
            it[Sessions.id] = Sessions.entityId(sessionId)
            it.fields(
                Session(
                    id = sessionId.toString(),
                    principalId = principalId,
                    appId = appId,
                    tenantId = tenantId,
                    contextId = contextId,
                    expiresAt = expiresAt,
                    data = EMPTY_JSON,
                )
            )
        }
        val raw = randomRefreshToken()
        RefreshTokens.insert {
            it.fields(
                RefreshToken(
                    id = UUID.randomUUID().toString(), sessionId = sessionId.toString(),
                    tokenHash = hash(raw), familyId = UUID.randomUUID().toString(),
                    principalId = principalId, expiresAt = expiresAt, data = EMPTY_JSON
                )
            )
        }
        NewSession(sessionId.toString(), raw)
    }

    /**
     * Rotate a refresh token. Returns the (session, new raw token) on success; null when invalid/expired.
     * If the presented token was already used (reuse/replay), the entire family is revoked and null is returned.
     */
    fun rotate(rawRefresh: String, database: Database? = null): RotatedSession? = txn(database) {
        val tokenHash = hash(rawRefresh)
        val token = RefreshTokens.selectAll()
            .where { RefreshTokens.tokenHash eq tokenHash }
            .map { RefreshTokens.toDto(it) }
            .firstOrNull() ?: return@txn null

        val now = Clock.System.now()
        if (token.revokedAt != null) return@txn null
        if (now > token.expiresAt) return@txn null
        if (token.usedAt != null) {
            // Reuse detected → revoke the whole family, forcing re-authentication.
            RefreshTokens.update({ RefreshTokens.familyId eq UUID.fromString(token.familyId) }) {
                it[RefreshTokens.revokedAt] = now
            }
            return@txn null
        }

        RefreshTokens.update({ RefreshTokens.id eq UUID.fromString(token.id) }) {
            it[RefreshTokens.usedAt] = now
            it[RefreshTokens.rotatedAt] = now
        }

        val session = Sessions.selectAll()
            .where { Sessions.id eq UUID.fromString(token.sessionId) }
            .map { Sessions.toDto(it) }
            .firstOrNull() ?: return@txn null
        if (now > session.expiresAt) return@txn null

        val raw = randomRefreshToken()
        RefreshTokens.insert {
            it.fields(
                RefreshToken(
                    id = UUID.randomUUID().toString(), sessionId = session.id, tokenHash = hash(raw),
                    familyId = token.familyId, principalId = token.principalId, previousTokenId = token.id,
                    expiresAt = session.expiresAt, data = EMPTY_JSON
                )
            )
        }
        RotatedSession(session, raw)
    }

    /** Revoke every session of a principal (logout-all / "sign out everywhere"). Returns the session count revoked. */
    fun revokeAllForPrincipal(principalId: String, database: Database? = null): Int = txn(database) {
        val now = Clock.System.now()
        val sessionIds = Sessions.selectAll()
            .where { (Sessions.principalId eq UUID.fromString(principalId)) and (Sessions.expiresAt greater now) }
            .map { it[Sessions.id].value }
        if (sessionIds.isEmpty()) return@txn 0
        RefreshTokens.update({ RefreshTokens.sessionId inList sessionIds }) {
            it[RefreshTokens.revokedAt] = now
        }
        Sessions.update({ Sessions.id inList sessionIds }) {
            it[Sessions.expiresAt] = now
        }
        sessionIds.size
    }

    /** Revoke a session and its whole refresh-token family (logout). */
    fun revokeByRefreshToken(rawRefresh: String, database: Database? = null): Boolean = txn(database) {
        val token = RefreshTokens.selectAll()
            .where { RefreshTokens.tokenHash eq hash(rawRefresh) }
            .map { RefreshTokens.toDto(it) }
            .firstOrNull() ?: return@txn false
        val now = Clock.System.now()
        RefreshTokens.update({ RefreshTokens.familyId eq UUID.fromString(token.familyId) }) {
            it[RefreshTokens.revokedAt] = now
        }
        Sessions.update({ Sessions.id eq UUID.fromString(token.sessionId) }) {
            it[Sessions.expiresAt] = now
        }
        true
    }
}
