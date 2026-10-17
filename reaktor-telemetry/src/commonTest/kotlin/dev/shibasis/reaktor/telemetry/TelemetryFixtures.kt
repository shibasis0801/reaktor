package dev.shibasis.reaktor.telemetry

/** Contract under test. Lives outside the test class because the runner scans by class name. */
class Sender {
    var calls = 0
    fun send(message: String): String { calls++; return "sent:$message" }
    suspend fun sendLater(message: String): String { calls++; return "sent:$message" }
    fun fail(): String { calls++; error("boom") }
}
