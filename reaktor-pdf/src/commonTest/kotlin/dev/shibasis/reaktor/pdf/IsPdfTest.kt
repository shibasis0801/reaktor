package dev.shibasis.reaktor.pdf

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsPdfTest {
    @Test
    fun aPdfStartsWithItsMarker() {
        assertTrue(isPdf("%PDF-1.7\n".encodeToByteArray()))
        assertTrue(isPdf(SamplePdf.of(listOf("anything"))))
    }

    /** Mail gateways and old scanners put things in front. The format allows it, within reason. */
    @Test
    fun theMarkerMaySitALittleWayIn() {
        assertTrue(isPdf(ByteArray(300) { ' '.code.toByte() } + "%PDF-1.4".encodeToByteArray()))
        assertTrue(isPdf(ByteArray(1024) + "%PDF-1.4".encodeToByteArray()))
        assertFalse(isPdf(ByteArray(1025) + "%PDF-1.4".encodeToByteArray()))
    }

    @Test
    fun otherThingsAreNotPdfs() {
        assertFalse(isPdf(ByteArray(0)))
        assertFalse(isPdf("%PDF".encodeToByteArray()))
        assertFalse(isPdf("PDF-1.7".encodeToByteArray()))
        assertFalse(isPdf("a note that mentions %PDF but not at once: ".encodeToByteArray() + ByteArray(2000)))
    }
}
