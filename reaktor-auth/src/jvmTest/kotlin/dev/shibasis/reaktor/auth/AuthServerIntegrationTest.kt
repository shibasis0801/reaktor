package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.AuthServer
import dev.shibasis.reaktor.auth.api.AnonymousAuthRequest
import dev.shibasis.reaktor.auth.api.ExchangePatRequest
import dev.shibasis.reaktor.auth.api.LoginResponse
import dev.shibasis.reaktor.auth.api.LogoutAllRequest
import dev.shibasis.reaktor.auth.api.LogoutRequest
import dev.shibasis.reaktor.auth.api.MeRequest
import dev.shibasis.reaktor.auth.api.MintPatRequest
import dev.shibasis.reaktor.auth.api.RefreshRequest
import dev.shibasis.reaktor.auth.api.TokenRequest
import dev.shibasis.reaktor.auth.api.VerifyPatRequest
import dev.shibasis.reaktor.auth.jwt.toAuthContext
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalStatus
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeGraph
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.service.Environment
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Handler-level integration for the AuthServer surface. These tests intentionally call the same
 * PostHandler objects the graph/runtime exposes, so policy around headers, grants, TTL clamping,
 * refresh rotation, logout, whoami, and PAT audience binding is covered above the raw services.
 */
class AuthServerIntegrationTest {
    @BeforeTest
    fun setup() = AuthDbFixture.ensure()

    @Test
    fun anonymousLoginCreatesGuestSessionAndRefreshKeepsAnonymousClaims() = runBlocking {
        val fx = AuthServerFixture()
        val appId = AuthDbFixture.seedApp()

        val response = fx.server.anonymous(
            AnonymousAuthRequest(appId = appId, environment = Environment.STAGE)
        )

        assertTrue(response is LoginResponse.Success)
        assertEquals(AuthMethod.ANONYMOUS, response.context.method)
        assertEquals(PrincipalKind.USER, response.context.principalKind)
        assertNull(response.context.identityId)
        assertEquals(appId, response.context.appId)
        assertEquals(listOf("guest"), response.context.roles)
        assertTrue(response.tokenSet.refreshToken?.startsWith("rkr_") == true)
        assertNotNull(response.tokenSet.sessionId)

        val principal = AuthDbFixture.principalById(response.context.principalId)
        assertNotNull(principal)
        assertEquals(PrincipalKind.USER, principal.kind)
        assertNull(principal.identityId)
        val memberships = AuthDbFixture.membershipsForPrincipal(response.context.principalId)
        assertEquals(1, memberships.size)
        assertEquals(appId, memberships.single().appId)

        val claims = fx.runtime.jwt.verifier
            .verifyReaktorToken(response.tokenSet.accessToken, listOf(appId))
            .getOrNull()
        assertNotNull(claims)
        assertEquals(response.context.principalId, claims.subject)
        assertEquals("anonymous", claims.getStringClaim("credential_type"))
        assertEquals("user", claims.getStringClaim("principal_type"))
        assertNull(claims.getStringClaim("identity_id"))
        assertEquals(listOf("guest"), claims.getStringListClaim("roles"))
        assertEquals(AuthMethod.ANONYMOUS, claims.toAuthContext().method)
        assertAuditEvent("anonymous login should be audited") {
            it.eventType == AuthAuditEventType.TOKEN_MINT &&
                it.outcome == AuthAuditOutcome.SUCCESS &&
                it.credentialType == "anonymous" &&
                it.grantType == "anonymous" &&
                it.subjectPrincipalId == response.context.principalId &&
                it.appId == appId
        }

        val refreshed = fx.server.sessionRefresh(
            RefreshRequest(refreshToken = response.tokenSet.refreshToken ?: "", environment = Environment.STAGE)
        )
        val refreshedTokenSet = refreshed.tokenSet
        assertEquals(StatusCode.OK, refreshed.statusCode)
        assertNotNull(refreshedTokenSet)
        val refreshedClaims = fx.runtime.jwt.verifier
            .verifyReaktorToken(refreshedTokenSet.accessToken, listOf(appId))
            .getOrNull()
        assertNotNull(refreshedClaims)
        assertEquals(response.context.principalId, refreshedClaims.subject)
        assertEquals("anonymous", refreshedClaims.getStringClaim("credential_type"))
        assertNull(refreshedClaims.getStringClaim("identity_id"))
        assertEquals(listOf("guest"), refreshedClaims.getStringListClaim("roles"))
        assertEquals(AuthMethod.ANONYMOUS, refreshedClaims.toAuthContext().method)

        val me = fx.server.sessionMe(
            MeRequest(headers = bearer(refreshedTokenSet.accessToken), environment = Environment.STAGE)
        )
        assertEquals(StatusCode.OK, me.statusCode)
        assertEquals(response.context.principalId, me.principalId)
        assertEquals("USER", me.principalKind)
        assertEquals(listOf("guest"), me.roles)
    }

