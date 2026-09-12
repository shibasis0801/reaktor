package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import java.nio.file.Files
import java.net.URI
import java.net.http.*
import kotlin.test.*
import kotlinx.serialization.json.*

class AgentWorkspaceTest {
    @Test fun cancellationBeforeDispatchRetainsTheFullPromptInAContinuableConversation() = runBlocking {
        val root = Files.createTempDirectory("agent-root").toFile()
        val directory = Files.createTempDirectory("agent-state")
        val harness = Harness(RuntimeKind.Codex)
        try {
            val stoppedScope = CoroutineScope(SupervisorJob().also { it.cancel() } + Dispatchers.IO)
            AgentWorkspace(root, directory, mapOf(harness.kind to harness), scope = stoppedScope).use { workspace ->
                val prompt = "Full original task ".repeat(40)
                val submitted = workspace.submit(AgentSubmission("before-dispatch", harness.kind, prompt))
                assertEquals(AgentRunStatus.Interrupted, workspace.get(submitted.id).status)
                assertEquals(prompt, workspace.transcript(submitted.threadId).events.first().text)
                assertEquals(EventKind.Failure, workspace.transcript(submitted.threadId).events.last().kind)
                assertTrue(harness.requests.isEmpty())
            }
        } finally { root.deleteRecursively(); directory.toFile().deleteRecursively() }
    }
    private class Harness(override val kind: RuntimeKind) : AgentRuntime {
        val requests = java.util.concurrent.CopyOnWriteArrayList<AgentRequest>()
        val started = CompletableDeferred<Unit>()
        override fun run(request: AgentRequest) = flow {
            requests.add(request)
            val session = request.resume ?: ProviderSession(kind, "session-${requests.size}")
            emit(AgentEvent.Started(request.agent.id, session))
            emit(AgentEvent.Delta(request.agent.id, "working"))
            started.complete(Unit)
            if (request.prompt.contains("BLOCK_UNTIL_CANCEL")) awaitCancellation()
            emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "answer-${kind.name}", true, session = session)))
        }
    }

    private suspend fun terminal(connection: AgentWorkspaceConnection, initial: AgentRunRecord): AgentRunRecord = withTimeout(10000) {
        var record = initial
        while (record.status == AgentRunStatus.Running) record = connection.wait(record.id, record.revision, 5000)
        record
    }

    @Test fun twoClientsShareOwnerAndRetriesNeverRedispatch() = runBlocking {
        val root = Files.createTempDirectory("agent-root").toFile()
        val directory = Files.createTempDirectory("agent-state")
        val harness = Harness(RuntimeKind.Codex)
        try {
            AgentWorkspaceConnection.open(root, directory, mapOf(harness.kind to harness)).use { owner ->
                AgentWorkspaceConnection.open(root, directory, mapOf(harness.kind to harness)).use { client ->
                    assertTrue(owner.ownsService)
                    assertFalse(client.ownsService)
                    assertEquals(owner.url, client.url)
                    val initialize = client.exchange("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"codex-compatible","version":"1"}}}""")!!.jsonObject
                    assertEquals("2025-06-18", initialize.getValue("result").jsonObject.getValue("protocolVersion").jsonPrimitive.content)
                    assertEquals(root.canonicalPath, client.info().workspaceRoot)
                    val defaults = client.call("agent_submit", buildJsonObject {
                        put("requestId", "wire-defaults"); put("provider", harness.kind.name); put("prompt", "wire defaults")
                    }).jsonObject
                    assertEquals("Running", defaults.getValue("status").jsonPrimitive.content)
                    assertEquals(1, defaults.getValue("revision").jsonPrimitive.int)
                    assertFalse(defaults.getValue("allowWrites").jsonPrimitive.boolean)
                    terminal(client, client.get(defaults.getValue("id").jsonPrimitive.content))
                    val request = AgentSubmission("retry-1", harness.kind, "first question")
                    val first = terminal(owner, owner.submit(request))
                    assertEquals(AgentRunStatus.Completed, first.status)
                    assertEquals(first.id, client.submit(request).id)
                    assertEquals(2, harness.requests.size)
                    assertFailsWith<IllegalStateException> { client.submit(request.copy(prompt = "different effect")) }
                    assertEquals(2, client.transcript(first.threadId).events.size)
                    assertEquals("", client.list().first().output)
                    assertTrue(client.list().first().outputTruncated)
                }
                assertEquals(2, owner.list().size)
            }
            AgentWorkspaceConnection.open(root, directory, mapOf(harness.kind to harness)).use { reopened ->
                assertEquals(AgentRunStatus.Completed, reopened.list().first().status)
                assertEquals(2, harness.requests.size)
            }
        } finally { root.deleteRecursively(); directory.toFile().deleteRecursively() }
    }

    @Test fun clientReattachesAfterOwnerRestartAndDataCannotBeReboundToAnotherWorkspace() = runBlocking {
        val root = Files.createTempDirectory("agent-root").toFile()
        val otherRoot = Files.createTempDirectory("agent-other-root").toFile()
        val directory = Files.createTempDirectory("agent-state")
        val harness = Harness(RuntimeKind.Codex)
        val runtimes = mapOf(harness.kind to harness)
        try {
            val owner = AgentWorkspaceConnection.open(root, directory, runtimes)
            AgentWorkspaceConnection.open(root, directory).use { client ->
                val run = terminal(client, client.submit(AgentSubmission("1", harness.kind, "remember this run")))
                owner.close()
                assertFailsWith<IllegalArgumentException> { AgentWorkspaceConnection.open(otherRoot, directory, runtimes) }
                AgentWorkspaceConnection.open(root, directory, runtimes).use {
                    assertEquals(run.id, client.get(run.id).id)
                    assertEquals(1, harness.requests.size)
                }
            }
        } finally { root.deleteRecursively(); otherRoot.deleteRecursively(); directory.toFile().deleteRecursively() }
    }

    @Test fun continuationResumesOnlyTheImmediatelyPreviousCompatibleProvider() = runBlocking {
        val root = Files.createTempDirectory("agent-root").toFile()
        val directory = Files.createTempDirectory("agent-state")
        val codex = Harness(RuntimeKind.Codex)
        val claude = Harness(RuntimeKind.ClaudeCode)
        try {
            AgentWorkspaceConnection.open(root, directory, mapOf(codex.kind to codex, claude.kind to claude)).use { owner ->
                val first = terminal(owner, owner.submit(AgentSubmission("1", codex.kind, "UNIQUE_FIRST_CONTEXT")))
                val second = terminal(owner, owner.submit(AgentSubmission("2", codex.kind, "second question", first.threadId)))
                assertEquals(first.session, codex.requests[1].resume)
                assertFalse(codex.requests[1].prompt.contains("UNIQUE_FIRST_CONTEXT"))
                terminal(owner, owner.submit(AgentSubmission("3", claude.kind, "CLAUDE_INTERVENING_CONTEXT", second.threadId)))
                assertNull(claude.requests.single().resume)
                assertTrue(claude.requests.single().prompt.contains("UNIQUE_FIRST_CONTEXT"))
                terminal(owner, owner.submit(AgentSubmission("4", codex.kind, "back to codex", first.threadId)))
                assertNull(codex.requests.last().resume)
                assertTrue(codex.requests.last().prompt.contains("CLAUDE_INTERVENING_CONTEXT"))
                assertTrue(codex.requests.all { it.persistSession })
            }
        } finally { root.deleteRecursively(); directory.toFile().deleteRecursively() }
    }

    @Test fun cancelIsExactAndShutdownLeavesInterruptedReceiptWithoutReplay() = runBlocking {
        val root = Files.createTempDirectory("agent-root").toFile()
        val directory = Files.createTempDirectory("agent-state")
        val harness = Harness(RuntimeKind.Codex)
        try {
            lateinit var interrupted: AgentRunRecord
            AgentWorkspaceConnection.open(root, directory, mapOf(harness.kind to harness)).use { owner ->
                val first = owner.submit(AgentSubmission("1", harness.kind, "BLOCK_UNTIL_CANCEL"))
                withTimeout(10000) { harness.started.await() }
                assertFailsWith<IllegalStateException> { owner.submit(AgentSubmission("same-thread", harness.kind, "busy", first.threadId)) }
                assertFailsWith<IllegalStateException> { owner.submit(AgentSubmission("writer", harness.kind, "edit", allowWrites = true)) }
                assertEquals(AgentRunStatus.Interrupted, owner.cancel(first.id).status)
                val second = terminal(owner, owner.submit(AgentSubmission("2", harness.kind, "fresh conversation")))
                assertEquals(AgentRunStatus.Interrupted, owner.cancel(first.id).status)
                assertEquals(AgentRunStatus.Completed, owner.get(second.id).status)
                interrupted = owner.submit(AgentSubmission("3", harness.kind, "BLOCK_UNTIL_CANCEL"))
            }
            val invokedBeforeRestart = harness.requests.size
            AgentWorkspaceConnection.open(root, directory, mapOf(harness.kind to harness)).use { reopened ->
                assertEquals(AgentRunStatus.Interrupted, reopened.get(interrupted.id).status)
                assertEquals(invokedBeforeRestart, harness.requests.size)
            }
        } finally { root.deleteRecursively(); directory.toFile().deleteRecursively() }
    }

    @Test fun effectfulEndpointRequiresBearerAndAdvertisesEffects() {
        val root = Files.createTempDirectory("agent-root").toFile()
        val directory = Files.createTempDirectory("agent-state")
        try {
            AgentWorkspaceConnection.open(root, directory, mapOf(RuntimeKind.Echo to EchoRuntime())).use { owner ->
                HttpClient.newHttpClient().use { http ->
                    val request = HttpRequest.newBuilder(URI(owner.url)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""))
                    assertEquals(401, http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                    val token = ConductorJson.parseToJsonElement(Files.readString(owner.discoveryFile)).jsonObject.getValue("token").jsonPrimitive.content
                    val response = http.send(request.header("Authorization", "Bearer $token").build(), HttpResponse.BodyHandlers.ofString())
                    assertEquals(200, response.statusCode())
                    val tools = ConductorJson.parseToJsonElement(response.body()).jsonObject.getValue("result").jsonObject.getValue("tools").jsonArray
                        .associate { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("annotations").jsonObject }
                    assertFalse(tools.getValue("agent_submit").getValue("readOnlyHint").jsonPrimitive.boolean)
                    assertTrue(tools.getValue("agent_submit").getValue("idempotentHint").jsonPrimitive.boolean)
                    assertFalse(tools.getValue("agent_cancel").getValue("readOnlyHint").jsonPrimitive.boolean)
                    assertTrue(tools.getValue("agent_run").getValue("readOnlyHint").jsonPrimitive.boolean)
                }
            }
        } finally { root.deleteRecursively(); directory.toFile().deleteRecursively() }
    }
}
