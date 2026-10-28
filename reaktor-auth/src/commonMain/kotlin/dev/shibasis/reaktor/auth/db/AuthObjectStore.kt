package dev.shibasis.reaktor.auth.db

import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.db.ObjectDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Mobile hosts install an OS-protected store before creating their auth graph. */
interface AuthSecretStore {
    suspend fun read(): String?
    suspend fun write(value: String)
}

var Feature.AuthSecrets by CreateSlot<AuthSecretStore>()

@Serializable
private data class AuthSecrets(
    val context: AuthContext? = null,
    val accessToken: String? = null,
    val expiresAt: Long? = null,
    val refreshToken: String? = null,
)

/**
 * One storage boundary for adapters, graph hydration and development fixtures.
 * Protected hosts never write credentials back to the general object database.
 */
class AuthObjectStore(
    database: ObjectDatabase,
    private val secrets: AuthSecretStore? = Feature.AuthSecrets,
) {
    private val store = database.store("auth")

    suspend fun getContext(): AuthContext? = read().context
    suspend fun setContext(context: AuthContext) = update { it.copy(context = context) }

    suspend fun getAccessToken(): String? = read().accessToken
    suspend fun setAccessToken(token: String) = update { it.copy(accessToken = token) }

    suspend fun getAccessTokenExpiresAtEpochMillis(): Long? = read().expiresAt
    suspend fun setAccessTokenExpiresAtEpochMillis(epochMillis: Long) = update { it.copy(expiresAt = epochMillis) }
    suspend fun clearAccessTokenExpiresAtEpochMillis() = update { it.copy(expiresAt = null) }

    suspend fun getRefreshToken(): String? = read().refreshToken
    suspend fun setRefreshToken(token: String) = update { it.copy(refreshToken = token) }

    /** A fresh login can replace an unreadable old record without decrypting it first. */
    suspend fun setSession(context: AuthContext, accessToken: String, refreshToken: String?, expiresAt: Long?) =
        mutex.withLock { write(AuthSecrets(context, accessToken, expiresAt, refreshToken)) }

    suspend fun setTokens(accessToken: String, refreshToken: String?, expiresAt: Long?) = update {
        it.copy(accessToken = accessToken, refreshToken = refreshToken ?: it.refreshToken, expiresAt = expiresAt)
    }

    suspend fun clear() = mutex.withLock { write(AuthSecrets()) }

    private suspend fun read(): AuthSecrets = mutex.withLock { readLocked() }

    private suspend fun update(change: (AuthSecrets) -> AuthSecrets) = mutex.withLock {
        write(change(readLocked()))
    }

    private suspend fun readLocked(): AuthSecrets {
        val protected = secrets ?: return readLegacy()
        val encoded = protected.read()
        if (encoded != null) {
            val value = json.decodeFromString<AuthSecrets>(encoded)
            store.clear()
            return value
        }
        // Commit the protected copy first. A failed OS write leaves the old session
        // available for a retry, and must never trigger a plaintext fallback.
        val legacy = readLegacy()
        protected.write(json.encodeToString(AuthSecrets.serializer(), legacy))
        store.clear()
        return legacy
    }

    private suspend fun readLegacy() = AuthSecrets(
        context = store.get<AuthContext>("context")?.value,
        accessToken = store.get<String>("access_token")?.value,
        expiresAt = store.get<Long>("access_token_expires_at_epoch_millis")?.value,
        refreshToken = store.get<String>("refresh_token")?.value,
    )

    private suspend fun write(value: AuthSecrets) {
        if (secrets != null) {
            // Retain an empty protected record on logout, preventing an old database
            // backup from resurrecting credentials after the user has signed out.
            secrets.write(json.encodeToString(AuthSecrets.serializer(), value))
            store.clear()
            return
        }
        value.context?.let { store.put("context", it) } ?: store.delete("context")
        value.accessToken?.let { store.put("access_token", it) } ?: store.delete("access_token")
        value.expiresAt?.let { store.put("access_token_expires_at_epoch_millis", it) }
            ?: store.delete("access_token_expires_at_epoch_millis")
        value.refreshToken?.let { store.put("refresh_token", it) } ?: store.delete("refresh_token")
    }

    private companion object {
        // All instances share this lock, including AuthNode and adapter instances.
        val mutex = Mutex()
        val json = Json { ignoreUnknownKeys = true }
    }
}
