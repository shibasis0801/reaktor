package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.*
import kotlin.test.*

class AntigravityParserTest {
    @Test fun permissionDeniedCannotBeCountedAsSuccessAndCumulativeUsageStaysMarked() {
        val parser = AntigravityEventParser(AgentId("hybrid"))
        parser.onLine("""{"event":"init","conversation_id":"owned","init":{"model":"gemini-test"}}""")
        assertTrue(parser.onLine("""{"event":"step_update","step_update":{"conversation_id":"other","step_index":2,"step_type":"agent_response","text_delta":"wrong"}}""").isEmpty())
        parser.onLine("""{"event":"result","result":{"status":"SUCCESS","response":"Looks fine","denied_actions":[{"action":"command"}],"usage":{"input_tokens":10,"cache_read_tokens":20,"output_tokens":4}}}""")
        val outcome = parser.finish(0, "")
        assertFalse(outcome.ok)
        assertEquals(UsageScope.ProviderSessionTotal, outcome.usage?.scope)
        assertNull(outcome.usage?.cachedInputTokens)
        assertTrue(outcome.attributes.containsKey("usageNotice"))
    }

    @Test fun noBlanketPermissionFlagAndAbsoluteWorkspaceIsGranted() {
        val request = AgentRequest(AgentSpec(AgentId("hybrid"), "Hybrid", RuntimeKind.Gemini, ""), "Inspect graph", "/tmp")
        val argv = antigravityArgv(request)
        assertFalse("--dangerously-skip-permissions" in argv)
        assertEquals("plan", argv[argv.indexOf("--mode") + 1])
        // The harness ignores --mode while slash expansion is disabled, so asking for inspection
        // and then disabling expansion would silently permit writes. The prompt Reaktor builds
        // never begins with a slash, which is the only place expansion reads one.
        assertFalse("--disable-slash-commands" in argv, "This flag would make the requested mode do nothing")
        assertTrue(argv[argv.indexOf("--print") + 1].startsWith("Workspace: "))
        assertTrue("--add-dir" in argv)
        assertFailsWith<IllegalArgumentException> { antigravityArgv(request.copy(agent = request.agent.copy(harnessArgs = listOf("--dangerously-skip-permissions")))) }
    }
}
