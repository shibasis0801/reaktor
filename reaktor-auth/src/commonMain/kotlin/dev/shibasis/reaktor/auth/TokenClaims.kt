package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthDefaults
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.Delegation
import dev.shibasis.reaktor.auth.kernel.PermissionRef
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.auth.kernel.RoleRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object TokenClaims {
    fun context(claims: JsonObject, defaultApp: String? = null): AuthContext? {
        val subject = claims.text("sub") ?: return null
        val audiences = claims.texts("aud")
        val app = claims.text("app_id") ?: claims.text("appId") ?: audiences.firstOrNull() ?: defaultApp ?: return null
        val principal = PrincipalRef(subject, kind(claims.text("principal_type")))
        val actor = (claims["act"] as? JsonObject)?.text("sub")?.let { PrincipalRef(it, PrincipalKind.SERVICE) }
        val scopes = claims.texts("scp").ifEmpty { claims.texts("scopes") }
        val permissions = claims.texts("permissions").ifEmpty { claims.texts("perms") }
        return AuthContext(
            principal = principal,
            identityId = claims.text("identity_id"),
            appId = app,
            tenantId = claims.text("tid"),
            contextId = claims.text("ctx"),
            sessionId = claims.text("sid"),
            tokenId = claims.text("jti"),
            credentialId = claims.text("pat_id"),
            issuer = claims.text("iss") ?: AuthDefaults.ISSUER,
            audience = audiences.firstOrNull() ?: app,
            scopes = scopes.map { PermissionRef(name = it) }.toSet(),
            roles = claims.texts("roles").map { RoleRef(name = it) }.toSet(),
            permissions = (permissions + scopes).distinct().map { PermissionRef(name = it) }.toSet(),
            method = method(claims.text("credential_type")),
            actor = actor,
            delegation = actor?.let { Delegation(actor = it, subject = principal) },
            claims = claims,
        )
    }

    fun kind(principalType: String?): PrincipalKind = when (principalType) {
        "service" -> PrincipalKind.SERVICE
        "agent" -> PrincipalKind.AGENT
        "actor" -> PrincipalKind.ACTOR
        else -> PrincipalKind.USER
    }

    fun method(credentialType: String?): AuthMethod = when (credentialType) {
        AuthCredentialType.CLIENT_CREDENTIALS.wireName -> AuthMethod.SERVICE_CREDENTIAL
        AuthCredentialType.PERSONAL_ACCESS_TOKEN.wireName, "personal_access_token" -> AuthMethod.PERSONAL_ACCESS_TOKEN
        AuthCredentialType.ANONYMOUS.wireName -> AuthMethod.ANONYMOUS
        else -> AuthMethod.ACCESS_TOKEN
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.texts(name: String): List<String> = when (val value = this[name]) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        is JsonPrimitive -> value.contentOrNull?.split(' ')?.filter(String::isNotBlank).orEmpty()
        else -> emptyList()
    }
}