    @Test
    fun patMintVerifyAndExchangeEnforcesBearerAudienceAndTtlPolicy() = runBlocking {
        val fx = AuthServerFixture()
        val (_, bootstrapRaw) = fx.runtime.personalTokens.createPersonalAccessToken(
            principalId = null,
            name = "bootstrap",
            scopes = listOf("auth:pat:mint"),
        )

        assertEquals(
            StatusCode.UNAUTHORIZED,
            fx.server.mintPat(MintPatRequest(name = "denied", environment = Environment.STAGE)).statusCode,
        )

        val minted = fx.server.mintPat(
            MintPatRequest(
                name = "agent token",
                scopes = listOf("mcp:read", "mcp:write"),
                expiresInDays = 7,
                headers = bearer(bootstrapRaw),
                environment = Environment.STAGE,
            )
        )
        assertEquals(StatusCode.OK, minted.statusCode)
        assertTrue(minted.rawToken.startsWith("rkt_"))

        val verified = fx.server.verifyPat(VerifyPatRequest(rawToken = minted.rawToken, environment = Environment.STAGE))
        assertEquals(StatusCode.OK, verified.statusCode)
        assertTrue(verified.isValid)
        assertEquals(setOf("mcp:read", "mcp:write"), verified.scopes.toSet())
        assertAuditEvent("PAT mint should be audited") {
            it.eventType == AuthAuditEventType.TOKEN_MINT &&
                it.outcome == AuthAuditOutcome.SUCCESS &&
                it.credentialType == "pat" &&
                it.grantType == "mint_pat" &&
                it.tokenId == verified.tokenId
        }

        val wrongAudience = fx.server.token(
            TokenRequest(
                grantType = "pat",
                rawToken = minted.rawToken,
                audience = "other-service",
                ttlSeconds = 30,
                environment = Environment.STAGE,
            )
        )
        assertEquals(StatusCode.FORBIDDEN, wrongAudience.statusCode)
        assertAuditEvent("PAT audience rejection should be audited") {
            it.eventType == AuthAuditEventType.AUTH_FAILURE &&
                it.outcome == AuthAuditOutcome.FAILURE &&
                it.credentialType == "pat" &&
                it.grantType == "exchange_pat" &&
                it.tokenId == verified.tokenId &&
                it.reason == "audience_not_allowed"
        }

        val exchanged = fx.server.token(
            TokenRequest(
                grantType = "pat",
                rawToken = minted.rawToken,
                audience = "manna-mcp",
                ttlSeconds = 30,
                environment = Environment.STAGE,
            )
        )
        assertEquals(StatusCode.OK, exchanged.statusCode)
        assertEquals(60, exchanged.expiresInSeconds, "PAT exchanges clamp too-small TTLs")
        assertEquals(verified.tokenId, exchanged.tokenId)

        val claims = fx.runtime.jwt.verifier.verifyReaktorToken(exchanged.accessToken, listOf("manna-mcp")).getOrNull()
        assertNotNull(claims)
        assertEquals("pat:${exchanged.tokenId}", claims.subject)
        assertEquals("service", claims.getStringClaim("principal_type"))
        assertEquals(exchanged.scopes.toSet(), claims.getStringListClaim("scp").toSet())
        assertAuditEvent("PAT exchange should be audited") {
            it.eventType == AuthAuditEventType.TOKEN_EXCHANGE &&
                it.outcome == AuthAuditOutcome.SUCCESS &&
                it.credentialType == "pat" &&
                it.grantType == "exchange_pat" &&
                it.tokenId == exchanged.tokenId &&
                it.audience == "manna-mcp"
        }
    }

