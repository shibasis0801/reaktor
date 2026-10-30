package dev.shibasis.reaktor.conductor

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConductorResumeTest {
    private val agent = AgentSpec(AgentId("worker"), "Worker", RuntimeKind.Codex, "Implement")
    private val session = ProviderSession(RuntimeKind.Codex, "saved-provider-session")
    private fun interrupted() = ThreadDocument(ThreadId("conversation"), "Work", listOf(agent),
        events = listOf(ThreadEvent(EventId("interruption"), Author.Orchestrator("recovery"), EventKind.Failure, "Interrupted")),
        providerSessions = mapOf(agent.id.value to session))

    @Test fun anInterruptedTurnResumesTheSavedProviderSession() = runTest {
        var request: AgentRequest? = null
        val runtime = EchoRuntime(RuntimeKind.Codex) { request = it; "Continued" }
        Conductor(mapOf(RuntimeKind.Codex to runtime)).run(interrupted(), "Continue", Protocol.Ask(agent.id), ".", resumeProviderSession = true)
        assertEquals(session, request?.resume)
        assertTrue(request?.persistSession == true)
    }

    @Test fun aSessionForAnotherRuntimeIsNotResumed() = runTest {
        var request: AgentRequest? = null
        val runtime = EchoRuntime(RuntimeKind.Codex) { request = it; "Fresh" }
        val thread = interrupted().copy(providerSessions = mapOf(agent.id.value to ProviderSession(RuntimeKind.ClaudeCode, "wrong-runtime")))
        Conductor(mapOf(RuntimeKind.Codex to runtime)).run(thread, "Continue", Protocol.Ask(agent.id), ".", resumeProviderSession = true)
        assertNull(request?.resume)
    }

    @Test fun continuationRemainsLimitedToSingleAgentAsk() = runTest {
        assertFailsWith<IllegalArgumentException> {
            Conductor(emptyMap()).run(interrupted(), "Continue", Protocol.All(listOf(agent.id)), ".", resumeProviderSession = true)
        }
    }
}
