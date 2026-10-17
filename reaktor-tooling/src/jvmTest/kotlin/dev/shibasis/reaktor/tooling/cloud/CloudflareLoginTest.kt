package dev.shibasis.reaktor.tooling.cloud

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class CloudflareLoginTest {
    @Test fun invalidSavedTokenRecoversThroughExistingSignInOnce() = runBlocking {
        var refreshes = 0
        val login = CloudflareLogin("config/cloudflare.token", CloudflareApiToken("invalid"), CloudflareCredentials { refreshes++; "signed-in" })
        val attempts = mutableListOf<String>()
        repeat(2) {
            assertEquals(200, login.authenticated({ token -> attempts += token; if (token == "invalid") 401 else 200 }, { it == 401 }))
        }
        assertEquals(listOf("invalid", "signed-in", "signed-in"), attempts)
        assertEquals(1, refreshes)
        assertEquals("Wrangler sign-in", login.source)
    }

    @Test fun permissionRefusalsKeepTheSelectedCredential() = runBlocking {
        val login = CloudflareLogin("saved", CloudflareApiToken("limited"), CloudflareCredentials { error("Must not replace a permission refusal") })
        assertEquals(403, login.authenticated({ 403 }, { it == 401 }))
        assertEquals("saved", login.source)
    }
}
