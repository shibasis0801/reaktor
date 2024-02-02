package dev.shibasis.reaktor.flatinvoker

import dev.shibasis.reaktor.flatinvoker.flexbuffer.encodeToList
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
data class EncodingBasicTestCase(
    val name: String = "Shibasis",
    val id: Int = 1,
    val temperature: Double = 22.5,
    val isRaining: Boolean = false
)

class ListEncoderTest {
    @Test
    fun testEncoding() {
        val testCase = EncodingBasicTestCase()
        val encoded = encodeToList(testCase)
        assertEquals(4, encoded.size)
        assertEquals("Shibasis", encoded[0])
        assertEquals(1, encoded[1])
        assertEquals(22.5, encoded[2])
        assertEquals(false, encoded[3])
    }
}