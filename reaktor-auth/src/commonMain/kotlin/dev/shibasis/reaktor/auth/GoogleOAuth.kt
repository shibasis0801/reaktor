package dev.shibasis.reaktor.auth

import io.ktor.http.URLBuilder
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64

class GoogleOAuthRequest(val clientId: String, serverClientId: String?) {
    val authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth"
    val tokenEndpoint = "https://oauth2.googleapis.com/token"
    val redirectUrl: String
    val scopes = listOf("openid", "email", "profile")
    val authorizationParameters: Map<String, String>
    val tokenParameters: Map<String, String>
    init {
        require(clientId.isNotBlank() && clientId.all { it.isLetterOrDigit() || it in ".-_" }) { "Invalid Google client ID" }
        require(serverClientId == null || serverClientId.isNotBlank()) { "Invalid server client ID" }
        redirectUrl = clientId.split('.').reversed().joinToString(".") + ":/oauth2callback"
        tokenParameters = serverClientId?.let { mapOf("audience" to it) }.orEmpty()
        authorizationParameters = mapOf("include_granted_scopes" to "false", "prompt" to "select_account") + tokenParameters
    }
    fun acceptsSession(authorized: Boolean, sessionClientId: String) = authorized && sessionClientId == clientId
}

class GoogleOAuthClaims(token: String?) {
    private val claims = runCatching {
        val parts = requireNotNull(token).split('.')
        require(parts.size == 3)
        val payload = parts[1]
        val padded = payload.padEnd((payload.length + 3) / 4 * 4, '=')
        Json.parseToJsonElement(Base64.UrlSafe.decode(padded).decodeToString()).jsonObject
    }.getOrElse { JsonObject(emptyMap()) }
    private fun value(key: String) = runCatching { claims[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
    val givenName get() = value("given_name")
    val familyName get() = value("family_name")
    val email get() = value("email")
    fun imageUrl(dimension: Int): String? = value("picture")?.let { picture ->
        runCatching { URLBuilder(picture).apply { parameters.remove("sz"); parameters.append("sz", dimension.toString()) }.buildString() }.getOrNull()
    }
}