    @Test
    fun exchangePatEndpointMirrorsTokenEndpointPolicy() = runBlocking {
        val fx = AuthServerFixture()
        val (_, raw) = fx.runtime.personalTokens.createPersonalAccessToken(
            principalId = null,
            name = "exchange",
            scopes = listOf("mcp:read"),
            allowedAudiences = listOf("manna-mcp"),
        )

        assertEquals(
            StatusCode.FORBIDDEN,
            fx.server.exchangePat(
                ExchangePatRequest(rawToken = raw, audience = "not-allowed", environment = Environment.STAGE)
            ).statusCode,
        )

        val exchanged = fx.server.exchangePat(
            ExchangePatRequest(rawToken = raw, audience = "manna-mcp", ttlSeconds = 9999, environment = Environment.STAGE)
        )
        assertEquals(StatusCode.OK, exchanged.statusCode)
        assertEquals(15 * 60, exchanged.expiresInSeconds, "PAT exchanges clamp too-large TTLs")
        assertEquals(listOf("mcp:read"), exchanged.scopes)
    }

    @Test
    fun sessionRefreshMeLogoutAndLogoutAllWorkThroughHandlers() = runBlocking {
        val fx = AuthServerFixture()
        val (appId, principalId) = AuthDbFixture.seedUser()
        val roleName = "member-${UUID.randomUUID()}"
        val permissions = AuthDbFixture.grantPermissions(
            principalId,
            appId,
            listOf("profile:read", "auth:pat:mint"),
            roleName = roleName,
        )
        val created = fx.runtime.sessionLifecycle.createSession(principalId, appId)

        val refreshed = fx.server.sessionRefresh(
            RefreshRequest(refreshToken = created.rawRefreshToken, environment = Environment.STAGE)
        )
        val tokenSet = refreshed.tokenSet
        assertEquals(StatusCode.OK, refreshed.statusCode)
        assertNotNull(tokenSet)
        assertNotEquals(created.rawRefreshToken, tokenSet.refreshToken)
        assertEquals(permissions.toSet(), tokenSet.scopes.toSet())
        assertAuditEvent("refresh should be audited") {
            it.eventType == AuthAuditEventType.TOKEN_REFRESH &&
                it.outcome == AuthAuditOutcome.SUCCESS &&
                it.subjectPrincipalId == principalId &&
                it.appId == appId &&
                it.sessionId == tokenSet.sessionId
        }

        val me = fx.server.sessionMe(
            MeRequest(headers = bearer(tokenSet.accessToken), environment = Environment.STAGE)
        )
        assertEquals(StatusCode.OK, me.statusCode)
        assertEquals(principalId, me.principalId)
        assertEquals("USER", me.principalKind)
        assertEquals(appId, me.appId)
        assertEquals(tokenSet.sessionId, me.sessionId)
        assertEquals(permissions.toSet(), me.scopes.toSet())
        assertEquals(setOf(roleName), me.roles.toSet())
        assertEquals(permissions.toSet(), me.permissions.toSet())

        val logout = fx.server.sessionLogout(
            LogoutRequest(refreshToken = tokenSet.refreshToken ?: "", environment = Environment.STAGE)
        )
        assertEquals(StatusCode.OK, logout.statusCode)
        assertTrue(logout.success)
        assertEquals(
            StatusCode.UNAUTHORIZED,
            fx.server.sessionRefresh(
                RefreshRequest(refreshToken = tokenSet.refreshToken ?: "", environment = Environment.STAGE)
            ).statusCode,
        )
        assertAuditEvent("invalid refresh after logout should be audited") {
            it.eventType == AuthAuditEventType.AUTH_FAILURE &&
                it.outcome == AuthAuditOutcome.FAILURE &&
                it.credentialType == "refresh_token" &&
                it.grantType == "refresh_token" &&
                it.reason == "invalid_refresh_token"
        }
        assertAuditEvent("logout should be audited") {
            it.eventType == AuthAuditEventType.TOKEN_REVOKE &&
                it.outcome == AuthAuditOutcome.SUCCESS &&
                it.credentialType == "refresh_token" &&
                it.grantType == "logout"
        }

        val s1 = fx.runtime.sessionLifecycle.createSession(principalId, appId)
        val s2 = fx.runtime.sessionLifecycle.createSession(principalId, appId)
        val bearerToken = fx.runtime.jwt.minter.mintAccessToken(principalId, appId, listOf("profile:read"), sessionId = s1.sessionId)
        val logoutAll = fx.server.sessionLogoutAll(
            LogoutAllRequest(audience = appId, headers = bearer(bearerToken), environment = Environment.STAGE)
        )
        assertEquals(StatusCode.OK, logoutAll.statusCode)
        assertEquals(2, logoutAll.revokedSessions)
        assertNull(fx.runtime.sessionLifecycle.rotate(s1.rawRefreshToken))
        assertNull(fx.runtime.sessionLifecycle.rotate(s2.rawRefreshToken))
        assertAuditEvent("logout-all should be audited") {
            it.eventType == AuthAuditEventType.TOKEN_REVOKE &&
                it.outcome == AuthAuditOutcome.SUCCESS &&
                it.subjectPrincipalId == principalId &&
                it.grantType == "logout_all"
        }
    }

