package dev.shibasis.reaktor.flexbuffer.flatbuffers

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FastDecodeJvmTest {
    @Test
    fun compactAsciiLengthsCoverWordBoundariesAndTails() {
        boundaryLengths.forEach { length ->
            val value = buildString(length) {
                repeat(length) { append(('a'.code + it % 26).toChar()) }
            }
            assertLength(value, length)
        }
    }

    @Test
    fun compactLatin1CountsEveryHighByteAcrossWordsAndTails() {
        boundaryLengths.forEach { length ->
            val alternating = buildString(length) {
                repeat(length) { append(if (it % 3 == 0) '\u00e9' else ('a'.code + it % 26).toChar()) }
            }
            val allHigh = "\u00ff".repeat(length)

            assertLength(alternating, alternating.encodeToByteArray().size)
            assertLength(allHigh, length * 2)
        }
    }

    @Test
    fun utf16BmpAndSurrogatePairsRetainPortableSemantics() {
        listOf(
            "ASCII-and-\u20ac-and-\u6771",
            "start-\ud83d\ude00-end",
            "\u0800\uffff\ud83d\ude80",
        ).forEach { value ->
            assertLength(value, value.encodeToByteArray().size)
        }
    }

    @Test
    fun nonStringCharSequencesUseThePortablePath() {
        listOf(
            StringBuilder("plain-ascii"),
            StringBuilder("caf\u00e9"),
            StringBuilder("\u20ac-\u6771-\ud83d\ude00"),
        ).forEach { value ->
            assertLength(value, value.toString().encodeToByteArray().size)
        }
    }

    @Test
    fun malformedSurrogatesStillFail() {
        listOf("\ud800", "prefix-\udc00", "\ud800-x").forEach { value ->
            assertFailsWith<IllegalStateException> { fastEncodedLength(value) }
            assertFailsWith<IllegalStateException> { fastEncodedLength(StringBuilder(value)) }
        }
    }

    @Test
    fun knownLengthEncodingMatchesUtf8AtAnOffset() {
        listOf(
            "short",
            "12345678",
            "caf\u00e9-\u00ff",
            "\u20ac-\u6771-\ud83d\ude00",
        ).forEach { value ->
            val expected = value.encodeToByteArray()
            val offset = 3
            val out = ByteArray(offset + expected.size + 2) { 0x5a }
            val end = fastEncodeUtf8KnownLength(value, out, offset, expected.size)

            assertEquals(offset + expected.size, end, "end position for '$value'")
            assertContentEquals(expected, out.copyOfRange(offset, end), "encoded bytes for '$value'")
            assertEquals(0x5a.toByte(), out[0])
            assertEquals(0x5a.toByte(), out[end])
        }
    }

    private fun assertLength(value: CharSequence, expected: Int) {
        assertEquals(expected, fastEncodedLength(value), "UTF-8 length for '$value'")
        assertEquals(Utf8.encodedLength(value), fastEncodedLength(value), "portable parity for '$value'")
    }

    private companion object {
        val boundaryLengths = listOf(0, 1, 2, 7, 8, 9, 15, 16, 17)
    }
}
