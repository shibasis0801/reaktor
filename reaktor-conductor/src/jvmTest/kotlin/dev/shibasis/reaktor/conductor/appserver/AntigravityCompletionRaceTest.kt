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

class AntigravityCompletionRaceTest {
    @Test fun theStatusCanBecomeIdleAfterTheLastTranscriptWrite() = fixture(false)
    @Test fun aTemporaryStatusReadFailureDoesNotLoseTheIdleRecheck() = fixture(true)

    private fun fixture(failStatus: Boolean) = runBlocking {
        val home = Files.createTempDirectory("gemini-push-cost").toFile()
        val calls = AtomicInteger()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            home.resolve("app/Contents/Resources/bin/language_server").apply {
                parentFile.mkdirs(); writeText("#!/bin/sh\nprintf '{}'\n"); setExecutable(true)
            }
            val app = AntigravityApp(home, home.resolve("app"), alive = { true }, transport = { _, method, _ ->
                Json.parseToJsonElement(when (method) {
                    "ListProjects" -> """{"projectIds":["fixture"]}"""
                    "ReadProject" -> """{"project":{"projectResources":{"resources":[{"folderUri":"file://${home.canonicalPath}","allowWrite":true}]}}}"""
                    "GetAllCascadeTrajectories" -> { calls.incrementAndGet(); if (failStatus && finished.get() && !failed.getAndSet(true)) error("Invented temporary status failure"); """{"trajectorySummaries":{"fixture":{"status":"CASCADE_RUN_STATUS_${if (finished.get()) "IDLE" else "RUNNING"}","stepCount":${if (finished.get()) 4 else 1},"latestExecutorTerminationReason":"EXECUTOR_TERMINATION_REASON_NO_TOOL_CALL"}}}""" }
                    else -> "{}"
                }).jsonObject
            })
            home.resolve(".gemini/antigravity/daemon/hub_1.json").apply { parentFile.mkdirs(); writeText("""{"pid":1,"httpPort":1,"csrfToken":"invented"}""") }
            val transcript = app.transcript("fixture").apply { parentFile.mkdirs(); writeText("""{"step_index":0,"type":"USER_INPUT","content":"Earlier"}""" + "\n") }
            val request = AgentRequest(AgentSpec(AgentId("gemini"), "Gemini", RuntimeKind.Gemini, ""), "Continue", home.path, resume = ProviderSession(RuntimeKind.Gemini, "fixture"))
            val session = AntigravityAppRuntime(app).open(request)
            try {
                val job = async { session.events.toList() }
                try {
                withTimeout(3000) { while (calls.get() < 3) yield() }
                val read = calls.get()
                transcript.appendText("""{"step_index":1,"type":"PLANNER_RESPONSE","content":"The whole answer"}""" + "\n" + """{"step_index":2,"type":"CHECKPOINT","status":"DONE"}""" + "\n")
                withTimeout(5000) { while (calls.get() <= read) yield() }
                delay(100)
                finished.set(true)
                val completed = withTimeoutOrNull(3000) { job.await() }
                System.getenv("HANGAR_ORDER15_COSTS")?.let { destination -> File(destination).apply { mkdirs() }.resolve("gemini-completion.json").writeText(buildJsonObject { put("finishedWithin3Seconds", completed != null) }.toString()) }
                val answer = assertNotNull(completed, "The last transcript line preceded idle status; the turn did not end within three seconds").filterIsInstance<AgentEvent.Finished>().single().outcome.text
                assertEquals("The whole answer", answer)
            } finally { job.cancelAndJoin() }
            } finally { session.close() }
        } finally { home.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
}