    @Test
    fun clientCredentialsAndDelegationAreDownscopedAudienceBoundAndTtlClamped() = runBlocking {
        val fx = AuthServerFixture()
        val (appId, principalId) = AuthDbFixture.seedUser()
        val clientId = "svc-${UUID.randomUUID()}"
        val secret = "secret-${UUID.randomUUID()}"
        val servicePrincipalId = AuthDbFixture.seedServiceAccount(
            clientId = clientId,
            appId = appId,
            authMethod = "SECRET",
            secretHash = sha256(secret),
            scopes = "delegate:read delegate:write",
            allowedAudiences = """["manna-mcp"]""",
        )

        val clientCredentials = fx.server.token(
            TokenRequest(
                grantType = "client_credentials",
                clientId = clientId,
                clientSecret = secret,
                audience = "manna-mcp",
                scopes = listOf("delegate:write", "admin"),
                ttlSeconds = 9999,
                environment = Environment.STAGE,
            )
        )
        assertEquals(StatusCode.OK, clientCredentials.statusCode)
        assertEquals(15 * 60, clientCredentials.expiresInSeconds)
        assertEquals(listOf("delegate:write"), clientCredentials.scopes)
        val serviceClaims = fx.runtime.jwt.verifier.verifyReaktorToken(clientCredentials.accessToken, listOf("manna-mcp")).getOrNull()
        assertNotNull(serviceClaims)
        assertEquals("service", serviceClaims.getStringClaim("principal_type"))
        assertAuditEvent("client credentials should be audited") {
            it.eventType == AuthAuditEventType.TOKEN_MINT &&
                it.outcome == AuthAuditOutcome.SUCCESS &&
                it.credentialType == "client_credentials" &&
                it.grantType == "client_credentials" &&
                it.subjectPrincipalId == servicePrincipalId &&
                it.audience == "manna-mcp"
        }

        val subjectToken = fx.runtime.jwt.minter.mintAccessToken(principalId, appId, listOf("profile:read"))
        val delegated = fx.server.token(
            TokenRequest(
                grantType = TOKEN_EXCHANGE_GRANT,
                clientId = clientId,
                clientSecret = secret,
                subjectToken = subjectToken,
                audience = "manna-mcp",
                scopes = listOf("delegate:read", "admin"),
                contextId = "room-123",
                ttlSeconds = 30,
                environment = Environment.STAGE,
            )
        )
        assertEquals(StatusCode.OK, delegated.statusCode)
        assertEquals(60, delegated.expiresInSeconds)
        assertEquals(listOf("delegate:read"), delegated.scopes)
        assertEquals("manna-mcp", delegated.audience)
        assertEquals("room-123", delegated.contextId)

        val delegatedClaims = fx.runtime.jwt.verifier.verifyReaktorToken(delegated.accessToken, listOf("manna-mcp")).getOrNull()
        assertNotNull(delegatedClaims)
        assertEquals(principalId, delegatedClaims.subject)
        assertEquals("room-123", delegatedClaims.getStringClaim("ctx"))
        @Suppress("UNCHECKED_CAST")
        val act = delegatedClaims.getClaim("act") as Map<String, Any?>
        assertEquals(servicePrincipalId, act["sub"])
        assertAuditEvent("delegated token exchange should be audited") {
            it.eventType == AuthAuditEventType.TOKEN_EXCHANGE &&
                it.outcome == AuthAuditOutcome.SUCCESS &&
                it.credentialType == "client_credentials" &&
                it.grantType == TOKEN_EXCHANGE_GRANT &&
                it.actorPrincipalId == servicePrincipalId &&
                it.subjectPrincipalId == principalId
        }

        val (otherAppId, otherPrincipalId) = AuthDbFixture.seedUser()
        val otherSubjectToken = fx.runtime.jwt.minter.mintAccessToken(otherPrincipalId, otherAppId, listOf("profile:read"))
        assertEquals(
            StatusCode.FORBIDDEN,
            fx.server.token(
                TokenRequest(
                    grantType = TOKEN_EXCHANGE_GRANT,
                    clientId = clientId,
                    clientSecret = secret,
                    subjectToken = otherSubjectToken,
                    audience = "manna-mcp",
                    environment = Environment.STAGE,
                )
            ).statusCode,
        )
        assertAuditEvent("cross-app delegated subject should be rejected") {
            it.eventType == AuthAuditEventType.AUTH_FAILURE &&
                it.outcome == AuthAuditOutcome.FAILURE &&
                it.credentialType == "client_credentials" &&
                it.grantType == TOKEN_EXCHANGE_GRANT &&
                it.actorPrincipalId == servicePrincipalId &&
                it.subjectPrincipalId == otherPrincipalId &&
                it.reason == "subject_app_mismatch"
        }

        assertEquals(
            StatusCode.FORBIDDEN,
            fx.server.token(
                TokenRequest(
                    grantType = TOKEN_EXCHANGE_GRANT,
                    clientId = clientId,
                    clientSecret = secret,
                    subjectToken = subjectToken,
                    audience = "not-allowed",
                    environment = Environment.STAGE,
                )
            ).statusCode,
        )
        assertAuditEvent("delegated audience rejection should be audited") {
            it.eventType == AuthAuditEventType.AUTH_FAILURE &&
                it.outcome == AuthAuditOutcome.FAILURE &&
                it.credentialType == "client_credentials" &&
                it.grantType == TOKEN_EXCHANGE_GRANT &&
                it.actorPrincipalId == servicePrincipalId &&
                it.subjectPrincipalId == principalId &&
                it.reason == "audience_not_allowed"
        }
    }

