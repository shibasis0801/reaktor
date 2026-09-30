package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class AgentCollaborationTest {
    private class Harness(override val kind: RuntimeKind, val fail: Boolean = false) : AgentRuntime {
        val requests = CopyOnWriteArrayList<AgentRequest>()
        val started = CompletableDeferred<Unit>()
        override fun run(request: AgentRequest) = flow {
            requests += request
            val session = ProviderSession(kind, "session-${requests.size}")
            emit(AgentEvent.Started(request.agent.id, session))
            emit(AgentEvent.Delta(request.agent.id, "${kind.name} progress"))
            started.complete(Unit)
            if (request.prompt.contains("BLOCK_COLLABORATION")) awaitCancellation()
            emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "UNIQUE_${kind.name}_PROPOSAL", !fail,
                failure = if (fail) "${kind.name} failed" else null, session = session,
                usage = AgentUsage(inputTokens = 10, outputTokens = 3))))
        }
    }

    private suspend fun terminal(connection: AgentWorkspaceConnection, initial: AgentRunRecord): AgentRunRecord = withTimeout(10000) {
        var record = initial
        while (record.status == AgentRunStatus.Running) record = connection.wait(record.id, record.revision, 5000)
        record
    }

    @Test fun councilAccountsForFiveTurnsAndNeverResumesAnEphemeralCouncilSession() = runBlocking {
        val root = Files.createTempDirectory("council-root").toFile()
        val data = Files.createTempDirectory("council-data")
        val codex = Harness(RuntimeKind.Codex)
        val claude = Harness(RuntimeKind.ClaudeCode)
        try {
            AgentWorkspaceConnection.open(root, data, mapOf(codex.kind to codex, claude.kind to claude)).use { connection ->
                assertTrue(AgentCollaboration.Council in connection.info().collaborations)
                val request = AgentSubmission("council", codex.kind, "Design tab ownership", model = "primary-model",
                    collaboration = AgentCollaboration.Council, partner = AgentPartner(claude.kind, "partner-model"))
                val completed = terminal(connection, connection.submit(request))
                assertEquals(AgentRunStatus.Completed, completed.status)
                assertEquals(5, completed.turnUsage?.turns)
                assertEquals(50, completed.turnUsage?.input?.reported)
                assertEquals(5, completed.turnUsage?.cachedInput?.unknownTurns)
                assertEquals(2, completed.participants.size)
                assertNull(completed.usage)
                assertNull(completed.session)
                assertEquals(3, codex.requests.size)
                assertEquals(2, claude.requests.size)
                assertTrue(codex.requests.all { it.agent.model == "primary-model" && !it.persistSession && it.resume == null })
                assertTrue(claude.requests.all { it.agent.model == "partner-model" && !it.persistSession && it.resume == null })
                assertFalse(codex.requests.first().prompt.contains("UNIQUE_ClaudeCode_PROPOSAL"))
                assertTrue(codex.requests[1].prompt.contains("UNIQUE_ClaudeCode_PROPOSAL"))
                assertEquals(EventKind.Synthesis, connection.transcript(completed.threadId).events.last().kind)
                assertEquals(completed.id, connection.submit(request).id)
                assertEquals(3, codex.requests.size)
                assertFailsWith<IllegalStateException> { connection.submit(request.copy(partner = AgentPartner(claude.kind, "different"))) }
                terminal(connection, connection.submit(AgentSubmission("continue", codex.kind, "Continue from the council", completed.threadId, "primary-model")))
                assertNull(codex.requests.last().resume)
                assertTrue(codex.requests.last().prompt.contains("UNIQUE_ClaudeCode_PROPOSAL"))
                assertTrue(connection.list().all { it.participants.values.all { participant -> participant.output.isEmpty() } })
            }
        } finally { root.deleteRecursively(); data.toFile().deleteRecursively() }
    }

    @Test fun participantFailureRemainsFailedEvenWhenSynthesisSucceeds() = runBlocking {
        val root = Files.createTempDirectory("council-root").toFile()
        val data = Files.createTempDirectory("council-data")
        val codex = Harness(RuntimeKind.Codex)
        val claude = Harness(RuntimeKind.ClaudeCode, fail = true)
        try {
            AgentWorkspaceConnection.open(root, data, mapOf(codex.kind to codex, claude.kind to claude)).use { connection ->
                val run = terminal(connection, connection.submit(AgentSubmission("failed-council", codex.kind, "Review",
                    collaboration = AgentCollaboration.Council, partner = AgentPartner(claude.kind))))
                assertEquals(AgentRunStatus.Failed, run.status)
                assertTrue(run.failure.orEmpty().contains("ClaudeCode failed"))
                assertEquals(EventKind.Synthesis, connection.transcript(run.threadId).events.last().kind)
                assertEquals(5, run.turnUsage?.turns)
            }
        } finally { root.deleteRecursively(); data.toFile().deleteRecursively() }
    }

    @Test fun comparisonReservesTwoSlotsAndCancellationStopsBothParticipants() = runBlocking {
        val root = Files.createTempDirectory("council-root").toFile()
        val data = Files.createTempDirectory("council-data")
        val codex = Harness(RuntimeKind.Codex)
        val claude = Harness(RuntimeKind.ClaudeCode)
        try {
            AgentWorkspaceConnection.open(root, data, mapOf(codex.kind to codex, claude.kind to claude)).use { connection ->
                val run = connection.submit(AgentSubmission("compare", codex.kind, "BLOCK_COLLABORATION",
                    collaboration = AgentCollaboration.Compare, partner = AgentPartner(claude.kind)))
                withTimeout(10000) { codex.started.await(); claude.started.await() }
                assertFailsWith<IllegalStateException> { connection.submit(AgentSubmission("over-capacity", codex.kind, "another")) }
                val cancelled = connection.cancel(run.id)
                assertEquals(AgentRunStatus.Interrupted, cancelled.status)
                assertEquals(2, cancelled.participants.size)
                assertTrue(cancelled.participants.values.all { it.status == AgentRunStatus.Interrupted })
                val next = terminal(connection, connection.submit(AgentSubmission("fresh", codex.kind, "Fresh compare",
                    collaboration = AgentCollaboration.Compare, partner = AgentPartner(claude.kind))))
                assertEquals(2, next.turnUsage?.turns)
                assertEquals(2, connection.transcript(next.threadId).events.count { it.kind == EventKind.Proposal })
                assertEquals(AgentRunStatus.Interrupted, connection.cancel(run.id).status)
                assertEquals(AgentRunStatus.Completed, connection.get(next.id).status)
            }
        } finally { root.deleteRecursively(); data.toFile().deleteRecursively() }
    }

    @Test fun collaborativeValidationPrecedesDispatch() {
        val root = Files.createTempDirectory("council-root").toFile()
        val data = Files.createTempDirectory("council-data")
        val codex = Harness(RuntimeKind.Codex)
        val claude = Harness(RuntimeKind.ClaudeCode)
        try {
            AgentWorkspace(root, data, mapOf(codex.kind to codex, claude.kind to claude)).use { workspace ->
                val request = AgentSubmission("invalid", codex.kind, "Review", collaboration = AgentCollaboration.Council)
                assertFailsWith<IllegalArgumentException> { workspace.submit(request) }
                assertFailsWith<IllegalArgumentException> { workspace.submit(request.copy(partner = AgentPartner(codex.kind))) }
                assertFailsWith<IllegalArgumentException> { workspace.submit(request.copy(partner = AgentPartner(claude.kind), allowWrites = true)) }
                assertFailsWith<IllegalArgumentException> { workspace.submit(request.copy(partner = AgentPartner(claude.kind, ""))) }
                assertTrue(workspace.list().isEmpty())
                assertTrue(codex.requests.isEmpty())
            }
        } finally { root.deleteRecursively(); data.toFile().deleteRecursively() }
    }

    @Test fun recoveryMarksUnfinishedParticipantsInterruptedWithoutRedispatch() = runBlocking {
        val root = Files.createTempDirectory("council-root").toFile()
        val data = Files.createTempDirectory("council-data")
        val codex = Harness(RuntimeKind.Codex)
        val claude = Harness(RuntimeKind.ClaudeCode)
        val runtimes = mapOf(codex.kind to codex, claude.kind to claude)
        try {
            val completed = AgentWorkspaceConnection.open(root, data, runtimes).use { connection ->
                terminal(connection, connection.submit(AgentSubmission("recover", codex.kind, "Compare",
                    collaboration = AgentCollaboration.Compare, partner = AgentPartner(claude.kind))))
            }
            val unfinished = completed.copy(status = AgentRunStatus.Running,
                participants = completed.participants.mapValues { (_, participant) -> if (participant.provider == claude.kind)
                    participant.copy(status = AgentRunStatus.Running) else participant })
            Files.writeString(data.resolve("runs/${completed.id}.json"), ConductorJson.encodeToString(AgentRunRecord.serializer(), unfinished))
            AgentWorkspaceConnection.open(root, data, runtimes).use { connection ->
                val recovered = connection.get(completed.id)
                assertEquals(AgentRunStatus.Interrupted, recovered.status)
                assertEquals(AgentRunStatus.Interrupted, recovered.participants.getValue("claudecode").status)
                assertEquals(AgentRunStatus.Completed, recovered.participants.getValue("codex").status)
                assertEquals(1, codex.requests.size)
                assertEquals(1, claude.requests.size)
            }
        } finally { root.deleteRecursively(); data.toFile().deleteRecursively() }
    }
}
