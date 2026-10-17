package dev.shibasis.reaktor.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CrashlyticsAdapterTest {
    private class RecordingAdapter : CrashlyticsAdapter<Unit>(Unit) {
        val keys = mutableMapOf<String, String>()
        var user = ""
        override fun recordException(throwable: Throwable) = Unit
        override fun log(message: String) = Unit
        override fun setUserId(userId: String) { user = userId }
        override fun writeCustomKey(key: String, value: String) { keys[key] = value }
    }

    @Test
    fun rejectedContextDoesNotPartiallyWriteOrLeakPayloads() {
        val adapter = RecordingAdapter()
        for (values in listOf(
            mapOf("request_body" to "private"),
            mapOf("reaktor.route" to "/feed?token=private"),
            mapOf("reaktor.application_session" to "x".repeat(129)),
            mapOf("reaktor.platform" to "android", "reaktor.route" to "line\nsecret"),
        )) {
            assertFailsWith<IllegalArgumentException> { adapter.setContext(values) }
            assertTrue(adapter.keys.isEmpty())
        }
    }

    @Test
    fun nullClearsAKeyAndResetClearsEveryOwnedKeyAndUser() {
        val adapter = RecordingAdapter()
        adapter.setContext(mapOf("reaktor.application_session" to "session-a", "reaktor.graph_digest" to null))
        assertEquals("session-a", adapter.keys["reaktor.application_session"])
        assertEquals("", adapter.keys["reaktor.graph_digest"])
        adapter.setUserId("user-a")
        adapter.clearContext()
        assertEquals(CrashlyticsAdapter.ContextKeys, adapter.keys.keys)
        assertTrue(adapter.keys.values.all(String::isEmpty))
        assertEquals("", adapter.user)
    }
}
