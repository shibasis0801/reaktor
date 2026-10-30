package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.workspace.KeepWorkingResult
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class AgentStopHookTest {
    @Test fun nativeStopReadsOnlyTheCurrentOwnersLatestMessage() = fixture { home, rollout -> runBlocking {
        rollout.writeText(user("stop yesterday") + "\n" + user("resume now") + "\n" + assistant("stop") + "\n")
        var seen: String? = null
        val result = AgentStopHook.codex(event(rollout), "codex:falcon", home) {
            seen = it
            KeepWorkingResult(true, "Continue assigned work")
        }
        assertEquals("resume now", seen)
        assertEquals("block", result.getValue("decision").jsonPrimitive.content)
        assertEquals("Continue assigned work", result.getValue("reason").jsonPrimitive.content)
    } }

    @Test fun principalMismatchAmbiguousSessionAndNonStopEventsNeverCallTheOwner() = fixture { home, rollout -> runBlocking {
        rollout.writeText(user("resume") + "\n")
        val events = listOf(event(rollout) to "codex:other", event(rollout) to null,
            JsonObject(event(rollout) + ("thread_id" to JsonPrimitive("other"))) to "codex:falcon",
            JsonObject(event(rollout) + ("hook_event_name" to JsonPrimitive("SessionStart"))) to "codex:falcon")
        events.forEach { (event, principal) ->
            val result = AgentStopHook.codex(event, principal, home) { error("Must not call owner") }
            assertTrue(result.isEmpty())
        }
    } }

    @Test fun managedRunKeepsItsAuthenticatedIdentityAndUsesTheNativeTranscript() = fixture { home, rollout -> runBlocking {
        rollout.writeText(user("resume") + "\n")
        var called = false
        val result = AgentStopHook.codex(event(rollout), "hangar:managed", home) {
            called = true
            KeepWorkingResult(reason = "Paused")
        }
        assertTrue(called)
        assertTrue(result.isEmpty())
    } }

    @Test fun transcriptOutsideTheNativeHomeOrSymlinkEscapeCannotBeRead() = fixture { home, rollout -> runBlocking {
        val outside = home.parentFile.resolve("rollout-2026-10-30T01-02-03-falcon.jsonl")
        outside.writeText(user("resume") + "\n")
        assertTrue(AgentStopHook.codex(event(outside), "codex:falcon", home) { error("Outside transcript") }.isEmpty())
        Files.createSymbolicLink(rollout.toPath(), outside.toPath())
        assertTrue(AgentStopHook.codex(event(rollout), "codex:falcon", home) { error("Symlink escape") }.isEmpty())
    } }

    @Test fun missingTranscriptAndFailedOwnerAllowTheTurnToEnd() = fixture { home, rollout -> runBlocking {
        assertTrue(AgentStopHook.codex(event(rollout), "codex:falcon", home) { error("Missing transcript") }.isEmpty())
        rollout.writeText(user("resume") + "\n")
        var called = false
        assertTrue(AgentStopHook.codex(event(rollout), "codex:falcon", home) { called = true; error("Owner unavailable") }.isEmpty())
        assertTrue(called)
    } }

    @Test fun currentRolloutCanBeFoundWithoutTrustingAnArbitraryPath() = fixture { home, rollout -> runBlocking {
        rollout.writeText(buildJsonObject {
            put("type", "event_msg")
            putJsonObject("payload") { put("type", "user_message"); put("message", "stop please") }
        }.toString() + "\n")
        var seen: String? = null
        val noPath = JsonObject(event(rollout) - "transcript_path")
        val result = AgentStopHook.codex(noPath, "codex:falcon", home) { seen = it; KeepWorkingResult(reason = "Owner stop") }
        assertEquals("stop please", seen)
        assertTrue(result.isEmpty())
    } }

    private fun event(file: File) = buildJsonObject {
        put("session_id", "falcon"); put("hook_event_name", "Stop"); put("transcript_path", file.path)
    }

    private fun user(text: String) = message("user", text)
    private fun assistant(text: String) = message("assistant", text)
    private fun message(role: String, text: String) = buildJsonObject {
        put("type", "response_item")
        putJsonObject("payload") {
            put("type", "message"); put("role", role)
            putJsonArray("content") { add(buildJsonObject { put("type", "input_text"); put("text", text) }) }
        }
    }.toString()

    private fun fixture(test: (File, File) -> Unit) {
        val root = Files.createTempDirectory("agent-stop-hook").toFile()
        try {
            val home = root.resolve(".codex")
            val rollout = home.resolve("sessions/2026/10/30/rollout-2026-10-30T01-02-03-falcon.jsonl")
            rollout.parentFile.mkdirs()
            test(home, rollout)
        } finally { root.deleteTreeSafely(within = root.parentFile) }
    }
}
