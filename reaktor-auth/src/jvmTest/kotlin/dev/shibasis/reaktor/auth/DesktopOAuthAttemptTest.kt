package dev.shibasis.reaktor.auth

import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*
import kotlinx.coroutines.*

class DesktopOAuthAttemptTest {
    @Test fun callbackRejectsWrongStateDuplicatesErrorsAndOversizedInput() {
        val attempt = DesktopOAuthAttempt()
        assertEquals("code+value", assertIs<DesktopOAuthCallback.Code>(attempt.callback(oauthForm(mapOf("state" to attempt.state, "code" to "code+value")))).value)
        assertNull(attempt.callback("state=wrong&code=value"))
        assertNull(attempt.callback("state=${attempt.state}&state=${attempt.state}&code=value"))
        assertNull(attempt.callback("state=${attempt.state}&error=access_denied&code=value"))
        assertNull(attempt.callback("state=${attempt.state}&code=%00"))
        assertNull(attempt.callback("state=${attempt.state}&code=" + "x".repeat(16_384)))
    }

    @Test fun aBlockedBrowserTimesOutAsARetryableFailureWithoutSwallowingOwnerCancellation() = runBlocking {
        val failure = assertFailsWith<DesktopSignInException> { awaitDesktopOAuthCallback(CompletableDeferred(), 1) }
        assertTrue(failure.message.orEmpty().contains("timed out"))
        val pending = CompletableDeferred<DesktopOAuthCallback>()
        val task = async { awaitDesktopOAuthCallback(pending) }
        task.cancel()
        assertFailsWith<CancellationException> { task.await() }
        Unit
    }

    @Test fun aProviderDenialCompletesOnlyTheMatchingAttemptAndDoesNotEchoProviderText() = runBlocking {
        val attempt = DesktopOAuthAttempt()
        assertNull(attempt.callback("state=wrong&error=access_denied"))
        val denial = assertNotNull(attempt.callback("state=${attempt.state}&error=access_denied&error_description=private"))
        assertEquals(DesktopOAuthCallback.Denied, denial)
        val failure = assertFailsWith<DesktopSignInException> { awaitDesktopOAuthCallback(CompletableDeferred(denial)) }
        assertFalse(failure.message.orEmpty().contains("private"))
    }

    @Test fun everyAttemptHasIndependentPkceNonceAndStateAndRequestsOnlyIdentityScopes() {
        val a = DesktopOAuthAttempt(); val b = DesktopOAuthAttempt()
        assertNotEquals(a.state, b.state); assertNotEquals(a.nonce, b.nonce); assertNotEquals(a.verifier, b.verifier)
        assertEquals(43, a.verifier.length)
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(a.verifier.toByteArray())), a.challenge)
        val uri = a.authorizationUri("example.apps.googleusercontent.com", URI("http://127.0.0.1:12345/oauth2/callback"))
        assertEquals("accounts.google.com", uri.host)
        assertTrue("code_challenge_method=S256" in uri.rawQuery)
        assertTrue("scope=openid+email+profile" in uri.rawQuery)
        assertFalse("client_secret" in uri.rawQuery)
        assertFalse(a.verifier in uri.rawQuery)
    }
}
