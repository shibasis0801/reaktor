package dev.shibasis.reaktor.conductor.message

import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.conductor.ProviderCapability
import dev.shibasis.reaktor.conductor.workspace.AgentWorkspace
import dev.shibasis.reaktor.conductor.workspace.BoardActor
import dev.shibasis.reaktor.conductor.workspace.BoardActorKind
import dev.shibasis.reaktor.conductor.workspace.agentMessageTools
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PersonMessageTest {
    private val owner = BoardActor(BoardActorKind.Person, "Owner")
    private val osprey = BoardActor(BoardActorKind.Agent, "Osprey", "codex:osprey")

    @Test fun theOwnerMessagesAnAgentAndCannotForgeAnotherAuthor(): Unit = runBlocking {
        val root = Files.createTempDirectory("person-message-root").toFile()
        val state = Files.createTempDirectory("person-message-state")
        try {
            AgentWorkspace(root, state, emptyMap(), background = false, discover = { ProviderCapability(it) }).use { workspace ->
                val send = agentMessageTools(workspace).single { it.name == "agent_message_send" }
                val sent = ConductorJson.decodeFromJsonElement(Message.serializer(), send.invoke(buildJsonObject {
                    put("id", "owner-1"); put("kind", "Ask"); put("body", "Rerun the check")
                    put("by", ConductorJson.encodeToJsonElement(BoardActor.serializer(), owner))
                    putJsonObject("to") { put("agent", "agent:codex:osprey") }
                }))
                assertEquals("person:Owner", sent.request.from)
                assertEquals(listOf("agent:codex:osprey"), sent.recipients)
                val received = workspace.messages.inbox(osprey).single()
                assertEquals(sent.id, received.message.id)
                assertEquals("Rerun the check", received.message.request.body)
                assertFailsWith<IllegalArgumentException> { workspace.messages.send(owner, sent.request.copy(requestId = "forged", from = "agent:claude:tower")) }
            }
        } finally {
            root.deleteTreeSafely(within = root.parentFile)
            state.toFile().deleteTreeSafely(within = state.parent.toFile())
        }
    }
}
