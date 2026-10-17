package dev.shibasis.reaktor.devtools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LogRedactionTest {
    @Test
    fun onlyTheFirstLineOfAMessageIsKept() {
        assertEquals("REQUEST: https://example.test/auth/session/refresh", "REQUEST: https://example.test/auth/session/refresh\n{\"body\":1}".redacted())
    }

    @Test
    fun tokensAreMasked() {
        val line = "sent refreshToken=\"rkr_abcDEF-123\" with eyJhbGciOi.eyJzdWIiOi.c2lnbmF0dXJl and Authorization: Bearer opaque-value"
        val redacted = line.redacted()
        assertFalse("rkr_abcDEF-123" in redacted)
        assertFalse("eyJhbGciOi" in redacted)
        assertFalse("opaque-value" in redacted)
        assertEquals("sent refreshToken=\"***\" with *** and Authorization: ***", redacted)
    }

    @Test
    fun jsonTokenFieldsAreMasked() {
        assertEquals("{\"accessToken\":\"***\",\"expiresInSeconds\":900}", "{\"accessToken\":\"secret-value\",\"expiresInSeconds\":900}".redacted())
    }

    @Test
    fun quotedSecretsWithSpacesAndEscapesAreMaskedAsOneValue() {
        assertEquals("password=\"***\" route=/home", "password=\"private multi \\\"word\\\" value\" route=/home".redacted())
        assertEquals("refresh_token='***'", "refresh_token='private value'".redacted())
    }

    @Test
    fun basicAuthorizationAndUrlsDropCredentialLocations() {
        assertEquals("Authorization: ***", "Authorization: Basic private-value".redacted())
        assertEquals("GET https://example.test/home", "GET https://private-user:private-password@example.test/home?opaque=private-query#private-fragment".redacted())
        assertEquals("/home", "/home?opaque=private-query#private-fragment".redactedUrl())
    }
}
