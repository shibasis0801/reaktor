package dev.shibasis.reaktor.blueprint.editor

import kotlin.test.Test
import kotlin.test.assertEquals

class RedactionTest {
    @Test
    fun runValuesHideSecretsOnEveryPlatform() {
        assertEquals("""{"token":•••,"name":"x"}""", redacted("""{"token":"abc123","name":"x"}"""))
        assertEquals("Authorization: •••", redacted("Authorization: Bearer abc.def"))
        assertEquals("AUTHORIZATION: •••", redacted("AUTHORIZATION: bearer abc.def"))
        assertEquals("sent Bearer •••", redacted("sent Bearer abc.def"))
        assertEquals("call ••• ok", redacted("call eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.c2lnbmF0dXJl ok"))
        assertEquals("plain text stays", redacted("plain text stays"))
    }
}
