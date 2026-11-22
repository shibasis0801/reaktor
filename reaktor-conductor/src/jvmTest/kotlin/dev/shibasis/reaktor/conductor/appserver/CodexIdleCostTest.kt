package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class CodexIdleCostTest {
    @Test fun anAppTurnWaitsForItsNextTranscriptChange() = runBlocking {
        val home = Files.createTempDirectory("codex-idle-cost").toFile()
        try {
            val thread = "fixture-thread"
            val file = home.resolve("sessions/2026/11/17/rollout-$thread.jsonl").apply { parentFile.mkdirs(); writeText("{}\n") }
            fun event(payload: String) = file.appendText("""{"type":"event_msg","payload":$payload}""" + "\n")
            val request = AgentRequest(AgentSpec(AgentId("codex"), "Codex", RuntimeKind.Codex, ""), "Our question", home.path,
                resume = ProviderSession(RuntimeKind.Codex, thread))
            val session = CodexDesktopSession(request, "stub", this, home, launch = { command ->
                if (command.getOrNull(1) == "queue") {
                    event("""{"type":"item_completed","item":{"type":"UserMessage","content":[{"type":"text","text":"Our question"}]}}""")
                    event("""{"type":"task_started","turn_id":"ours"}""")
                }
                true
            })
            val started = CompletableDeferred<Unit>()
            val job = async { withTimeout(120000) { session.events.onEach { if (it is AgentEvent.TurnStarted) started.complete(Unit) }.toList() } }
            try {
                withTimeout(5000) { started.await() }
                val cpu = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
                val recording = jdk.jfr.Recording().apply { enable("jdk.FileRead").withThreshold(java.time.Duration.ZERO); start() }
                val cpuBefore = cpu.processCpuTime
                val wall = System.nanoTime()
                val active = System.getenv("HANGAR_ORDER13_ACTIVE") == "1"
                val stream = if (active) launch(Dispatchers.IO) {
                    while (isActive) {
                        event("""{"type":"agent_message_delta","delta":"${"x".repeat(1024)}"}""")
                        delay(500)
                    }
                } else null
                delay(System.getenv("HANGAR_ORDER13_SAMPLE_MILLIS")?.toLongOrNull() ?: 2200)
                stream?.cancelAndJoin()
                val elapsed = System.nanoTime() - wall
                val cpuNanos = cpu.processCpuTime - cpuBefore
                recording.stop()
                val receipt = home.resolve("reads.jfr")
                recording.dump(receipt.toPath())
                recording.close()
                val reads = jdk.jfr.consumer.RecordingFile.readAllEvents(receipt.toPath()).filter { it.eventType.name == "jdk.FileRead" && it.getString("path") == file.path }
                val bytes = reads.sumOf { it.getLong("bytesRead").coerceAtLeast(0) }
                System.getenv("HANGAR_ORDER13_COSTS")?.let { output ->
                    File(output).mkdirs()
                    File(output, if (active) "codex-stream-cost.json" else "codex-idle-cost.json").writeText("""{"sampleMillis":${elapsed / 1_000_000},"cpuMillis":${cpuNanos / 1_000_000},"bytesRead":$bytes,"readCalls":${reads.size},"normalizedCpuMillisPerMinute":${cpuNanos / 1_000_000.0 * 60_000_000_000.0 / elapsed},"normalizedBytesPerMinute":${bytes * 60_000_000_000.0 / elapsed}}""")
                }
                if (!active) assertEquals(0, reads.size, "Waiting for a file event must not re-read the transcript")
                event("""{"type":"task_complete","turn_id":"ours","last_agent_message":"An invented answer"}""")
                assertEquals("An invented answer", job.await().filterIsInstance<AgentEvent.Finished>().single().outcome.text)
            } finally { job.cancelAndJoin(); session.close() }
        } finally { home.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
}
