package dev.shibasis.flatinvoker.core

import dev.shibasis.flatinvoker.core.ListSerializer.decodeFromList
import dev.shibasis.flatinvoker.core.ListSerializer.encodeToList
import kotlin.test.Test
import kotlin.test.assertEquals

class ListDecoderTest {
    @Test
    fun testDecoding() {
        val testCase = EncodingBasicTestCase()
        val encoded = encodeToList(testCase)
        val decoded = decodeFromList<EncodingBasicTestCase>(encoded)
        assertEquals("Shibasis", decoded.name)
        assertEquals(1, decoded.id)
        assertEquals(22.5, decoded.temperature)
        assertEquals(false, decoded.isRaining)
    }
}