package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.conductor.ProviderSession
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class AgentMessageTest {
    private val person = BoardActor(BoardActorKind.Person, "owner")
    private val lead = BoardActor(BoardActorKind.Agent, "codex", "codex:lead")
    private val writer = BoardActor(BoardActorKind.Agent, "codex", "codex:writer")
    private val reviewer = BoardActor(BoardActorKind.Agent, "claude", "claude:reviewer")
    private val outsider = BoardActor(BoardActorKind.Agent, "codex", "codex:outsider")
    private val temporary = File(System.getProperty("java.io.tmpdir")).canonicalFile

    private fun fixture(test: (AgentWorkspace, Path) -> Unit) {
        val root = Files.createTempDirectory("message-root").toFile()
        val directory = Files.createTempDirectory("message-owner")
        try {
            val run = AgentRunRecord("a".repeat(64), "11111111-1111-1111-1111-111111111111", RuntimeKind.ClaudeCode,
                "fingerprint", "Recorded run", status = AgentRunStatus.Completed, startedAt = 1, updatedAt = 2,
                session = ProviderSession(RuntimeKind.ClaudeCode, "recorded"))
            Files.createDirectories(directory.resolve("runs"))
            Files.writeString(directory.resolve("runs/${run.id}.json"), ConductorJson.encodeToString(AgentRunRecord.serializer(), run))
            AgentWorkspace(root, directory, emptyMap()).use { workspace ->
                workspace.board.propose(TaskGraphDraft("Messages", tasks = listOf(
                    TaskGraphOp.Add("Write", seat = TaskSeat(RuntimeKind.Codex)),
                    TaskGraphOp.Add("Review", seat = TaskSeat(RuntimeKind.ClaudeCode)),
                ), lead = lead.session), person)
                listOf("T1" to writer, "T2" to reviewer).forEach { (id, actor) ->
                    workspace.board.advance("G1", id) { task, plan -> task.copy(work = listOf(actor.session!!),
                        graph = plan.copy(state = TaskState.Running, attempt = 1, workAttempts = mapOf(actor.session to 1))) }
                }
                test(workspace, directory)
            }
        } finally {
            directory.toFile().deleteTreeSafely(within = temporary)
            root.deleteTreeSafely(within = temporary)
        }
    }

    private fun args(actor: BoardActor, vararg fields: Pair<String, JsonElement>) = JsonObject(mapOf(
        "graph" to JsonPrimitive("G1"), "by" to AgentWorkspaceJson.encodeToJsonElement(BoardActor.serializer(), actor), *fields))

    private fun send(workspace: AgentWorkspace, actor: BoardActor = writer, id: String = "question-1", revision: Long = workspace.board.graph("G1").revision) = args(actor,
        "id" to JsonPrimitive(id), "expectedRevision" to JsonPrimitive(revision), "recipient" to buildJsonObject { put("task", "T2") },
        "kind" to JsonPrimitive("Question"), "body" to JsonPrimitive("Which parser contract?"),
        "task" to JsonPrimitive("T1"), "attempt" to JsonPrimitive(1))

    private fun call(workspace: AgentWorkspace, name: String, arguments: JsonObject) =
        agentMessageTools(workspace).single { it.name == "agent_message_$name" }.execute(arguments).jsonObject

    private fun message(receipt: JsonObject) = AgentWorkspaceJson.decodeFromJsonElement(AgentMessage.serializer(), receipt.getValue("message"))

    @Test fun repeatedSendsKeepTheOriginalReceiptAcrossReopen() = fixture { workspace, directory ->
        val args = send(workspace)
        val first = message(call(workspace, "send", args))
        val revision = workspace.board.revision
        assertEquals(first, message(call(workspace, "send", args)))
        assertEquals(revision, workspace.board.revision)
        val reopened = AgentBoardStore(directory.resolve("board"))
        assertEquals(first, reopened.sendMessage(first, workspace.taskGraphCaller("G1", writer)))
        assertEquals(revision, reopened.revision)
        assertEquals(writer, first.author)
        assertEquals(1, first.attempt)
        assertEquals("question-1", first.requestId)
        assertEquals("Accepted", call(workspace, "read", args(writer, "id" to JsonPrimitive(first.id)))["state"]?.jsonPrimitive?.content)
        assertTrue(workspace.board.coordination("G1").messageEvents.isEmpty())
    }

    @Test fun changedRequestsAndUnknownArgumentsRefuseWithoutWriting() = fixture { workspace, _ ->
        val request = send(workspace)
        call(workspace, "send", request)
        val before = workspace.board.coordination("G1")
        listOf("body" to JsonPrimitive("Different"), "expectedRevision" to JsonPrimitive(workspace.board.graph("G1").revision),
            "recipient" to buildJsonObject { put("task", "T1") }, "author" to JsonPrimitive("forged"), "thread" to JsonPrimitive("other")).forEach { changed ->
            assertFails { call(workspace, "send", JsonObject(request + changed)) }
        }
        assertEquals(before, workspace.board.coordination("G1"))
    }

    @Test fun authorsNeedCurrentOwnershipAndAnExactAttempt() = fixture { workspace, _ ->
        val before = workspace.board.revision
        assertFails { call(workspace, "send", send(workspace, outsider)) }
        assertFails { call(workspace, "send", send(workspace, reviewer)) }
        assertFails { call(workspace, "send", JsonObject(send(workspace) + ("attempt" to JsonPrimitive(0)))) }
        assertFails { call(workspace, "send", JsonObject(send(workspace) - "expectedRevision")) }
        assertEquals(before, workspace.board.revision)
        val leadMessage = JsonObject(send(workspace, lead) - "task" - "attempt")
        assertEquals(lead, message(call(workspace, "send", leadMessage)).author)
    }

    @Test fun bidirectionalThreadsKeepBodiesOutOfHeadersAndRestrictAcknowledgment() = fixture { workspace, directory ->
        val question = message(call(workspace, "send", send(workspace)))
        val read = args(reviewer, "id" to JsonPrimitive(question.id))
        assertEquals(question, message(call(workspace, "read", read)))
        val headers = call(workspace, "list", args(reviewer))["messages"]!!.jsonArray
        assertEquals(question.id, headers.single().jsonObject["id"]?.jsonPrimitive?.content)
        assertFalse("body" in headers.single().jsonObject)
        assertFails { call(workspace, "ack", args(writer, "id" to JsonPrimitive(question.id))) }
        assertFails { call(workspace, "ack", args(lead, "id" to JsonPrimitive(question.id))) }
        assertFails { call(workspace, "read", args(outsider, "id" to JsonPrimitive(question.id))) }
        assertTrue(call(workspace, "list", args(outsider))["messages"]!!.jsonArray.isEmpty())
        val acknowledged = call(workspace, "ack", read)
        val revision = workspace.board.revision
        assertEquals(acknowledged, call(workspace, "ack", read))
        assertEquals(revision, workspace.board.revision)
        val event = AgentWorkspaceJson.decodeFromJsonElement(AgentMessageEvent.serializer(), acknowledged)
        assertEquals(reviewer, event.by)
        assertEquals(AgentMessageState.Acknowledged, event.state)
        assertEquals(listOf(event), AgentBoardStore(directory.resolve("board")).view("G1").messageEvents)
        assertEquals("Acknowledged", call(workspace, "read", read)["state"]?.jsonPrimitive?.content)
        val answer = JsonObject(send(workspace, reviewer, "answer-1") + mapOf(
            "recipient" to buildJsonObject { put("task", "T1") }, "kind" to JsonPrimitive("Answer"),
            "body" to JsonPrimitive("Use the typed contract"), "task" to JsonPrimitive("T2"), "replyTo" to JsonPrimitive(question.id)))
        val reply = message(call(workspace, "send", answer))
        assertEquals(question.thread, reply.thread)
        assertEquals(question.id, reply.replyTo)
        call(workspace, "ack", args(writer, "id" to JsonPrimitive(reply.id)))
        assertEquals(2, workspace.board.coordination("G1").messageEvents.size)
    }

    @Test fun sessionRecipientsMustExistInThisOwnersRecordedRuns() = fixture { workspace, _ ->
        val unowned = "hangar:22222222-2222-2222-2222-222222222222"
        assertNotNull(workspace.threadOf(unowned))
        assertFails { call(workspace, "send", JsonObject(send(workspace) + ("recipient" to buildJsonObject { put("session", unowned) }))) }
        val owned = "claude:recorded"
        val sent = message(call(workspace, "send", JsonObject(send(workspace) + ("recipient" to buildJsonObject { put("session", owned) }))))
        val recipient = BoardActor(BoardActorKind.Agent, "claude", owned)
        call(workspace, "ack", args(recipient, "id" to JsonPrimitive(sent.id)))
        assertTrue("hangar:" + "a".repeat(64) in workspace.ownedSessionRefs())
        assertTrue("hangar:11111111-1111-1111-1111-111111111111" in workspace.ownedSessionRefs())
    }

    @Test fun paginationAndEvictionDoNotReplayExpiredSendsOrRetainDanglingEvents() = fixture { workspace, _ ->
        val request = send(workspace)
        val first = message(call(workspace, "send", request))
        call(workspace, "ack", args(reviewer, "id" to JsonPrimitive(first.id)))
        repeat(2) { call(workspace, "send", send(workspace, id = "next-$it")) }
        val page = call(workspace, "list", args(reviewer, "limit" to JsonPrimitive(2)))
        assertEquals(2, page["messages"]!!.jsonArray.size)
        val after = page.getValue("nextAfter")
        assertEquals(1, call(workspace, "list", args(reviewer, "after" to after))["messages"]!!.jsonArray.size)
        repeat(TaskCoordination.Retained) { index ->
            workspace.board.appendMessage(AgentMessage("G1", AgentRecipient(task = "T2"), AgentMessageKind.Handoff, "Next $index"), person)
        }
        val records = workspace.board.coordination("G1")
        assertEquals(TaskCoordination.Retained, records.messages.size)
        assertTrue(records.messageEvents.isEmpty())
        val revision = workspace.board.revision
        assertFails { call(workspace, "send", request) }
        assertFails { call(workspace, "list", args(reviewer, "after" to JsonPrimitive(first.id))) }
        assertEquals(revision, workspace.board.revision)
        assertFails { call(workspace, "list", args(reviewer, "limit" to JsonPrimitive(51))) }
    }

    @Test fun anUninvolvedTaskCannotJoinAnExistingThreadByGuessingItsId() = fixture { workspace, _ ->
        val hidden = message(call(workspace, "send", JsonObject(send(workspace, person, "person-note") - "task" - "attempt")))
        assertFails { call(workspace, "send", JsonObject(send(workspace) + ("thread" to JsonPrimitive(hidden.thread)))) }
        assertFails { call(workspace, "send", JsonObject(send(workspace) + ("replyTo" to JsonPrimitive(hidden.id)))) }
        val leadReply = JsonObject(send(workspace, lead, "lead-reply") - "task" - "attempt" + ("replyTo" to JsonPrimitive(hidden.id)))
        assertEquals(hidden.thread, message(call(workspace, "send", leadReply)).thread)
    }

    @Test fun retrySurvivesARecipientAcknowledgmentAndTaskReassignment() = fixture { workspace, _ ->
        val request = send(workspace)
        val first = message(call(workspace, "send", request))
        call(workspace, "ack", args(reviewer, "id" to JsonPrimitive(first.id)))
        workspace.board.advance("G1", "T1") { task, plan -> task.copy(graph = plan.copy(attempt = 2, workAttempts = emptyMap())) }
        val revision = workspace.board.revision
        assertEquals(first, message(call(workspace, "send", request)))
        assertEquals(revision, workspace.board.revision)
        assertFails { call(workspace, "send", send(workspace, id = "new-request")) }
    }
}
