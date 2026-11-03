package dev.shibasis.reaktor.auth

import com.nimbusds.jwt.JWTClaimsSet
import dev.shibasis.reaktor.auth.api.AppleBrowserRedeem
import dev.shibasis.reaktor.auth.api.AppleBrowserStart
import dev.shibasis.reaktor.core.network.StatusCode
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppleBrowserLoginTest {
    private val verifier = "v".repeat(43)
    private val challenge = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    private val redirect = "https://app.example.test/auth/login/apple/callback"

    @Test fun browserExchangeRequiresTheAppSecretAndCanOnlyBeConsumedOnce() {
        var claims = JWTClaimsSet.Builder().build()
        val service = AppleBrowserLogin("client", redirect) { claims }
        val attempt = service.begin(AppleBrowserStart(challenge))
        val query = query(attempt.authorizationUrl)
        assertEquals("https://appleid.apple.com", URI(attempt.authorizationUrl).let { "${it.scheme}://${it.host}" })
        assertEquals(redirect, query["redirect_uri"])
        assertEquals("client", query["client_id"])
        assertEquals("name email", query["scope"])
        assertEquals("form_post", query["response_mode"])
        assertEquals("code id_token", query["response_type"])
        assertEquals(attempt.state, query["state"])
        assertEquals(StatusCode.ACCEPTED, service.redeem(AppleBrowserRedeem(attempt.state, verifier)).statusCode)
        claims = claims(query.getValue("nonce"))
        assertTrue(service.callback(attempt.state, "apple-token", null, """{"name":{"firstName":"Ada","lastName":"Lovelace"}}"""))
        assertFalse(service.callback(attempt.state, "replayed-token", null, null))
        assertEquals(StatusCode.BAD_REQUEST, service.redeem(AppleBrowserRedeem(attempt.state, "x".repeat(43))).statusCode)
        val credential = service.redeem(AppleBrowserRedeem(attempt.state, verifier))
        assertNull(credential.error)
        assertEquals("apple-token", credential.user?.idToken)
        assertEquals("ada@example.test", credential.user?.emailId)
        assertEquals("Ada", credential.user?.givenName)
        assertEquals("Lovelace", credential.user?.familyName)
        assertEquals("no-store", credential.headers["Cache-Control"])
        assertEquals(StatusCode.BAD_REQUEST, service.redeem(AppleBrowserRedeem(attempt.state, verifier)).statusCode)
    }

    @Test fun invalidClaimsAndInvalidSignaturesNeverReturnACredential() {
        for (invalid in listOf("nonce", "issuer", "audience", "expired", "subject", "signature")) {
            var nonce = ""
            val service = AppleBrowserLogin("client", redirect) {
                if (invalid == "signature") error("Bad signature")
                JWTClaimsSet.Builder(claims(nonce))
                    .apply {
                        when (invalid) {
                            "nonce" -> claim("nonce", "other-attempt")
                            "issuer" -> issuer("https://attacker.example.test")
                            "audience" -> audience("other-client")
                            "expired" -> expirationTime(Date(0))
                            "subject" -> subject("")
                        }
                    }.build()
            }
            val attempt = service.begin(AppleBrowserStart(challenge))
            nonce = query(attempt.authorizationUrl).getValue("nonce")
            assertTrue(service.callback(attempt.state, "token", null, null))
            val credential = service.redeem(AppleBrowserRedeem(attempt.state, verifier))
            assertNull(credential.user, invalid)
            assertNotNull(credential.error, invalid)
        }
    }

    @Test fun attemptsAreIndependentAndCancellationIsDeliveredToTheCorrectApp() {
        val service = AppleBrowserLogin("client", redirect) { error("Cancellation must not verify a token") }
        val first = service.begin(AppleBrowserStart(challenge))
        val second = service.begin(AppleBrowserStart(challenge))
        assertFalse(first.state == second.state)
        assertFalse(query(first.authorizationUrl)["nonce"] == query(second.authorizationUrl)["nonce"])
        assertFalse(service.callback("unknown-state", "token", null, null))
        assertTrue(service.callback(first.state, null, "user_cancelled_authorize", null))
        assertEquals("Sign in with Apple was cancelled", service.redeem(AppleBrowserRedeem(first.state, verifier)).error)
        assertEquals(StatusCode.ACCEPTED, service.redeem(AppleBrowserRedeem(second.state, verifier)).statusCode)
        assertEquals(StatusCode.BAD_REQUEST, service.begin(AppleBrowserStart("bad-challenge")).statusCode)
    }

    private fun claims(nonce: String) = JWTClaimsSet.Builder()
        .issuer("https://appleid.apple.com").audience("client").subject("apple-subject")
        .expirationTime(Date(System.currentTimeMillis() + 60_000)).claim("nonce", nonce)
        .claim("email", "ada@example.test").build()

    private fun query(url: String) = URI(url).rawQuery.split('&').associate {
        it.split('=', limit = 2).let { parts -> parts[0] to URLDecoder.decode(parts[1], Charsets.UTF_8) }
    }
}
