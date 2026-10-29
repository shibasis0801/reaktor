package dev.shibasis.reaktor.ui.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MediaContentTest {
    @Test fun contentTypesKeepParametersAndStructuredSuffixesOutOfRouting() {
        mapOf("IMAGE/PNG; charset=binary" to MediaKind.Image, "audio/mp4" to MediaKind.Audio,
            "video/mp4" to MediaKind.Video, "application/problem+json" to MediaKind.Json,
            "text/csv;charset=utf-8" to MediaKind.Csv, "application/pdf" to MediaKind.Pdf,
            "text/x-kotlin" to MediaKind.Text, "application/octet-stream" to MediaKind.Binary,
            "application/unknown" to MediaKind.Unsupported).forEach { (mime, expected) -> assertEquals(expected, mediaKind(mime)) }
    }

    @Test fun csvPreservesQuotedCommasNewlinesAndEscapedQuotes() {
        assertEquals(listOf(listOf("name", "note"), listOf("one,two", "a\r\nb"), listOf("quote", "say \"hi\"")),
            csvRecords("name,note\r\n\"one,two\",\"a\r\nb\"\r\nquote,\"say \"\"hi\"\"\"\r\n"))
    }

    @Test fun csvKeepsTrailingFieldsAndEmptyRecords() {
        assertEquals(emptyList(), csvRecords(""))
        assertEquals(listOf(listOf("", "")), csvRecords(","))
        assertEquals(listOf(listOf("")), csvRecords("\n"))
        assertEquals(listOf(listOf("a", ""), listOf("b")), csvRecords("a,\nb"))
        assertEquals(listOf(listOf("")), csvRecords("\"\""))
    }

    @Test fun malformedCsvExplainsTheFailure() {
        listOf("\"open", "a\"b", "\"a\"b").forEach { assertFailsWith<IllegalArgumentException> { csvRecords(it) } }
    }

    @Test fun binaryOffsetsAndUnsignedBytesStayCorrectAcrossRows() {
        val text = mediaHex(byteArrayOf(0, 127, -1, 65) + ByteArray(13) { 32 })
        assertTrue(text.startsWith("00000000  00 7f ff 41"), text)
        assertTrue(text.contains("...A"), text)
        assertTrue(text.lines()[1].startsWith("00000010  20"), text)
        assertEquals("", mediaHex(byteArrayOf()))
    }

    @Test fun nativePlayersUseControlsAndEscapeTheSourceAttribute() {
        val source = "https://example.test/a?x=\"<&"
        assertTrue("<audio controls" in mediaHtml(MediaKind.Audio, source))
        assertTrue("<video controls" in mediaHtml(MediaKind.Video, source))
        assertTrue("&quot;&lt;&amp;" in mediaHtml(MediaKind.Video, source))
        assertTrue("application/pdf" in mediaHtml(MediaKind.Pdf, source))
        assertFailsWith<IllegalStateException> { mediaHtml(MediaKind.Text, source) }
    }

    @Test fun remoteSourcesRequireAnHttpHost() {
        assertEquals("https://example.test/file", MediaSource.Url("https://example.test/file").url)
        listOf("file:///etc/passwd", "javascript:alert(1)", "https:/file", "/file").forEach {
            assertFailsWith<IllegalArgumentException> { MediaSource.Url(it) }
        }
    }
}
