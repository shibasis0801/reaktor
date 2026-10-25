package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class AgentMessageDeliveryTest {
    private val person = BoardActor(BoardActorKind.Person, "owner")

    private class Native(override val kind: RuntimeKind) : AgentRuntime {
        val requests = CopyOnWriteArrayList<AgentRequest>()
        var starts = true
        var onRun: suspend (AgentRequest) -> Unit = {}
        override fun run(request: AgentRequest) = flow {
            requests += request
            if (starts) emit(AgentEvent.Started(request.agent.id, ProviderSession(kind, request.executionId.orEmpty().replace(':', '-'))))
            onRun(request)
            emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "answer", true)))
        }
    }

    private inner class Fixture {
        val root = Files.createTempDirectory("message-delivery-root").toFile()
        val data = Files.createTempDirectory("message-delivery-owner")
        val codex = Native(RuntimeKind.Codex)
        val claude = Native(RuntimeKind.ClaudeCode)
        var claudeRuntime: AgentRuntime = claude
        fun open(background: Boolean = false) = AgentWorkspace(root, data, mapOf(codex.kind to codex, claudeRuntime.kind to claudeRuntime),
            background = background, discover = { ProviderCapability(it) }).also { owner ->
            if (runCatching { owner.board.graph("G1") }.isFailure) {
                owner.board.propose(TaskGraphDraft("Delivery", tasks = listOf(
                    TaskGraphOp.Add("Codex", seat = TaskSeat(RuntimeKind.Codex)),
                    TaskGraphOp.Add("Claude", seat = TaskSeat(RuntimeKind.ClaudeCode)),
                )), person)
                owner.board.start("G1", person)
            }
        }
        fun close() {
            val temporary = File(System.getProperty("java.io.tmpdir")).canonicalFile
            data.toFile().deleteTreeSafely(within = temporary)
            root.deleteTreeSafely(within = temporary)
        }
    }

    private fun fixture(body: suspend (Fixture) -> Unit) = runBlocking {
        val fixture = Fixture()
        try { body(fixture) } finally { fixture.close() }
    }

    private suspend fun until(test: () -> Boolean) = withTimeout(15000) { while (!test()) delay(10) }

    private fun bind(owner: AgentWorkspace, task: String, run: AgentRunRecord) {
        val ref = BoardRefs.Hangar + run.threadId
        owner.board.advance("G1", task) { value, plan -> value.copy(work = listOf(ref), graph = plan.copy(
            state = TaskState.Running, attempt = 1, runId = run.id, workAttempts = mapOf(ref to 1))) }
    }

    private suspend fun initial(owner: AgentWorkspace, task: String, runtime: RuntimeKind): AgentRunRecord {
        val run = owner.submit(AgentSubmission("initial-$task", runtime, "Initial $task", model = "fixture-model", effort = NativeEffort("high")))
        bind(owner, task, run)
        until { owner.get(run.id).status == AgentRunStatus.Completed }
        until { runCatching { owner.admitOperation(requireIdle = true) {} }.isSuccess }
        return owner.get(run.id)
    }

    private fun actor(run: AgentRunRecord) = BoardActor(BoardActorKind.Agent, run.provider.name, BoardRefs.Hangar + run.threadId)

    private fun send(owner: AgentWorkspace, id: String, recipient: AgentRecipient, by: BoardActor = person, task: String? = null, reply: String? = null): AgentMessage =
        owner.sendMessage(AgentMessage("G1", recipient, if (reply == null) AgentMessageKind.Question else AgentMessageKind.Answer,
            "Contract for $id", task = task, attempt = task?.let { owner.board.get(it).graph?.attempt }, replyTo = reply,
            requestId = id, requestRevision = owner.board.graph("G1").revision), owner.taskGraphCaller("G1", by))

    private fun events(owner: AgentWorkspace, value: AgentMessage) = owner.board.coordination("G1").messageEvents.filter { it.message == value.id }

    private suspend fun delivered(owner: AgentWorkspace, value: AgentMessage): AgentRunRecord {
        until { events(owner, value).any { it.state == AgentMessageState.Delivered } }
        val run = requireNotNull(events(owner, value).first { it.state == AgentMessageState.Delivered }.runId)
        until { owner.get(run).status != AgentRunStatus.Running }
        assertEquals(AgentRunStatus.Completed, owner.get(run).status)
        return owner.get(run)
    }

    @Test fun bidirectionalQuestionsUseEachRecipientsAdapterAndPreserveThreadAndSettings() = fixture { f ->
        f.open().use { owner ->
            val codex = initial(owner, "T1", RuntimeKind.Codex)
            val claude = initial(owner, "T2", RuntimeKind.ClaudeCode)
            val question = send(owner, "question", AgentRecipient(task = "T2"), actor(codex), "T1")
            val received = delivered(owner, question)
            assertEquals(claude.threadId, received.threadId)
            assertFalse(received.allowWrites)
            assertEquals(RuntimeKind.ClaudeCode, received.provider)
            assertEquals("fixture-model", received.model)
            assertEquals(NativeEffort("high"), received.effort.requested)
            assertTrue(f.claude.requests.last().prompt.contains(question.body))
            assertFalse(f.claude.requests.last().agent.tools.allowWrites)
            owner.board.acknowledgeMessage("G1", question.id, owner.taskGraphCaller("G1", actor(claude)))
            val answer = send(owner, "answer", AgentRecipient(task = "T1"), actor(claude), "T2", question.id)
            assertEquals(question.thread, answer.thread)
            assertEquals(codex.threadId, delivered(owner, answer).threadId)
            assertEquals(2, f.codex.requests.size)
            assertEquals(2, f.claude.requests.size)
            assertEquals(listOf(AgentMessageState.Queued, AgentMessageState.Delivered, AgentMessageState.Acknowledged), events(owner, question).map { it.state })
        }
    }

    @Test fun runningRecipientReceivesNothingUntilItsExistingTurnEndsAndRetryDoesNotQueueTwice() = fixture { f ->
        val release = CompletableDeferred<Unit>()
        f.claude.onRun = { if (it.prompt.contains("Initial")) release.await() }
        f.open().use { owner ->
            val run = owner.submit(AgentSubmission("initial-T2", RuntimeKind.ClaudeCode, "Initial T2"))
            bind(owner, "T2", run)
            until { f.claude.requests.size == 1 }
            val message = send(owner, "queued", AgentRecipient(task = "T2"))
            val request = message.copy(id = "", author = null, at = 0)
            assertEquals(message, owner.sendMessage(request, owner.taskGraphCaller("G1", person)))
            assertEquals(1, owner.queueItems(run.threadId).size)
            assertEquals(listOf(AgentMessageState.Queued), events(owner, message).map { it.state })
            assertEquals(1, f.claude.requests.size)
            release.complete(Unit)
            delivered(owner, message)
            assertEquals(2, f.claude.requests.size)
            assertEquals(listOf("dispatched"), owner.queueItems(run.threadId).map { it.state })
        }
    }

    @Test fun staleSenderOrRecipientAttemptsBlockBeforeNativeExecution() {
        for (stale in listOf("T1", "T2")) fixture { f ->
            val release = CompletableDeferred<Unit>()
            f.claude.onRun = { if (it.prompt.contains("Initial")) release.await() }
            f.open().use { owner ->
                val sender = initial(owner, "T1", RuntimeKind.Codex)
                val recipient = owner.submit(AgentSubmission("recipient-$stale", RuntimeKind.ClaudeCode, "Initial recipient"))
                bind(owner, "T2", recipient)
                until { f.claude.requests.any { it.executionId?.startsWith(recipient.id) == true } }
                val message = send(owner, "stale-$stale", AgentRecipient(task = "T2"), actor(sender), "T1")
                owner.board.advance("G1", stale) { value, plan -> value.copy(graph = plan.copy(attempt = plan.attempt + 1)) }
                val count = f.claude.requests.size
                release.complete(Unit)
                until { owner.queueItems(recipient.threadId).single().state == "blocked" }
                assertEquals(count, f.claude.requests.size)
                assertEquals(listOf(AgentMessageState.Queued), events(owner, message).map { it.state })
                assertTrue(owner.queueItems(recipient.threadId).single().error.orEmpty().contains("stale"))
            }
        }
    }

    @Test fun nativeStartEvidenceIsRequiredAndAnEarlyAcknowledgmentNeverRegresses() = fixture { f ->
        f.open().use { owner ->
            val recipient = initial(owner, "T2", RuntimeKind.ClaudeCode)
            f.claude.starts = false
            val missing = send(owner, "no-start", AgentRecipient(task = "T2"))
            until { owner.queueItems(recipient.threadId).single().runId?.let { owner.get(it).status == AgentRunStatus.Completed } == true }
            assertEquals(listOf(AgentMessageState.Queued), events(owner, missing).map { it.state })
            val release = CompletableDeferred<Unit>()
            f.claude.onRun = { release.await() }
            val running = owner.submit(AgentSubmission("hold", RuntimeKind.ClaudeCode, "hold", recipient.threadId))
            val acknowledged = send(owner, "early-ack", AgentRecipient(task = "T2"))
            owner.board.acknowledgeMessage("G1", acknowledged.id, owner.taskGraphCaller("G1", actor(recipient)))
            f.claude.starts = true
            f.claude.onRun = {}
            release.complete(Unit)
            until { owner.get(running.id).status == AgentRunStatus.Completed }
            delivered(owner, acknowledged)
            val read = agentMessageTools(owner).single { it.name == "agent_message_read" }.execute(buildJsonObject {
                put("graph", "G1"); put("id", acknowledged.id); put("by", AgentWorkspaceJson.encodeToJsonElement(BoardActor.serializer(), person))
            }).jsonObject
            assertEquals("Acknowledged", read.getValue("state").jsonPrimitive.content)
        }
    }

    @Test fun messagesForUnownedAndInterruptedTasksRemainInTheRetainedInbox() = fixture { f ->
        f.open().use { owner ->
            val unowned = send(owner, "unowned", AgentRecipient(task = "T1"))
            assertTrue(events(owner, unowned).isEmpty())
            val started = CompletableDeferred<Unit>()
            f.claude.onRun = { started.complete(Unit); awaitCancellation() }
            val run = owner.submit(AgentSubmission("interrupted", RuntimeKind.ClaudeCode, "Initial interrupted"))
            bind(owner, "T2", run)
            withTimeout(15000) { started.await() }
            owner.cancel(run.id)
            val interrupted = send(owner, "interrupted-message", AgentRecipient(task = "T2"))
            assertTrue(events(owner, interrupted).isEmpty())
            assertEquals(2, owner.board.coordination("G1").messages.size)
            assertTrue(owner.queueItems(run.threadId).isEmpty())
        }
    }

    @Test fun sessionAddressesContinueTheRecordedThreadAndRemainBoundToItsTaskAttempt() = fixture { f ->
        f.open().use { owner ->
            initial(owner, "T2", RuntimeKind.ClaudeCode)
            val first = send(owner, "first-session", AgentRecipient(task = "T2"))
            val received = delivered(owner, first)
            val session = requireNotNull(received.session)
            val next = send(owner, "next-session", AgentRecipient(session = BoardRefs.session(session.runtime, session.sessionId)))
            val followup = delivered(owner, next)
            assertEquals(received.threadId, followup.threadId)
            assertEquals("T2", owner.submission(followup.id).message?.task)
            assertEquals(1, owner.submission(followup.id).message?.attempt)
            assertEquals(3, f.claude.requests.size)
        }
    }

    @Test fun continuationReadsTheOwnedCandidateWithWriteToolsDisabled() = fixture { f ->
        fun git(vararg args: String) {
            val process = ProcessBuilder(listOf("git", "-C", f.root.path) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { output }
        }
        git("init", "-q")
        git("config", "user.name", "Message fixture")
        git("config", "user.email", "fixture@local")
        File(f.root, "Source.kt").writeText("val source = 0\n")
        git("add", "Source.kt")
        git("commit", "-qm", "fixture")
        f.open().use { owner ->
            f.codex.onRun = { request ->
                if (request.agent.tools.allowWrites) File(request.workingDirectory, "Source.kt").writeText("val source = 1\n")
                else {
                    val run = requireNotNull(request.executionId).substringBefore(':')
                    assertEquals(File(request.workingDirectory).toPath().toRealPath(), owner.sourceRoots(run).first())
                    assertFails { owner.sourceRoots(run, writing = true) }
                    assertEquals("val source = 1\n", File(request.workingDirectory, "Source.kt").readText())
                }
            }
            val writing = owner.submit(AgentSubmission("isolated", RuntimeKind.Codex, "Initial writer",
                allowWrites = true, isolation = AgentIsolation.Worktree))
            bind(owner, "T1", writing)
            until { owner.get(writing.id).status != AgentRunStatus.Running }
            assertEquals(AgentRunStatus.Completed, owner.get(writing.id).status)
            try {
                val message = send(owner, "candidate-question", AgentRecipient(task = "T1"))
                val continuation = delivered(owner, message)
                assertEquals(owner.get(writing.id).workingDirectory, continuation.workingDirectory)
                assertFalse(continuation.allowWrites)
                assertEquals("val source = 0\n", File(f.root, "Source.kt").readText())
            } finally {
                val tree = requireNotNull(owner.worktreeFor(writing.id))
                owner.worktrees.abandon(tree.id, owner.worktrees.retainSnapshot(tree.id))
            }
        }
    }

    @Test fun interactiveSessionInitializationDoesNotClaimDeliveryBeforeItsNativeTurnStarts() = fixture { f ->
        val initialized = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val opened = CopyOnWriteArrayList<AgentRequest>()
        f.claudeRuntime = object : InteractiveAgentRuntime {
            override val kind = RuntimeKind.ClaudeCode
            override val interactive = Qualification.qualified("message delivery fixture")
            override fun run(request: AgentRequest) = flow<AgentEvent> { error("Interactive adapter required") }
            override suspend fun open(request: AgentRequest): AgentSession {
                opened += request
                val message = request.prompt.contains("Reaktor message")
                return object : AgentSession {
                    override val activeTurn = "native-turn"
                    override val events = flow {
                        emit(AgentEvent.Started(request.agent.id, ProviderSession(kind, "interactive")))
                        if (message) { initialized.complete(Unit); release.await() }
                        emit(AgentEvent.TurnStarted(request.agent.id, "native-turn"))
                        if (message) finish.await()
                        emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "answer", true)))
                    }
                    override suspend fun steer(text: String, expectedTurn: String?) = CommandOutcome.Unsupported("queue fixture")
                    override suspend fun resolve(requestId: String, decision: AgentDecision) = CommandOutcome.Stale(requestId, null)
                    override suspend fun interrupt() = CommandOutcome.Accepted
                    override fun close() = Unit
                }
            }
        }
        f.open().use { owner ->
            initial(owner, "T2", RuntimeKind.ClaudeCode)
            val message = send(owner, "interactive", AgentRecipient(task = "T2"))
            withTimeout(15000) { initialized.await() }
            assertEquals(listOf(AgentMessageState.Queued), events(owner, message).map { it.state })
            release.complete(Unit)
            until { events(owner, message).any { it.state == AgentMessageState.Delivered } }
            assertEquals("native-turn", events(owner, message).last().turnId)
            finish.complete(Unit)
            delivered(owner, message)
            assertEquals(2, opened.size)
            assertTrue(opened.all { !it.agent.tools.allowWrites })
        }
    }

    @Test fun anUncertainNativeDeliveryWaitsForReviewAfterOwnerReconstruction() = fixture { f ->
        lateinit var message: AgentMessage
        lateinit var running: AgentRunRecord
        f.open().use { owner ->
            initial(owner, "T2", RuntimeKind.ClaudeCode)
            f.claude.onRun = { awaitCancellation() }
            message = send(owner, "uncertain", AgentRecipient(task = "T2"))
            until { events(owner, message).any { it.state == AgentMessageState.Delivered } }
            running = owner.get(requireNotNull(events(owner, message).last().runId))
        }
        Files.writeString(f.data.resolve("runs/${running.id}.json"), ConductorJson.encodeToString(AgentRunRecord.serializer(), running))
        f.open(background = true).use { owner ->
            owner.recoverInBackground()
            until { owner.get(running.id).recovery == AgentRecovery.NeedsReview }
            assertTrue(owner.get(running.id).recoveryReason.orEmpty().contains("Message delivery"))
            assertEquals(2, f.claude.requests.size)
            assertEquals(2, events(owner, message).size)
        }
    }

    @Test fun completedDeliveryAndRequestIdentitySurviveOwnerRestartWithoutAnotherTurn() = fixture { f ->
        lateinit var message: AgentMessage
        f.open().use { owner ->
            initial(owner, "T2", RuntimeKind.ClaudeCode)
            message = send(owner, "restart", AgentRecipient(task = "T2"))
            delivered(owner, message)
        }
        f.open(background = true).use { owner ->
            owner.recoverInBackground()
            val revision = owner.board.revision
            assertEquals(message, owner.sendMessage(message.copy(id = "", author = null, at = 0), owner.taskGraphCaller("G1", person)))
            delay(100)
            assertEquals(revision, owner.board.revision)
            assertEquals(2, f.claude.requests.size)
            assertEquals(listOf(AgentMessageState.Queued, AgentMessageState.Delivered), events(owner, message).map { it.state })
        }
    }
}
