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
}
