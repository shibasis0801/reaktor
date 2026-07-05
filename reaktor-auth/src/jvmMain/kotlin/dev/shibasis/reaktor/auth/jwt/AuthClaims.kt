package dev.shibasis.reaktor.auth.jwt

import com.nimbusds.jwt.JWTClaimsSet
import dev.shibasis.reaktor.auth.AuthCredentialType
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthDefaults
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.Delegation
import dev.shibasis.reaktor.auth.kernel.PermissionRef
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.auth.kernel.RoleRef

fun JWTClaimsSet.authScopes(): List<String> =
    stringListClaim("scp").ifEmpty { stringListClaim("scopes") }

fun JWTClaimsSet.authRoles(): List<String> =
    stringListClaim("roles")

fun JWTClaimsSet.authPermissions(): List<String> =
    stringListClaim("permissions").ifEmpty { stringListClaim("perms") }

fun JWTClaimsSet.authPrincipalKind(): PrincipalKind =
    when (stringClaim("principal_type")) {
        "service" -> PrincipalKind.SERVICE
        "agent" -> PrincipalKind.AGENT
        "actor" -> PrincipalKind.ACTOR
        else -> PrincipalKind.USER
    }

fun JWTClaimsSet.authActorSubject(): String? =
    runCatching { (getClaim("act") as? Map<*, *>)?.get("sub") as? String }.getOrNull()

fun JWTClaimsSet.authAppId(): String? =
    audience.firstOrNull()

fun JWTClaimsSet.authSessionId(): String? =
    stringClaim("sid")

fun JWTClaimsSet.toAuthContext(): AuthContext {
    val appId = authAppId().orEmpty()
    val principal = PrincipalRef(subject.orEmpty(), authPrincipalKind())
    val actor = authActorSubject()?.let { PrincipalRef(it, PrincipalKind.SERVICE) }

    val capabilities = (authPermissions() + authScopes()).distinct()
    return AuthContext(
        principal = principal,
        identityId = stringClaim("identity_id"),
        appId = appId,
        tenantId = stringClaim("tid"),
        contextId = stringClaim("ctx"),
        sessionId = authSessionId(),
        tokenId = getJWTID(),
        credentialId = stringClaim("pat_id"),
        issuer = issuer ?: AuthDefaults.ISSUER,
        audience = audience.firstOrNull() ?: appId,
        scopes = authScopes().map { PermissionRef(name = it) }.toSet(),
        roles = authRoles().map { RoleRef(name = it) }.toSet(),
        permissions = capabilities.map { PermissionRef(name = it) }.toSet(),
        method = authMethod(),
        actor = actor,
        delegation = actor?.let { Delegation(actor = it, subject = principal) },
    )
}

private fun JWTClaimsSet.authMethod(): AuthMethod =
    when (AuthCredentialType.fromWireName(stringClaim("credential_type"))) {
        AuthCredentialType.PERSONAL_ACCESS_TOKEN -> AuthMethod.PERSONAL_ACCESS_TOKEN
        AuthCredentialType.CLIENT_CREDENTIALS -> AuthMethod.SERVICE_CREDENTIAL
        AuthCredentialType.ANONYMOUS -> AuthMethod.ANONYMOUS
        else -> AuthMethod.ACCESS_TOKEN
    }

private fun JWTClaimsSet.stringClaim(name: String): String? =
    runCatching { getStringClaim(name) }.getOrNull()

private fun JWTClaimsSet.stringListClaim(name: String): List<String> =
    runCatching { getStringListClaim(name) }.getOrNull().orEmpty()
