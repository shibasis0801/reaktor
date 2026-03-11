package dev.shibasis.reaktor.auth.db

import dev.shibasis.reaktor.auth.User
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.db.core.ObjectStore

class AuthObjectStore(database: ObjectDatabase) {
    private val store: ObjectStore = ObjectStore(database, "auth")

    suspend fun getUser() = store.read<User>("user")?.value
    suspend fun setUser(user: User) = store.write("user", user)

    suspend fun getAccessToken() = store.read<String>("access_token")?.value
    suspend fun setAccessToken(token: String) = store.write("access_token", token)

    suspend fun getRefreshToken() = store.read<String>("refresh_token")?.value
    suspend fun setRefreshToken(token: String) = store.write("refresh_token", token)

    suspend fun clear() = store.clear()
}
