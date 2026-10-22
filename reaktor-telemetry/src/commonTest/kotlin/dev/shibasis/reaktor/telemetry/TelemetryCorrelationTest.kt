package dev.shibasis.reaktor.telemetry

import dev.shibasis.reaktor.service.ServiceExecutionPhase
import dev.shibasis.reaktor.service.ServiceSpan
import kotlin.test.*

class TelemetryCorrelationTest {
    private class Recorder : CrashlyticsAdapter<Unit>(Unit) {
        val keys = mutableMapOf<String, String>()
        val messages = mutableListOf<String>()
        override fun recordException(throwable: Throwable) = Unit
        override fun log(message: String) { messages += message }
        override fun setUserId(userId: String) = Unit
        override fun writeCustomKey(key: String, value: String) { keys[key] = value }
    }
    private fun span() = ServiceSpan("1".repeat(32), "2".repeat(16), "", ServiceExecutionPhase.CLIENT,
        "client", "contract", "private?payload=secret", 100, 1.0, 500, node = "node-1", graphDigest = "a".repeat(64))

    @Test fun identityIsSharedAcrossSpansAndCrashBreadcrumbsWithoutOperationPayloads() {
        val identity = TelemetryIdentity("manna", "installation-1", "android")
        val recorder = Recorder()
        recorder.logSpan(identity.attribute(span()))
        assertEquals("installation-1", recorder.keys["reaktor.installation"])
        assertEquals("manna", recorder.keys["reaktor.application"])
        assertEquals("1".repeat(32), recorder.keys["reaktor.trace_id"])
        assertEquals("node-1", recorder.keys["reaktor.node"])
        assertTrue(recorder.messages.single().contains("reaktor.span_id=" + "2".repeat(16)))
        assertFalse(recorder.messages.single().contains("private"))
        recorder.clearContext()
        assertTrue(recorder.keys.values.all(String::isEmpty))
    }

    @Test fun invalidInstallationAndPayloadBearingCrashMetadataAreRejected() {
        assertFailsWith<IllegalArgumentException> { TelemetryIdentity("manna", "email@example.test", "android") }
        assertFailsWith<IllegalArgumentException> { TelemetryIdentity("manna", "install", "phone-secret") }
        val recorder = Recorder()
        assertFailsWith<IllegalArgumentException> { recorder.logSpan(TelemetryIdentity("manna", "install", "ios").attribute(span().copy(node = "private?token=secret"))) }
        assertTrue(recorder.keys.isEmpty())
        assertTrue(recorder.messages.isEmpty())
    }
}
