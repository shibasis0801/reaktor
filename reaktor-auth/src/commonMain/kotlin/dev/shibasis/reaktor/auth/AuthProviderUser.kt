package dev.shibasis.reaktor.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import dev.shibasis.reaktor.core.framework.json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

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
    override fun json() = JsonObject(json.encodeToJsonElement(this).jsonObject.filterKeys { it != "idToken" })
    override fun toString() = "GoogleUser(emailId=$emailId, idToken=<redacted>)"
}

@Serializable
data class AppleUser(
    override val idToken: String,
    override val givenName: String?,
    override val familyName: String?,
    override val emailId: String,
): AuthProviderUser {
    override fun json() = JsonObject(json.encodeToJsonElement(this).jsonObject.filterKeys { it != "idToken" })
    override fun toString() = "AppleUser(emailId=$emailId, idToken=<redacted>)"
}
