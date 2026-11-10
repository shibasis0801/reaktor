package dev.shibasis.reaktor.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.SignedJWT
import dev.shibasis.reaktor.auth.api.DeactivateAccountRequest
import dev.shibasis.reaktor.auth.kernel.AuthProviderKind
import dev.shibasis.reaktor.auth.runtime.AppleHttpReply
import dev.shibasis.reaktor.auth.runtime.AppleRevocationClient
import dev.shibasis.reaktor.auth.runtime.AppleTokenRevoker
import dev.shibasis.reaktor.core.framework.EMPTY_JSON
import dev.shibasis.reaktor.service.Environment
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppleTokenRevokerTest {
    private val key = ECKeyGenerator(Curve.P_256).keyID("synthetic-test-key").generate()
    private val code = UUID.randomUUID().toString()
    private val refresh = UUID.randomUUID().toString()
    private val identity = UUID.randomUUID().toString()
    private val request = DeactivateAccountRequest(
        audience = "test-app", environment = Environment.STAGE,
        appleAuthorizationCode = code, appleClientId = "native-client",
    )
    private val accounts = listOf(AuthProviderAccount(
        id = UUID.randomUUID().toString(), identityId = UUID.randomUUID().toString(),
        provider = AuthProviderKind.APPLE, issuer = "https://appleid.apple.com",
        subject = "linked-subject", data = EMPTY_JSON,
    ))

    private fun client(clientId: String = "native-client", redirect: String? = null) = AppleRevocationClient(
        "test-app", Environment.STAGE, clientId, "test-team", key.keyID, key.toECPrivateKey(), redirect,
    )

    private fun exchange() = AppleHttpReply(200, """{"id_token":"$identity","refresh_token":"$refresh"}""")

    @Test
    fun clientSecretUsesTheConfiguredClaimsAndFiveMinuteExpiry() {
        val now = Instant.parse("2026-11-09T00:00:00Z")
        val jwt = SignedJWT.parse(client().clientSecret(now))
        assertEquals(JWSAlgorithm.ES256, jwt.header.algorithm)
        assertEquals(key.keyID, jwt.header.keyID)
        assertTrue(jwt.verify(ECDSAVerifier(key.toECPublicKey())))
        assertEquals("test-team", jwt.jwtClaimsSet.issuer)
        assertEquals("native-client", jwt.jwtClaimsSet.subject)
        assertEquals(listOf("https://appleid.apple.com"), jwt.jwtClaimsSet.audience)
        assertEquals(now, jwt.jwtClaimsSet.issueTime.toInstant())
        assertEquals(now.plusSeconds(300), jwt.jwtClaimsSet.expirationTime.toInstant())
    }

    @Test
    fun exchangesOnceThenRevokesTheTokenForTheLinkedIdentity() = runBlocking {
        val calls = mutableListOf<String>()
        val revoker = AppleTokenRevoker(listOf(client()), verifyIdentity = { token, audience ->
            assertEquals(identity, token)
            assertEquals("native-client", audience)
            "linked-subject"
        }, post = { path, form ->
            calls += path
            assertEquals("native-client", form["client_id"])
            assertFalse(form.getValue("client_secret").isBlank())
            if (path == "/auth/token") {
                assertEquals(code, form["code"])
                assertEquals("authorization_code", form["grant_type"])
                assertFalse(form.containsKey("redirect_uri"))
                exchange()
            } else {
                assertEquals(refresh, form["token"])
                assertEquals("refresh_token", form["token_type_hint"])
                AppleHttpReply(200)
            }
        })
        assertTrue(revoker.revoke(request, accounts))
        assertEquals(listOf("/auth/token", "/auth/revoke"), calls)
    }

    @Test
    fun webExchangeUsesOnlyTheConfiguredRedirect() = runBlocking {
        val redirect = "https://example.test/delete-account"
        val revoker = AppleTokenRevoker(listOf(client("web-client", redirect)), { _, _ -> "linked-subject" }) { path, form ->
            if (path == "/auth/token") {
                assertEquals(redirect, form["redirect_uri"])
                exchange()
            } else AppleHttpReply(200)
        }
        assertTrue(revoker.revoke(request.copy(appleClientId = "web-client"), accounts))
    }

    @Test
    fun rejectsUnconfiguredAppEnvironmentClientAndMissingCodeBeforeAnyCall() = runBlocking {
        var calls = 0
        val revoker = AppleTokenRevoker(listOf(client()), { _, _ -> "linked-subject" }) { _, _ ->
            calls++
            exchange()
        }
        val invalid = listOf(request.copy(audience = "other-app"), request.copy(environment = Environment.PROD),
            request.copy(appleClientId = "other-client"), request.copy(appleAuthorizationCode = null))
        invalid.forEach { assertFalse(revoker.revoke(it, accounts)) }
        assertFalse(revoker.revoke(request, emptyList()))
        assertEquals(0, calls)
    }

    @Test
    fun neverRevokesAnotherAppleIdentity() = runBlocking {
        val calls = mutableListOf<String>()
        val revoker = AppleTokenRevoker(listOf(client()), { _, _ -> "other-subject" }) { path, _ ->
            calls += path
            exchange()
        }
        assertFalse(revoker.revoke(request, accounts))
        assertEquals(listOf("/auth/token"), calls)
    }

    @Test
    fun retriesRevocationOnceWithoutReusingTheAuthorizationCode() = runBlocking {
        val calls = mutableListOf<String>()
        val revoker = AppleTokenRevoker(listOf(client()), { _, _ -> "linked-subject" }) { path, _ ->
            calls += path
            when {
                path == "/auth/token" -> exchange()
                calls.size == 2 -> AppleHttpReply(503)
                else -> AppleHttpReply(200)
            }
        }
        assertTrue(revoker.revoke(request, accounts))
        assertEquals(listOf("/auth/token", "/auth/revoke", "/auth/revoke"), calls)
    }

    @Test
    fun boundsTransientExchangeFailuresAndDoesNotRetryRejectedCodes() = runBlocking {
        for ((status, expectedCalls) in listOf(503 to 2, 429 to 2, 400 to 1)) {
            var calls = 0
            val revoker = AppleTokenRevoker(listOf(client()), { _, _ -> "linked-subject" }) { _, _ ->
                calls++
                AppleHttpReply(status)
            }
            assertFalse(revoker.revoke(request, accounts))
            assertEquals(expectedCalls, calls)
        }
    }

    @Test
    fun transportFailuresStayBoundedAndDiagnosticsRedactCredentials() = runBlocking {
        var calls = 0
        val revoker = AppleTokenRevoker(listOf(client()), { _, _ -> "linked-subject" }) { _, _ ->
            calls++
            error(code)
        }
        assertFalse(revoker.revoke(request, accounts))
        assertEquals(2, calls)
        assertFalse(request.toString().contains(code))
        assertFalse(AppleHttpReply(500, refresh).toString().contains(refresh))
        assertFalse(client().toString().contains(key.toJSONString()))
        val user = AppleUser(identity, null, null, "", authorizationCode = code)
        assertFalse(user.toString().contains(code))
        assertFalse(user.json().toString().contains(code))
    }
}
