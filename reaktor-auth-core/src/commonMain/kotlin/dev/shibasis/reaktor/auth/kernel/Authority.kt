package dev.shibasis.reaktor.auth.kernel

import kotlinx.serialization.Serializable

@Serializable
data class AuthorityTarget(val appId: String, val tenantId: String, val contextId: String? = null)

@Serializable
data class AuthorityGrant(val target: AuthorityTarget, val appName: String, val tenantName: String, val roles: List<String>)

@Serializable
data class AuthorityResolution(
    val context: AuthContext,
    val environment: String,
    val expiresAtEpochMillis: Long,
    val accessToken: String,
) {
    override fun toString() = "AuthorityResolution(environment=$environment, expiresAtEpochMillis=$expiresAtEpochMillis, accessToken=redacted)"
}

interface AuthorityClient : AutoCloseable {
    suspend fun grants(): List<AuthorityGrant>
    suspend fun resolve(target: AuthorityTarget, permission: String): AuthorityResolution
}
