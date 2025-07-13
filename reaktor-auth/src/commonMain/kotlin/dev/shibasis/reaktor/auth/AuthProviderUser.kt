package dev.shibasis.reaktor.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import dev.shibasis.reaktor.core.framework.json
import kotlinx.serialization.json.encodeToJsonElement

interface AuthProviderUser {
    val idToken: String // JWT Token
    val givenName: String?
    val familyName: String?
    val emailId: String
    fun json(): JsonElement
}

@Serializable
data class GoogleUser(
    override val idToken: String,
    override val givenName: String?,
    override val familyName: String?,
    override val emailId: String,
    val imageUrl: String
): AuthProviderUser {
    override fun json() = json.encodeToJsonElement(this)
}

@Serializable
data class AppleUser(
    override val idToken: String,
    override val givenName: String?,
    override val familyName: String?,
    override val emailId: String,
): AuthProviderUser {
    override fun json() = json.encodeToJsonElement(this)
}