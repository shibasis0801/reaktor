package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.*
import kotlin.test.*

class AntigravityParserTest {
    @Test fun everyAdvertisedEffortCanLaunchBothAntigravityTransports() {
        val help = checkNotNull(javaClass.getResource("/cli/agy-1.4.7.help.txt")).readText()
        val levels = checkNotNull(CliCapabilities.antigravity(RuntimeKind.Gemini, "1.4.7", help).effort?.supported)
        assertTrue(NativeEffort("xhigh") in levels)
        assertTrue(NativeEffort("max") in levels)
        levels.forEach { effort ->
            val request = AgentRequest(AgentSpec(AgentId("gemini"), "Gemini", RuntimeKind.Gemini, "", effort = effort), "Inspect graph", "/tmp")
            listOf(antigravityArgv(request), antigravitySessionArgv(request)).forEach { argv ->
                assertEquals(effort.value, argv[argv.indexOf("--effort") + 1])
            }
        }
    }

    @Test fun authenticationFailureExplainsHowToRecoverAndBlankErrorsKeepStderr() {
        val parser = AntigravityEventParser(AgentId("gemini"))
        parser.onLine("""{"event":"result","result":{"status":"ERROR","error":"authentication failed or timed out"}}""")
        val failure = parser.finish(1, "").failure.orEmpty()
        assertTrue(failure.contains("Run agy in a terminal"))
        assertTrue(failure.contains("Google sign-in"))
        val blank = AntigravityEventParser(AgentId("gemini"))
        blank.onLine("""{"event":"result","result":{"status":"ERROR","error":""}}""")
        assertEquals("native startup failure", blank.finish(1, "native startup failure").failure)
    }

    @Test fun permissionDeniedCannotBeCountedAsSuccessAndCumulativeUsageStaysMarked() {
        val parser = AntigravityEventParser(AgentId("hybrid"))
        parser.onLine("""{"event":"init","conversation_id":"owned","init":{"model":"gemini-test"}}""")
        assertTrue(parser.onLine("""{"event":"step_update","step_update":{"conversation_id":"other","step_index":2,"step_type":"agent_response","text_delta":"wrong"}}""").isEmpty())
        parser.onLine("""{"event":"result","result":{"status":"SUCCESS","response":"Looks fine","denied_actions":[{"action":"command"}],"usage":{"input_tokens":10,"cache_read_tokens":20,"output_tokens":4}}}""")
        val outcome = parser.finish(0, "")
        assertFalse(outcome.ok)
        assertEquals("Antigravity could not obtain required permissions to run a command.", outcome.failure)
        assertEquals(UsageScope.ProviderSessionTotal, outcome.usage?.scope)
        assertNull(outcome.usage?.cachedInputTokens)
        assertTrue(outcome.attributes.containsKey("usageNotice"))
    }

    @Test fun everyTurnSkipsPermissionPromptsAndAnInspectionStaysInPlanMode() {
        val request = AgentRequest(AgentSpec(AgentId("hybrid"), "Hybrid", RuntimeKind.Gemini, ""), "Inspect graph", "/tmp")
        val argv = antigravityArgv(request)
        assertTrue("--dangerously-skip-permissions" in argv)
        assertEquals("plan", argv[argv.indexOf("--mode") + 1])
        val editing = antigravityArgv(request.copy(agent = request.agent.copy(tools = ToolPolicy(allowWrites = true))))
        assertTrue("--dangerously-skip-permissions" in editing)
        assertEquals("accept-edits", editing[editing.indexOf("--mode") + 1])
        // The harness ignores --mode while slash expansion is disabled, so asking for inspection
        // and then disabling expansion would silently permit writes. The prompt Reaktor builds
        // never begins with a slash, which is the only place expansion reads one.
        assertFalse("--disable-slash-commands" in argv, "This flag would make the requested mode do nothing")
        assertTrue(argv[argv.indexOf("--print") + 1].startsWith("Workspace: "))
        assertTrue("--add-dir" in argv)
        assertFailsWith<IllegalArgumentException> { antigravityArgv(request.copy(agent = request.agent.copy(harnessArgs = listOf("--dangerously-skip-permissions")))) }
    }
}
