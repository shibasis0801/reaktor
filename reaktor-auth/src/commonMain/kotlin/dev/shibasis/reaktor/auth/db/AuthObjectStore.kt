package dev.shibasis.reaktor.auth.db

import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.db.ObjectDatabase

class AuthObjectStore(database: ObjectDatabase) {
    private val store = database.store("auth")

    suspend fun getContext() = store.get<AuthContext>("context")?.value
    suspend fun setContext(context: AuthContext) = store.put("context", context)

    suspend fun getAccessToken() = store.get<String>("access_token")?.value
    suspend fun setAccessToken(token: String) = store.put("access_token", token)

    suspend fun getAccessTokenExpiresAtEpochMillis() =
        store.get<Long>("access_token_expires_at_epoch_millis")?.value

    suspend fun setAccessTokenExpiresAtEpochMillis(epochMillis: Long) =
        store.put("access_token_expires_at_epoch_millis", epochMillis)

    suspend fun clearAccessTokenExpiresAtEpochMillis() =
        store.delete("access_token_expires_at_epoch_millis")

    suspend fun getRefreshToken() = store.get<String>("refresh_token")?.value
    suspend fun setRefreshToken(token: String) = store.put("refresh_token", token)

    suspend fun clear() = store.clear()
}
