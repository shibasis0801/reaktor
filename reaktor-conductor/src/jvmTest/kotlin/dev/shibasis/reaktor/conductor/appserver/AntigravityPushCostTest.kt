package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.AntigravityApp
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class AntigravityPushCostTest {
    @Test fun anUnchangedTranscriptDoesNotDownloadTrajectoriesAgain() = runBlocking {
        val home = Files.createTempDirectory("gemini-push-cost").toFile()
        val calls = AtomicInteger()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            home.resolve("app/Contents/Resources/bin/language_server").apply {
                parentFile.mkdirs(); writeText("#!/bin/sh\nprintf '{}'\n"); setExecutable(true)
            }
            val app = AntigravityApp(home, home.resolve("app"), alive = { true }, transport = { _, method, _ ->
                Json.parseToJsonElement(when (method) {
                    "ListProjects" -> """{"projectIds":["fixture"]}"""
                    "ReadProject" -> """{"project":{"projectResources":{"resources":[{"folderUri":"file://${home.canonicalPath}","allowWrite":true}]}}}"""
                    "GetAllCascadeTrajectories" -> { calls.incrementAndGet(); """{"trajectorySummaries":{"fixture":{"status":"CASCADE_RUN_STATUS_${if (finished.get()) "IDLE" else "RUNNING"}","stepCount":${if (finished.get()) 4 else 1},"latestExecutorTerminationReason":"EXECUTOR_TERMINATION_REASON_NO_TOOL_CALL"}}}""" }
                    else -> "{}"
                }).jsonObject
            })
            home.resolve(".gemini/antigravity/daemon/hub_1.json").apply { parentFile.mkdirs(); writeText("""{"pid":1,"httpPort":1,"csrfToken":"invented"}""") }
            val transcript = app.transcript("fixture").apply { parentFile.mkdirs(); writeText("""{"step_index":0,"type":"USER_INPUT","content":"Earlier"}""" + "\n") }
            val request = AgentRequest(AgentSpec(AgentId("gemini"), "Gemini", RuntimeKind.Gemini, ""), "Continue", home.path, resume = ProviderSession(RuntimeKind.Gemini, "fixture"))
            val session = AntigravityAppRuntime(app).open(request)
            try {
                val job = async { withTimeout(120000) { session.events.toList() } }
                withTimeout(3000) { while (calls.get() < 3) yield() }
                val before = calls.get()
                val cpu = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
                val cpuBefore = cpu.processCpuTime
                val wall = System.nanoTime()
                val recording = jdk.jfr.Recording().apply { enable("jdk.FileRead").withThreshold(java.time.Duration.ZERO); start() }
                val active = System.getenv("HANGAR_ORDER13_ACTIVE") == "1"
                val stream = if (active) launch(Dispatchers.IO) {
                    var index = 1
                    while (isActive) {
                        transcript.appendText("""{"step_index":$index,"type":"PLANNER_RESPONSE","content":"${"x".repeat(1024)}"}""" + "\n")
                        index++
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
                val bytes = jdk.jfr.consumer.RecordingFile.readAllEvents(receipt.toPath()).filter { event -> event.eventType.name == "jdk.FileRead" && event.getString("path")?.startsWith(home.path) == true }.sumOf { it.getLong("bytesRead").coerceAtLeast(0) }
                System.getenv("HANGAR_ORDER13_COSTS")?.let { output ->
                    File(output).mkdirs()
                    File(output, if (active) "gemini-stream-cost.json" else "gemini-idle-cost.json").writeText("""{"sampleMillis":${elapsed / 1_000_000},"cpuMillis":${cpuNanos / 1_000_000},"bytesRead":$bytes,"trajectoryRequests":${calls.get() - before},"normalizedCpuMillisPerMinute":${cpuNanos / 1_000_000.0 * 60_000_000_000.0 / elapsed},"normalizedBytesPerMinute":${bytes * 60_000_000_000.0 / elapsed}}""")
                }
                if (!active) assertEquals(before, calls.get(), "An unchanged transcript re-downloaded its trajectory")
                finished.set(true)
                transcript.appendText("""{"step_index":1,"type":"PLANNER_RESPONSE","content":"The whole answer"}""" + "\n" + """{"step_index":2,"type":"CHECKPOINT","status":"DONE"}""" + "\n")
                val answer = job.await().filterIsInstance<AgentEvent.Finished>().single().outcome.text
                if (active) assertTrue(answer.endsWith("The whole answer")) else assertEquals("The whole answer", answer)
            } finally { session.close() }
        } finally { home.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
}
