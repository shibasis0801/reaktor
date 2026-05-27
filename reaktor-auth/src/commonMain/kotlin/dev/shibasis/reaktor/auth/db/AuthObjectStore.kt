package dev.shibasis.reaktor.auth.db

import dev.shibasis.reaktor.auth.User
import dev.shibasis.reaktor.db.ObjectDatabase

class AuthObjectStore(database: ObjectDatabase) {
    private val store = database.store("auth")

    suspend fun getUser() = store.get<User>("user")?.value
    suspend fun setUser(user: User) = store.put("user", user)

    suspend fun getAccessToken() = store.get<String>("access_token")?.value
    suspend fun setAccessToken(token: String) = store.put("access_token", token)

    suspend fun getRefreshToken() = store.get<String>("refresh_token")?.value
    suspend fun setRefreshToken(token: String) = store.put("refresh_token", token)

    suspend fun clear() = store.clear()
}
