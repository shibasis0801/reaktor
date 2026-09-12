package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.TokenSet
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.service.Environment
import kotlinx.serialization.Serializable

@Serializable
data class StoredAuthSession(val context: AuthContext, val tokens: TokenSet, val expiresAtEpochMillis: Long) {
    override fun toString() = "StoredAuthSession(principal=${context.principal.id}, credentials=<redacted>)"
}

/** A host can replace ObjectDatabase token caching without replacing the auth flow. */
interface AuthSessionStore {
    suspend fun read(environment: Environment): StoredAuthSession?
    suspend fun write(environment: Environment, session: StoredAuthSession)
    suspend fun clear(environment: Environment)
}