    @Test
    fun inactiveServiceAccountsCannotMintTokens() = runBlocking {
        val fx = AuthServerFixture()
        val (appId, _) = AuthDbFixture.seedUser()
        val clientId = "svc-${UUID.randomUUID()}"
        val secret = "secret-${UUID.randomUUID()}"
        AuthDbFixture.seedServiceAccount(
            clientId = clientId,
            appId = appId,
            authMethod = "SECRET",
            secretHash = sha256(secret),
            status = PrincipalStatus.DISABLED,
        )

        val response = fx.server.token(
            TokenRequest(
                grantType = "client_credentials",
                clientId = clientId,
                clientSecret = secret,
                audience = "manna-mcp",
                environment = Environment.STAGE,
            )
        )
        assertEquals(StatusCode.UNAUTHORIZED, response.statusCode)
        assertAuditEvent("disabled service-account attempt should be audited") {
            it.eventType == AuthAuditEventType.AUTH_FAILURE &&
                it.outcome == AuthAuditOutcome.FAILURE &&
                it.credentialType == "client_credentials" &&
                it.grantType == "client_credentials" &&
                it.reason == "invalid_client_credentials"
        }
    }

}

private const val TOKEN_EXCHANGE_GRANT = "urn:ietf:params:oauth:grant-type:token-exchange"

private class AuthServerFixture {
    val runtime = AuthRuntimeGraph.create(AuthDbFixture.adapter())
    val server = AuthServer(runtime.service)
}

private fun bearer(rawToken: String): MutableMap<String, String> =
    mutableMapOf("Authorization" to "Bearer $rawToken")

private fun sha256(value: String): String =
    Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    )

private fun assertAuditEvent(message: String, predicate: (AuthAuditEvent) -> Boolean) {
    assertTrue(
        AuthDbFixture.auditEvents().any(predicate),
        "$message\nRecorded events: ${AuthDbFixture.auditEvents().map { "${it.eventType}/${it.outcome}/${it.credentialType}/${it.grantType}/${it.reason}" }}",
    )
}
