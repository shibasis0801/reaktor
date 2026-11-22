package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.TokenSet
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StoredAuthSessionProfileCompatibilityTest {
    @Test
    fun sessionsSavedWithoutAProfileDeserializeWithTheLegacyDefault() {
        val session = storedSession()
        val encoded = Json.encodeToJsonElement(StoredAuthSession.serializer(), session) as JsonObject
        val legacy = JsonObject(encoded.filterKeys { it != "profile" })
        val restored = Json.decodeFromJsonElement(StoredAuthSession.serializer(), legacy)
        assertNull(restored.profile)
        assertEquals(session.context, restored.context)
        assertEquals(session.tokens, restored.tokens)
        assertEquals(session.expiresAtEpochMillis, restored.expiresAtEpochMillis)
    }

    @Test
    fun theCurrentSessionShapeRoundTripsItsProfile() {
        val profile = buildJsonObject { put("name", JsonPrimitive("Synthetic participant")) }
        val session = storedSession().copy(profile = profile)
        val restored = Json.decodeFromString(
            StoredAuthSession.serializer(),
            Json.encodeToString(StoredAuthSession.serializer(), session),
        )
        assertEquals(session, restored)
        assertEquals(profile, restored.profile)
    }

    private fun storedSession() = StoredAuthSession(
        context = AuthContext(
            principal = PrincipalRef("compatibility-user", PrincipalKind.USER),
            appId = "app",
            audience = "app",
            method = AuthMethod.ACCESS_TOKEN,
        ),
        tokens = TokenSet("synthetic-access"),
        expiresAtEpochMillis = 1_800_000_000_000,
    )
}
