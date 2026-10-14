package dev.shibasis.reaktor.auth

import kotlin.io.encoding.Base64
import kotlin.test.*

class GoogleOAuthTest {
    @Test fun requestPolicyAndSessionIdentity() {
        val request = GoogleOAuthRequest("fixture.apps.googleusercontent.com", "server-client")
        assertEquals("com.googleusercontent.apps.fixture:/oauth2callback", request.redirectUrl)
        assertEquals(listOf("openid", "email", "profile"), request.scopes)
        assertEquals("server-client", request.authorizationParameters["audience"])
        assertEquals("server-client", request.tokenParameters["audience"])
        assertEquals("select_account", request.authorizationParameters["prompt"])
        assertTrue(request.acceptsSession(true, request.clientId))
        assertFalse(request.acceptsSession(false, request.clientId))
        assertFalse(request.acceptsSession(true, "another-client"))
        assertTrue(GoogleOAuthRequest(request.clientId, null).tokenParameters.isEmpty())
        assertFailsWith<IllegalArgumentException> { GoogleOAuthRequest("", null) }
    }
    @Test fun claimsSupportUnicodeAndReplaceImageSize() {
        val payload = """{"given_name":"名","family_name":"Family","email":"fixture@example.com","picture":"https://example.com/image?sz=10&other=yes"}"""
        val token = "header.${Base64.UrlSafe.encode(payload.encodeToByteArray()).trimEnd('=')}.signature"
        val claims = GoogleOAuthClaims(token)
        assertEquals("名", claims.givenName)
        assertEquals("Family", claims.familyName)
        assertEquals("fixture@example.com", claims.email)
        assertEquals("https://example.com/image?other=yes&sz=320", claims.imageUrl(320))
    }
    @Test fun malformedDisplayClaimsAreAbsent() {
        for (token in listOf(null, "invalid", "header.invalid.signature")) {
            assertNull(GoogleOAuthClaims(token).email)
            assertNull(GoogleOAuthClaims(token).imageUrl(320))
        }
    }
}
