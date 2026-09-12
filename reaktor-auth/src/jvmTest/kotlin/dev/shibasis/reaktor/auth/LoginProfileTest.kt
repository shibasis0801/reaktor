package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.LoginRequest
import dev.shibasis.reaktor.auth.services.resolvedProfile
import dev.shibasis.reaktor.service.Environment
import kotlinx.serialization.json.*
import kotlin.test.*

class LoginProfileTest {
    @Test fun oldClientsCannotPersistTokensOrAuthorityFieldsAsProfileMetadata() {
        val profile = LoginRequest("provider-token", "app", UserProvider.GOOGLE, givenName = "Ada", environment = Environment.STAGE,
            profile = buildJsonObject {
                put("idToken", "provider-token"); put("accessToken", "access-token")
                put("refreshToken", "refresh-token"); put("emailVerified", true)
                put("roles", JsonArray(listOf(JsonPrimitive("superadmin"))))
                putJsonObject("metadata") { put("token", "nested-token") }
                put("givenName", "old name"); put("familyName", "Lovelace"); put("emailId", "display@example.test")
                put("imageUrl", "https://example.test/avatar.png")
            }).resolvedProfile()
        assertEquals(setOf("givenName", "familyName", "emailId", "imageUrl"), profile.keys)
        assertEquals("Ada", profile["givenName"]?.jsonPrimitive?.content)
        assertFalse(profile.toString().contains("token"))
    }
    @Test fun nonObjectsNestedFieldsAndOversizedDisplayValuesAreDiscarded() {
        fun profile(value: JsonElement) = LoginRequest("token", "app", UserProvider.GOOGLE, profile = value, environment = Environment.STAGE).resolvedProfile()
        assertEquals(JsonObject(emptyMap()), profile(JsonPrimitive("token")))
        assertEquals(JsonObject(emptyMap()), profile(buildJsonObject {
            putJsonObject("givenName") { put("idToken", "token") }
            put("familyName", "a".repeat(257)); put("emailId", false)
        }))
    }
}
