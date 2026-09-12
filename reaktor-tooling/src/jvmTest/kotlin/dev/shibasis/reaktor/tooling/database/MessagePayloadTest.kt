package dev.shibasis.reaktor.tooling.database

import kotlin.test.*

class MessagePayloadTest {
    @Test fun decodingRejectsInvalidUtf8AndOversizedInputWithoutConfusingIdentity() {
        assertEquals("α", MessagePayload.decode("zrE=", MessageDecoder.Base64Utf8))
        assertFails { MessagePayload.decode("/w==", MessageDecoder.Base64Utf8) }
        assertFails { MessagePayload.decode("x".repeat(1_048_577), MessageDecoder.Text) }
        assertNotEquals(MessagePayload.identity(listOf(null)), MessagePayload.identity(listOf("null")))
        assertNotEquals(MessagePayload.identity(listOf("a", "b")), MessagePayload.identity(listOf("a,b")))
    }
}
