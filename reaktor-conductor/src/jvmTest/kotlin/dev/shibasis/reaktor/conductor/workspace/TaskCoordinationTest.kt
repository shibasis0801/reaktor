package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class TaskCoordinationTest {
    private val person = BoardActor(BoardActorKind.Person, "owner")
    private val codex = BoardActor(BoardActorKind.Agent, "codex", "codex:worker")
    private val claude = BoardActor(BoardActorKind.Agent, "claude", "claude:reviewer")
    private val temporary = File(System.getProperty("java.io.tmpdir"))

    private fun propose(store: AgentBoardStore) = store.propose(TaskGraphDraft("Parser", tasks = listOf(
        TaskGraphOp.Add("Build", seat = TaskSeat(RuntimeKind.Codex)),
        TaskGraphOp.Add("Review", seat = TaskSeat(RuntimeKind.ClaudeCode)),
    )), person)

    private fun withStore(action: (AgentBoardStore, Path) -> Unit) {
        val directory = Files.createTempDirectory("task-coordination")
        val clock = AtomicLong(100)
        try {
            val store = AgentBoardStore(directory, clock::incrementAndGet)
            propose(store)
            action(store, directory)
        } finally { directory.toFile().deleteTreeSafely(within = temporary) }
    }

    private fun message(body: String = "Which parser contract?") = AgentMessage("G1", AgentRecipient(task = "T2"),
        AgentMessageKind.Question, body, task = "T1", attempt = 0)

    private fun decision() = TaskDecision("G1", "Use the typed parser", "Both runtimes need the same contract", listOf("T1", "T2"))

    private fun handoff() = TaskHandoff("G1", "T1", 1, "Build the parser", "Added the typed parser", candidate = "candidate-a",
        openIssues = listOf("Review the failure path"), changedPaths = listOf("src/Parser.kt"), checkReceipts = listOf("check-a"),
        reviewReceipts = listOf("review-a"), nextAction = "Review candidate-a", sessionLogs = listOf(codex.session!!))

    @Test fun recordsRoundTripWithOwnerIdentityAndAppendOrder() = withStore { store, directory ->
        val before = store.view("G1")
        assertEquals(TaskCoordination(), store.coordination("G1"))
        val legacy = ConductorJson.encodeToJsonElement(TaskGraphView.serializer(), before).jsonObject
        assertEquals(before, ConductorJson.decodeFromJsonElement(TaskGraphView.serializer(), JsonObject(legacy.filterKeys {
            it !in setOf("messages", "handoffs", "decisions")
        })))
        val initialRevision = store.revision
        val question = store.appendMessage(message().copy(id = "forged", author = person, at = 1), codex)
        val answer = store.appendMessage(message("Use the typed contract").copy(kind = AgentMessageKind.Answer,
            recipient = AgentRecipient(task = "T1"), task = "T2", replyTo = question.id), claude)
        assertEquals("G1-M1", question.id)
        assertEquals(codex, question.author)
        assertTrue(question.at > 100)
        assertEquals(question.id, question.thread)
        assertEquals(question.thread, answer.thread)
        assertEquals(question.id, answer.replyTo)
        val proposed = store.appendDecision(decision().copy(author = person), codex)
        val accepted = store.appendDecision(decision().copy(supersedes = proposed.id, personAccepted = true), person)
        store.advance("G1", "T1") { task, plan -> task.copy(graph = plan.copy(attempt = 1)) }
        val first = store.appendHandoff(handoff().copy(decisions = listOf(accepted.id)), codex)
        store.advance("G1", "T1") { task, plan -> task.copy(graph = plan.copy(attempt = 2)) }
        val second = store.appendHandoff(first.copy(attempt = 2, candidate = "candidate-b", done = "Fixed the failure path"), claude)
        val records = store.coordination("G1")
        assertEquals(listOf(question, answer), records.messages)
        assertEquals(listOf(proposed, accepted), records.decisions)
        assertEquals(listOf(first, second), records.handoffs)
        assertEquals(7L, records.next)
        assertFalse(proposed.personAccepted)
        assertEquals(codex, proposed.author)
        assertEquals(person, accepted.author)
        assertEquals(initialRevision + 8, store.revision)
        assertEquals(before.graph.revision + 8, store.graph("G1").revision)
        val reopened = AgentBoardStore(directory)
        assertEquals(records, reopened.coordination("G1"))
        assertEquals(store.view("G1"), reopened.view("G1"))
        assertEquals(second, reopened.view("G1").handoffs["T1"])
        val text = TaskGraphText.render(reopened.view("G1"))
        listOf(second.id, "candidate-b", "check-a", "review-a", "codex:worker", accepted.id, "supersedes", "accepted by a person").forEach {
            assertTrue(it in text, it)
        }
        assertFalse("  Handoff ${first.id} · attempt" in text)
    }

    @Test fun byteAndFieldBoundsRefuseBeforeWriting() = withStore { store, _ ->
        store.appendMessage(message("é".repeat(TaskCoordination.MessageBytes / 2)), codex)
        val before = store.view("G1")
        val revision = store.revision
        assertFailsWith<IllegalArgumentException> { store.appendMessage(message("é".repeat(TaskCoordination.MessageBytes / 2 + 1)), codex) }
        assertFailsWith<IllegalArgumentException> { store.appendMessage(message(" "), codex) }
        assertFailsWith<IllegalArgumentException> { store.appendMessage(message().copy(artifacts = List(101) { "artifact" }), codex) }
        assertFailsWith<IllegalArgumentException> { store.appendMessage(message().copy(artifacts = List(100) { "é".repeat(500) }), codex) }
        assertFailsWith<IllegalArgumentException> { store.appendDecision(decision().copy(statement = "x".repeat(2_001)), codex) }
        assertFailsWith<IllegalArgumentException> { store.appendDecision(decision().copy(subjects = emptyList()), codex) }
        assertEquals(revision, store.revision)
        assertEquals(before, store.view("G1"))
        store.advance("G1", "T1") { task, plan -> task.copy(graph = plan.copy(attempt = 1)) }
        val withAttempt = store.view("G1")
        assertFailsWith<IllegalArgumentException> { store.appendHandoff(handoff().copy(done = "x".repeat(8_001)), codex) }
        assertFailsWith<IllegalArgumentException> { store.appendHandoff(handoff().copy(sessionLogs = listOf("../log")), codex) }
        assertEquals(withAttempt, store.view("G1"))
    }

    @Test fun recipientsThreadsAttemptsAndAcceptanceKeepTheirAuthority() = withStore { store, _ ->
        val before = store.view("G1")
        assertFailsWith<IllegalArgumentException> { store.appendMessage(message().copy(recipient = AgentRecipient()), codex) }
        assertFailsWith<IllegalArgumentException> { store.appendMessage(message().copy(recipient = AgentRecipient("T2", "hangar:run")), codex) }
        assertFailsWith<IllegalArgumentException> { store.appendMessage(message().copy(recipient = AgentRecipient(session = "hangar:run")), codex) }
        assertFailsWith<IllegalArgumentException> { store.appendMessage(message().copy(task = null), codex) }
        assertFailsWith<IllegalArgumentException> { store.appendMessage(message().copy(attempt = 1), codex) }
        assertFailsWith<IllegalArgumentException> { store.appendDecision(decision().copy(personAccepted = true), codex) }
        assertFailsWith<IllegalArgumentException> { store.appendDecision(decision().copy(supersedes = "G1-D99"), person) }
        assertFailsWith<IllegalArgumentException> { store.appendHandoff(handoff().copy(attempt = 0), codex) }
        assertEquals(before, store.view("G1"))
        val owned = store.appendMessage(message().copy(recipient = AgentRecipient(session = "hangar:run")), codex, setOf("hangar:run"))
        val otherThread = store.appendMessage(message(), claude)
        val noReply = store.view("G1")
        assertFailsWith<IllegalArgumentException> { store.appendMessage(message().copy(thread = otherThread.thread, replyTo = owned.id), claude) }
        assertFailsWith<IllegalArgumentException> { store.appendMessage(message().copy(thread = "unknown"), claude) }
        assertEquals(noReply, store.view("G1"))
        propose(store)
        val secondGraph = store.view("G1")
        assertFailsWith<IllegalArgumentException> { store.appendMessage(message().copy(recipient = AgentRecipient(task = "T3")), codex) }
        assertEquals(secondGraph, store.view("G1"))
    }

    @Test fun retentionKeepsTheNewestRecordsWithoutReusingIds() = withStore { store, directory ->
        store.advance("G1", "T1") { task, plan -> task.copy(graph = plan.copy(attempt = 1)) }
        repeat(TaskCoordination.Retained + 2) { index ->
            store.appendMessage(message("Message $index"), codex)
            store.appendDecision(decision().copy(statement = "Decision $index"), codex)
            store.appendHandoff(handoff().copy(done = "Handoff $index"), codex)
        }
        val records = AgentBoardStore(directory).coordination("G1")
        assertEquals(TaskCoordination.Retained, records.messages.size)
        assertEquals(TaskCoordination.Retained, records.decisions.size)
        assertEquals(TaskCoordination.Retained, records.handoffs.size)
        assertEquals("Message 2", records.messages.first().body)
        assertEquals("Decision 2", records.decisions.first().statement)
        assertEquals("Handoff 2", records.handoffs.first().done)
        assertEquals("Handoff 201", records.handoffs.last().done)
        assertEquals(607L, records.next)
        val next = AgentBoardStore(directory).appendMessage(message(), codex)
        assertEquals("G1-M607", next.id)
        assertEquals(TaskCoordination.Retained, store.graph("G1").changes.size)
        assertTrue(TaskGraphText.render(store.view("G1")).length <= TaskGraphText.Limit)
    }

    @Test fun theExistingOwnerReadProjectsCoordinationRecords() {
        val root = Files.createTempDirectory("coordination-root").toFile()
        val directory = Files.createTempDirectory("coordination-workspace")
        try {
            AgentWorkspace(root, directory, emptyMap()).use { workspace ->
                propose(workspace.board)
                workspace.board.advance("G1", "T1") { task, plan -> task.copy(graph = plan.copy(attempt = 1)) }
                val handoff = workspace.board.appendHandoff(handoff(), codex)
                val decision = workspace.board.appendDecision(decision(), claude)
                val tool = agentTaskGraphTools(workspace).single { it.name == "agent_taskgraph_read" }
                val json = tool.execute(buildJsonObject { put("graph", "G1") })
                val view = AgentWorkspaceJson.decodeFromJsonElement(TaskGraphView.serializer(), json)
                assertEquals(handoff, view.handoffs["T1"])
                assertEquals(listOf(decision), view.decisions)
                val text = tool.execute(buildJsonObject { put("graph", "G1"); put("format", "text") }).jsonObject.getValue("text").jsonPrimitive.content
                assertTrue(handoff.id in text && decision.id in text)
            }
        } finally {
            root.deleteTreeSafely(within = temporary)
            directory.toFile().deleteTreeSafely(within = temporary)
        }
    }
}
