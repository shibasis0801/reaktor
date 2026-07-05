package dev.shibasis.reaktor.auth

import kotlinx.serialization.Serializable
import kotlin.js.JsExport

@Serializable
@JsExport
enum class AuthCredentialType(val wireName: String) {
    ACCESS_TOKEN("access_token"),
    REFRESH_TOKEN("refresh_token"),
    EXTERNAL_LOGIN("external_login"),
    CLIENT_CREDENTIALS("client_credentials"),
    PERSONAL_ACCESS_TOKEN("pat"),
    DELEGATION("delegation"),
    ANONYMOUS("anonymous");

    companion object {
        fun fromWireName(wireName: String?): AuthCredentialType? =
            values().firstOrNull { it.wireName == wireName }
    }
}

@Serializable
@JsExport
enum class AuthGrantType(val wireName: String) {
    LOGIN("login"),
    ANONYMOUS("anonymous"),
    REFRESH_TOKEN("refresh_token"),
    LOGOUT("logout"),
    LOGOUT_ALL("logout_all"),
    MINT_PAT("mint_pat"),
    VERIFY_PAT("verify_pat"),
    PAT("pat"),
    PERSONAL_ACCESS_TOKEN("personal_access_token"),
    CLIENT_CREDENTIALS("client_credentials"),
    TOKEN_EXCHANGE("urn:ietf:params:oauth:grant-type:token-exchange");

    companion object {
        fun fromWireName(wireName: String?): AuthGrantType? =
            values().firstOrNull { it.wireName == wireName }
    }
}
