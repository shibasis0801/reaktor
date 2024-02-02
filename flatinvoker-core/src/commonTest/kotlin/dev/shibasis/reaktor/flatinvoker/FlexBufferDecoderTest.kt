package dev.shibasis.reaktor.flatinvoker

import dev.shibasis.reaktor.flatinvoker.flexbuffer.decodeFromList
import dev.shibasis.reaktor.flatinvoker.flexbuffer.encodeToList
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