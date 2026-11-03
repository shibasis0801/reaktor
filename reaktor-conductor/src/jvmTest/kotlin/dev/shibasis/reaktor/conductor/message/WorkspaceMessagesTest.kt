package dev.shibasis.reaktor.conductor.message

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.workspace.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class WorkspaceMessagesTest {
    private val tower = BoardActor(BoardActorKind.Agent, "Tower", "claude:tower")
    private val osprey = BoardActor(BoardActorKind.Agent, "Osprey", "codex:osprey")
    private val owner = BoardActor(BoardActorKind.Person, "Owner")

    private suspend fun fixture(runtimes: Map<RuntimeKind, AgentRuntime> = emptyMap(), block: suspend (AgentWorkspace) -> Unit) {
        val root = Files.createTempDirectory("workspace-messages-root").toFile()
        val state = Files.createTempDirectory("workspace-messages-state")
        try {
            AgentWorkspace(root, state, runtimes, background = false, discover = { ProviderCapability(it) }).use { block(it) }
        } finally {
            root.deleteTreeSafely(within = root.parentFile)
            state.toFile().deleteTreeSafely(within = state.parent.toFile())
        }
    }

    @Test fun planNotificationsBecomeDurableMessagesForEveryOtherAgent(): Unit = runBlocking {
        fixture { workspace ->
            val thread = "hangar:launch"
            workspace.plans.write(thread, PlanPatch(title = "Launch", ops = listOf(PlanOp.Add("Memory", key = "memory"))), owner)
            workspace.plans.attach(thread) { PlanSeat("s1", "osprey", listOf("memory"), owner, 1, session = osprey.session) }
            workspace.plans.write(thread, PlanPatch(message = "Memory API is ready"), tower)
            val messages = workspace.messages.inbox(osprey)
            assertTrue(messages.isNotEmpty())
            val notice = messages.single { it.message.request.body.contains("Memory API is ready") }
            assertEquals("agent:claude:tower", notice.message.request.from)
            assertEquals(listOf("agent:codex:osprey"), notice.message.recipients)
            assertNull(notice.receipt.deliveredAt)
            assertTrue(workspace.messages.inbox(tower).none { it.message.id == notice.message.id })
            val current = workspace.plans.plan(thread)!!
            workspace.messages.planChanged(current, current.copy(revision = current.revision - 1), workspace.identity(tower).actor)
            assertEquals(messages, workspace.messages.inbox(osprey))
        }
    }

    @Test fun toolsBindAuthorsAndReceiptsToTheAuthenticatedCaller(): Unit = runBlocking {
        fixture { workspace ->
            val tools = agentMessageTools(workspace).associateBy { it.name }
            val sent = tools.getValue("agent_message_send").invoke(buildJsonObject {
                put("id", "request-1"); put("kind", "Ask"); put("body", "Inspect the receipt")
                put("by", ConductorJson.encodeToJsonElement(BoardActor.serializer(), tower))
                putJsonObject("to") { put("agent", "agent:codex:osprey") }
            })
            val message = ConductorJson.decodeFromJsonElement(Message.serializer(), sent)
            assertEquals("agent:claude:tower", message.request.from)
            val ref = buildJsonObject {
                put("id", message.id); put("by", ConductorJson.encodeToJsonElement(BoardActor.serializer(), osprey))
            }
            val ack = tools.getValue("agent_message_ack").invoke(ref)
            assertNotNull(ConductorJson.decodeFromJsonElement(MessageReceipt.serializer(), ack).readAt)
            assertFailsWith<IllegalArgumentException> { tools.getValue("agent_message_read").invoke(JsonObject(ref +
                ("by" to ConductorJson.encodeToJsonElement(BoardActor.serializer(), tower)))) }
            assertFailsWith<IllegalArgumentException> { workspace.messages.send(osprey, message.request.copy(requestId = "forged")) }
        }
    }

    @Test fun hookFallbackRecordsDeliveryOnlyAfterTheHarnessTakesTheNote(): Unit = runBlocking {
        fixture { workspace ->
            val directory = Files.createTempDirectory("message-hook")
            val state = Files.createTempDirectory("message-hook-store")
            val inbox = ThreadInbox(directory)
            try {
                WorkspaceMessages(workspace, state, inbox).use { messages ->
                    for ((session, runtime) in listOf("claude:reader" to RuntimeKind.ClaudeCode, "agy:reader" to RuntimeKind.Gemini)) {
                        val by = BoardActor(BoardActorKind.Agent, runtime.name, session)
                        val identity = workspace.identity(by)
                        workspace.presence.heartbeat(identity)
                        val key = requireNotNull(identity.actor.agent)
                        val sent = messages.send(tower, SendMessage(session, "agent:claude:tower", MessageRecipient.Agent(key), MessageKind.Note, "A shared fact needs review"))
                        withTimeout(15000) { while (!Files.exists(inbox.file(session))) delay(25) }
                        assertNull(messages.read(by, sent.id).receipt.deliveredAt)
                        assertTrue(requireNotNull(inbox.take(session)).contains("message:${sent.id}"))
                        withTimeout(15000) { while (messages.read(by, sent.id).receipt.deliveredAt == null) delay(25) }
                        assertNull(messages.read(by, sent.id).receipt.readAt)
                        assertNotNull(messages.ack(by, sent.id).readAt)
                    }
                }
            } finally {
                directory.toFile().deleteTreeSafely(within = directory.parent.toFile())
                state.toFile().deleteTreeSafely(within = state.parent.toFile())
            }
        }
    }

    private suspend fun hooked(workspace: AgentWorkspace, pushFor: Duration = 30.minutes,
        block: suspend (WorkspaceMessages, ThreadInbox, BoardActor, String) -> Unit) {
        val directory = Files.createTempDirectory("message-hook")
        val state = Files.createTempDirectory("message-hook-store")
        try {
            val inbox = ThreadInbox(directory)
            WorkspaceMessages(workspace, state, inbox, pushFor).use { messages ->
                val by = BoardActor(BoardActorKind.Agent, RuntimeKind.ClaudeCode.name, "claude:reader")
                val identity = workspace.identity(by)
                workspace.presence.heartbeat(identity)
                block(messages, inbox, by, requireNotNull(identity.actor.agent))
            }
        } finally {
            directory.toFile().deleteTreeSafely(within = directory.parent.toFile())
            state.toFile().deleteTreeSafely(within = state.parent.toFile())
        }
    }

    @Test fun aNoteTheHookTookStaysDeliveredWhenALaterNoteReplacesTheReadFile(): Unit = runBlocking {
        fixture { workspace ->
            hooked(workspace) { messages, inbox, by, key ->
                val sent = messages.send(tower, SendMessage("first", "agent:claude:tower", MessageRecipient.Agent(key), MessageKind.Note, "The first fact"))
                val file = inbox.file("claude:reader")
                withTimeout(15000) { while (!Files.exists(file)) delay(25) }
                assertTrue(requireNotNull(inbox.take("claude:reader")).contains("message:${sent.id}"))
                Files.writeString(file.resolveSibling("${file.fileName}.read"), "message:a-later-note\n")
                withTimeout(15000) { while (messages.read(by, sent.id).receipt.deliveredAt == null) delay(25) }
                assertFalse(Files.exists(file))
            }
        }
    }

    @Test fun aNoteOlderThanThePushWindowStopsBeingPushed(): Unit = runBlocking {
        fixture { workspace ->
            hooked(workspace, pushFor = Duration.ZERO) { messages, inbox, by, key ->
                val sent = messages.send(tower, SendMessage("stale", "agent:claude:tower", MessageRecipient.Agent(key), MessageKind.Note, "An old fact"))
                withTimeout(15000) { while (messages.read(by, sent.id).receipt.deliveredAt == null) delay(25) }
                assertFalse(Files.exists(inbox.file("claude:reader")))
                assertNull(messages.read(by, sent.id).receipt.readAt)
            }
        }
    }

    @Test fun managedTurnsRecallUnreadMessagesAndKeepReadAsAnExplicitAcknowledgment(): Unit = runBlocking {
        val requests = java.util.concurrent.CopyOnWriteArrayList<AgentRequest>()
        val runtime = object : AgentRuntime {
            override val kind = RuntimeKind.Codex
            override fun run(request: AgentRequest) = flow {
                requests += request
                emit(AgentEvent.Started(request.agent.id, ProviderSession(kind, "native")))
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "answer", true)))
            }
        }
        fixture(mapOf(runtime.kind to runtime)) { workspace ->
            val first = workspace.submit(AgentSubmission("first", runtime.kind, "Inspect memory"))
            withTimeout(15000) { while (workspace.get(first.id).status == AgentRunStatus.Running) delay(10) }
            assertEquals(AgentRunStatus.Completed, workspace.get(first.id).status)
            val by = BoardActor(BoardActorKind.Agent, "Codex", "hangar:${first.threadId}")
            val key = requireNotNull(workspace.identity(by).actor.agent)
            val sent = workspace.messages.send(tower, SendMessage("for-next-turn", "agent:claude:tower", MessageRecipient.Agent(key), MessageKind.Ask, "Review the actor transition"))
            assertNull(workspace.messages.read(by, sent.id).receipt.deliveredAt)
            withTimeout(15000) { while (runCatching { workspace.admitOperation(requireIdle = true) {} }.isFailure) delay(10) }
            val next = workspace.submit(AgentSubmission("next", runtime.kind, "Continue", first.threadId))
            withTimeout(15000) { while (workspace.get(next.id).status == AgentRunStatus.Running) delay(10) }
            assertEquals(AgentRunStatus.Completed, workspace.get(next.id).status)
            assertTrue(workspace.get(next.id).context?.entries.orEmpty().any { it.ref == "message:${sent.id}" && it.text == sent.request.body })
            assertContains(requests.last().prompt, sent.request.body)
            val received = workspace.messages.read(by, sent.id)
            assertNotNull(received.receipt.deliveredAt)
            assertNull(received.receipt.readAt)
            assertNotNull(workspace.messages.ack(by, sent.id).readAt)
        }
    }

    @Test fun aLiveManagedTurnUsesSteeringWithItsNativeTurnPrecondition(): Unit = runBlocking {
        val release = CompletableDeferred<Unit>()
        val steered = CompletableDeferred<Pair<String, String?>>()
        val runtime = object : InteractiveAgentRuntime {
            override val kind = RuntimeKind.Codex
            override val interactive = Qualification.qualified("message fixture")
            override fun run(request: AgentRequest) = flow<AgentEvent> { error("Interactive adapter required") }
            override suspend fun open(request: AgentRequest) = object : AgentSession {
                override val activeTurn = "native-turn"
                override val events = flow {
                    emit(AgentEvent.Started(request.agent.id, ProviderSession(kind, "native")))
                    emit(AgentEvent.TurnStarted(request.agent.id, activeTurn))
                    release.await()
                    emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "answer", true)))
                }
                override suspend fun steer(text: String, expectedTurn: String?): CommandOutcome {
                    steered.complete(text to expectedTurn)
                    return CommandOutcome.Accepted
                }
                override suspend fun resolve(requestId: String, decision: AgentDecision) = CommandOutcome.Stale(requestId, null)
                override suspend fun interrupt() = CommandOutcome.Accepted
                override fun close() = Unit
            }
        }
        fixture(mapOf(runtime.kind to runtime)) { workspace ->
            try {
                val run = workspace.submit(AgentSubmission("live", runtime.kind, "Inspect memory"))
                withTimeout(15000) { while (workspace.get(run.id).participants.values.none { it.activeTurn == "native-turn" }) delay(10) }
                val by = BoardActor(BoardActorKind.Agent, "Codex", "hangar:${run.threadId}")
                val key = requireNotNull(workspace.identity(by).actor.agent)
                val sent = workspace.messages.send(tower, SendMessage("steer", "agent:claude:tower", MessageRecipient.Agent(key), MessageKind.Note, "Inspect the durable receipt"))
                val (text, expected) = withTimeout(15000) { steered.await() }
                assertContains(text, sent.id)
                assertContains(text, sent.request.body)
                assertEquals("native-turn", expected)
                withTimeout(15000) { while (workspace.messages.read(by, sent.id).receipt.deliveredAt == null) delay(10) }
                assertNull(workspace.messages.read(by, sent.id).receipt.readAt)
            } finally { release.complete(Unit) }
        }
    }
}
