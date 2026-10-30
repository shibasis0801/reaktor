package dev.shibasis.reaktor.conductor.cli

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AgentContextHookTest {
    @Test fun sessionStartRecallsAndDeliversOnlyTheAuthenticatedInbox() = runBlocking {
        for ((harness, principal) in listOf(AgentPluginBundle.Harness.Claude to "claude:proof", AgentPluginBundle.Harness.Codex to "codex:proof")) {
            val called = mutableListOf<String>()
            val output = AgentContextHook.read(harness, event("SessionStart"), principal,
                inbox = { called += "inbox:$it"; "message:one" }, recall = { called += "recall:$it"; "memory:one provenance=source" })
            assertEquals(listOf("recall:$principal", "inbox:$principal"), called)
            val result = output.getValue("hookSpecificOutput").jsonObject
            assertEquals("SessionStart", result.getValue("hookEventName").jsonPrimitive.content)
            assertEquals("message:one\n\nmemory:one provenance=source", result.getValue("additionalContext").jsonPrimitive.content)
            assertNull(output["decision"])
        }
    }

    @Test fun promptAndToolHooksDeliverWithoutRepeatedRecall() = runBlocking {
        for (name in listOf("UserPromptSubmit", "PostToolUse")) {
            val output = AgentContextHook.read(AgentPluginBundle.Harness.Codex, event(name), "codex:proof",
                inbox = { "a".repeat(9000) }, recall = { error("Only recall at session start") })
            assertEquals(8000, output.getValue("hookSpecificOutput").jsonObject.getValue("additionalContext").jsonPrimitive.content.length)
        }
    }

    @Test fun foreignMissingAndInconsistentIdentitiesNeverReadOrConsumeContext() = runBlocking {
        var calls = 0
        for ((input, principal) in listOf(event("SessionStart") to "codex:other", event("SessionStart") to null,
            event("Stop") to "codex:proof", buildJsonObject { put("session_id", "../../other"); put("hook_event_name", "SessionStart") } to "codex:proof",
            JsonObject(event("SessionStart") + ("thread_id" to JsonPrimitive("other"))) to "codex:proof")) {
            assertTrue(AgentContextHook.read(AgentPluginBundle.Harness.Codex, input, principal,
                inbox = { calls++; null }, recall = { calls++; null }).isEmpty())
        }
        assertTrue(AgentContextHook.read(AgentPluginBundle.Harness.Antigravity, event("SessionStart"), "agy:proof",
            inbox = { calls++; null }, recall = { calls++; null }).isEmpty())
        assertEquals(0, calls)
    }

    @Test fun managedIdentityIsPreservedAndRecallFailureDoesNotLoseTheInbox() = runBlocking {
        val output = AgentContextHook.read(AgentPluginBundle.Harness.Codex, event("SessionStart"), "hangar:run",
            inbox = { assertEquals("hangar:run", it); "message:pending" }, recall = { error("Owner unavailable") })
        assertEquals("message:pending", output.getValue("hookSpecificOutput").jsonObject.getValue("additionalContext").jsonPrimitive.content)
        assertTrue(AgentContextHook.read(AgentPluginBundle.Harness.Claude, event("SessionStart"), "claude:proof",
            inbox = { null }, recall = { null }).isEmpty())
    }

    @Test fun cancellationIsNotConvertedToSuccessfulContextDelivery(): Unit = runBlocking {
        assertFailsWith<CancellationException> {
            AgentContextHook.read(AgentPluginBundle.Harness.Codex, event("SessionStart"), "codex:proof",
                inbox = { null }, recall = { throw CancellationException("stopped") })
        }
    }

    private fun event(name: String) = buildJsonObject { put("session_id", "proof"); put("hook_event_name", name) }
}
