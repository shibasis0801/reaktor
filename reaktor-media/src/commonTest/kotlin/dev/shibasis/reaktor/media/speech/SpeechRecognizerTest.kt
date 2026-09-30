package dev.shibasis.reaktor.media.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for [SpeechRecognizer], exercised through [InMemorySpeechRecognizer] — the same way
 * `InMemoryPermissionAdapter` stands in for the platform permission adapters. These pin the session
 * state machine every platform impl must honour; the live engines are verified on-device.
 */
class SpeechRecognizerTest {

    @Test
    fun startThenStopTogglesListening() {
        val rec = InMemorySpeechRecognizer()
        assertFalse(rec.isListening())
        rec.start()
        assertTrue(rec.isListening())
        rec.stop()
        assertFalse(rec.isListening())
    }

    @Test
    fun cancelStopsListening() {
        val rec = InMemorySpeechRecognizer()
        rec.start()
        rec.cancel()
        assertFalse(rec.isListening())
    }

    @Test
    fun aFinalResultEndsTheSession() {
        val rec = InMemorySpeechRecognizer()
        rec.start()
        rec.feedResult("highlight this sentence")
        assertFalse(rec.isListening()) // a delivered result closes the session
    }

    @Test
    fun anUnavailableRecognizerNeverListens() {
        val rec = InMemorySpeechRecognizer(available = false)
        assertFalse(rec.isAvailable())
        rec.start()
        assertFalse(rec.isListening()) // start is refused; callers fall back to typed input
    }

    @Test
    fun startRemembersTheRequest() {
        val rec = InMemorySpeechRecognizer()
        assertNull(rec.lastRequest)
        rec.start(RecognitionRequest(language = "en-GB", preferOnDevice = true, partialResults = false))
        assertEquals("en-GB", rec.lastRequest?.language)
        assertEquals(true, rec.lastRequest?.preferOnDevice)
        assertEquals(false, rec.lastRequest?.partialResults)
    }
}
