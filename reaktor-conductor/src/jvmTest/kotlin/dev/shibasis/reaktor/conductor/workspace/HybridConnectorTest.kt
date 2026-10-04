package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * The connector driven the way ChatGPT drives it: over HTTP, one tool call at a time, with no
 * knowledge of the state machine underneath.
 *
 * Everything else in this module tests the seat from inside the process. This is the only test that
 * exercises what a connector actually touches — the wire, the secret, and whether four verbs are
 * enough to get a task from "waiting for a plan" to "done" without anyone reading Kotlin.
 */
class HybridConnectorTest {

    @Test fun chatGptDrivesATaskToCompletionThroughFourVerbsAndGeminiDoesTheWork() = runBlocking {
        val root = source(); val directory = Files.createTempDirectory("connector")
        val executions = AtomicInteger()
        val gemini = object : AgentRuntime {
            override val kind = RuntimeKind.Gemini
            override fun run(request: AgentRequest) = flow {
                val index = executions.incrementAndGet()
                File(request.workingDirectory, "graph.kt").appendText("\nmarker $index")
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id,
                    "appended marker $index", true, session = ProviderSession(RuntimeKind.Gemini, "conv-1"))))
            }
        }
        AgentWorkspace(root, directory, mapOf(RuntimeKind.Gemini to gemini),
            discover = { ProviderCapability(it) }).use { workspace ->
            val run = workspace.submit(AgentSubmission("connector", RuntimeKind.ChatGptGemini,
                "Record two markers in graph.kt", allowWrites = true))
            stopped(workspace, run.id)

            HybridConnector.start(workspace).use { connector ->
                val call = caller(connector)

                assertEquals("reaktor-hybrid-planner",
                    call("initialize", buildJsonObject { put("protocolVersion", REAKTOR_MCP_PROTOCOL_VERSION_FOR_TEST) })
                        .obj("serverInfo").str("name"))
                val names = call("tools/list", buildJsonObject {}).arr("tools").map { it.jsonObject.str("name") }
                // The surface may grow sight, but never reach: no writing, no starting work, no
                // reading another task's transcript.
                assertTrue(names.containsAll(listOf("reaktor_waiting_tasks", "reaktor_packet", "reaktor_reply", "reaktor_await")), names.toString())
                assertTrue(names.none { it.contains("submit") || it.contains("write") || it.contains("cancel") || it.contains("transcript") },
                    "the connector must not expose the workspace's own verbs: $names")

                // 1. What needs me?
                val waiting = tool(call, "reaktor_waiting_tasks", buildJsonObject {}).arr("tasks")
                assertEquals(1, waiting.size)
                val taskId = waiting.single().jsonObject.str("taskId")
                assertTrue(waiting.single().jsonObject["waitingForYou"]!!.jsonPrimitive.boolean)

                // 2. What is it?
                val packet = tool(call, "reaktor_packet", buildJsonObject { put("taskId", taskId) })
                assertEquals("Planning", packet.str("phase"))
                assertTrue(packet.str("packet").contains("Record two markers"), "The operator's task reaches ChatGPT")

                // 3. Tell Gemini what to do — and it starts, in one call.
                val first = tool(call, "reaktor_reply", buildJsonObject {
                    put("taskId", taskId); put("text", "Append the first marker"); put("next", "execute")
                    putJsonArray("acceptanceCriteria") { add("graph.kt gained a marker") }
                })
                assertTrue(first.str("nextStep").contains("Gemini is working"))

                // 4. Wait for it, then read what it did.
                awaitIdle(call, taskId)
                val review = tool(call, "reaktor_packet", buildJsonObject { put("taskId", taskId) })
                assertEquals("Reviewing", review.str("phase"))
                assertTrue(review.str("packet").contains("appended marker 1"), "Gemini's report reaches ChatGPT")

                // 5. Not done — ask for more. This is the edge the old seat did not have.
                tool(call, "reaktor_reply", buildJsonObject {
                    put("taskId", taskId); put("text", "Append the second marker"); put("next", "execute")
                })
                awaitIdle(call, taskId)
                val second = tool(call, "reaktor_packet", buildJsonObject { put("taskId", taskId) })
                assertEquals(2, second.obj("state")["completedCycles"]!!.jsonPrimitive.int)

                // 6. Done.
                val finish = tool(call, "reaktor_reply", buildJsonObject {
                    put("taskId", taskId); put("text", "Both markers are present."); put("next", "finish")
                })
                assertEquals("Completed", finish.str("phase"))
                awaitIdle(call, taskId)

                // The ledger must not call a connector-driven run operator-transferred: a person
                // pasting and ChatGPT calling in reach the same code, and only this says which.
                val answer = workspace.transcript(workspace.get(run.id).threadId).events
                    .last { it.author == Author.Agent(AgentId("chatgptgemini")) }
                assertEquals("Connector", answer.attributes["plannerVia"])
                assertEquals("automatic-chatgpt-agent", answer.attributes["plannerTransport"])
                assertFalse(answer.text.contains("operator-transferred"), answer.text.take(200))
                assertTrue(answer.text.contains("ChatGPT planned over its connector"), answer.text.take(200))

                assertEquals(2, executions.get())
                assertEquals(AgentRunStatus.Completed, workspace.get(run.id).status, workspace.get(run.id).failure)
                assertEquals(2, File(root, "graph.kt").readLines().count { it.startsWith("marker") })
                assertTrue(tool(call, "reaktor_waiting_tasks", buildJsonObject {}).arr("tasks").isEmpty(),
                    "A finished task stops asking for attention")
            }
        }
        removeTree(root); removeTree(directory.toFile())
        Unit
    }

    @Test fun theUrlIsTheCredentialAndAWrongOneIsIndistinguishableFromNothingBeingThere() = runBlocking {
        val root = source(); val directory = Files.createTempDirectory("connector-auth")
        AgentWorkspace(root, directory, mapOf(RuntimeKind.Gemini to object : AgentRuntime {
            override val kind = RuntimeKind.Gemini
            override fun run(request: AgentRequest) = flow<AgentEvent> {}
        }), discover = { ProviderCapability(it) }).use { workspace ->
            HybridConnector.start(workspace).use { connector ->
                val base = "http://127.0.0.1:${connector.port()}"
                val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
                assertEquals(404, post("$base/mcp/${HybridConnector.newSecret()}", body).statusCode(),
                    "A wrong secret looks exactly like a wrong path")
                assertEquals(404, post("$base/mcp/", body).statusCode())
                assertEquals(404, post(base + "/", body).statusCode())
                assertEquals(200, post(connector.url(base), body).statusCode())
                // A browser must not be able to reach this even with the URL.
                val cors = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI(connector.url(base)))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).header("Origin", "https://example.com").build(),
                    HttpResponse.BodyHandlers.ofString())
                assertNull(cors.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
                assertEquals(405, HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI(connector.url(base))).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode())
                assertTrue(connector.secret.length >= 32)
            }
        }
        removeTree(root); removeTree(directory.toFile())
        Unit
    }

    /**
     * The planner can see the code, and only the code it is entitled to.
     *
     * Sight is the whole point of these tools — every defect this seat has shipped was visible in the
     * file and invisible in the summary — but the surface is internet-reachable behind one URL, so the
     * confinement is as load-bearing as the reading.
     */
    @Test fun thePlannerCanReadTheWorkspaceAndNothingOutsideIt() = runBlocking {
        val root = source(); val directory = Files.createTempDirectory("connector-read")
        File(root, "nested").mkdirs()
        File(root, "nested/Zoom.kt").writeText("package a\n\nfun zoomIn() = 1\nfun zoomOut() = 2\n")
        val idle = object : AgentRuntime {
            override val kind = RuntimeKind.Gemini
            override fun run(request: AgentRequest) = flow<AgentEvent> {}
        }
        AgentWorkspace(root, directory, mapOf(RuntimeKind.Gemini to idle), discover = { ProviderCapability(it) }).use { workspace ->
            val run = workspace.submit(AgentSubmission("read", RuntimeKind.ChatGptGemini, "Inspect the zoom code"))
            stopped(workspace, run.id)
            HybridConnector.start(workspace).use { connector ->
                val call = caller(connector)
                val names = call("tools/list", buildJsonObject {}).arr("tools").map { it.jsonObject.str("name") }
                assertTrue(names.containsAll(listOf("reaktor_read_file", "reaktor_search", "reaktor_list", "reaktor_diff")), names.toString())

                val file = tool(call, "reaktor_read_file", buildJsonObject { put("taskId", run.id); put("path", "nested/Zoom.kt") })
                assertTrue(file.str("text").contains("fun zoomIn()"), file.str("text"))
                assertTrue(file.str("text").startsWith("1\t"), "lines are numbered so the planner can cite them")
                assertEquals(4, file.getValue("totalLines").jsonPrimitive.int)

                val found = tool(call, "reaktor_search", buildJsonObject { put("taskId", run.id); put("query", "zoomOut"); put("glob", "*.kt") })
                assertTrue(found.arr("matches").any { it.jsonPrimitive.content.contains("nested/Zoom.kt") }, found.toString())

                val entries = tool(call, "reaktor_list", buildJsonObject { put("taskId", run.id) }).arr("entries").map { it.jsonPrimitive.content }
                assertTrue(entries.any { it == "nested/" }, entries.toString())
                assertTrue(entries.none { it.startsWith(".git") }, "the git directory is not the planner's business")

            }
        }
        removeTree(root); removeTree(directory.toFile())
        Unit
    }

    /** Traversal, an absolute path, and a symlink pointing out all have to fail the same way. */
    @Test fun readsCannotEscapeTheWorkspace() = runBlocking {
        val root = source(); val directory = Files.createTempDirectory("connector-escape")
        val idle = object : AgentRuntime {
            override val kind = RuntimeKind.Gemini
            override fun run(request: AgentRequest) = flow<AgentEvent> {}
        }
        AgentWorkspace(root, directory, mapOf(RuntimeKind.Gemini to idle), discover = { ProviderCapability(it) }).use { workspace ->
            val run = workspace.submit(AgentSubmission("escape", RuntimeKind.ChatGptGemini, "Inspect"))
            stopped(workspace, run.id)
            java.nio.file.Files.createSymbolicLink(File(root, "escape").toPath(), File("/etc").toPath())
            HybridConnector.start(workspace).use { connector ->
                val call = caller(connector)
                for (path in listOf("../../../../etc/passwd", "/etc/passwd", "escape/passwd", "x/../../../etc/hosts")) {
                    val result = call("tools/call", buildJsonObject {
                        put("name", "reaktor_read_file")
                        putJsonObject("arguments") { put("taskId", run.id); put("path", path) }
                    })
                    assertTrue(result["isError"]?.jsonPrimitive?.booleanOrNull == true, "escaped the workspace via $path: $result")
                }
            }
        }
        removeTree(root); removeTree(directory.toFile())
        Unit
    }

    // --- plumbing -------------------------------------------------------------------------------

    private fun caller(connector: HybridConnector): (String, JsonObject) -> JsonObject {
        val url = connector.url("http://127.0.0.1:${connector.port()}")
        var id = 0
        return { method, params ->
            val request = buildJsonObject {
                put("jsonrpc", "2.0"); put("id", ++id); put("method", method); put("params", params)
            }
            val response = post(url, request.toString())
            assertEquals(200, response.statusCode(), response.body())
            val parsed = Json.parseToJsonElement(response.body()).jsonObject
            assertNull(parsed["error"], "RPC error from $method: ${parsed["error"]}")
            parsed["result"]!!.jsonObject
        }
    }

    /** Unwraps a tool result the way a client does: structured content, or the text block. */
    private fun tool(call: (String, JsonObject) -> JsonObject, name: String, args: JsonObject): JsonObject {
        val result = call("tools/call", buildJsonObject { put("name", name); put("arguments", args) })
        assertTrue(result["isError"]?.jsonPrimitive?.booleanOrNull != true, "$name failed: $result")
        (result["structuredContent"] as? JsonObject)?.let { return it }
        val text = result.arr("content").first { it.jsonObject.str("type") == "text" }.jsonObject.str("text")
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun awaitIdle(call: (String, JsonObject) -> JsonObject, taskId: String) {
        repeat(20) {
            val state = tool(call, "reaktor_await", buildJsonObject { put("taskId", taskId); put("timeoutMillis", 5000) })
            if (state["stillRunning"]?.jsonPrimitive?.booleanOrNull != true) return
        }
        fail("Gemini never stopped")
    }

    private fun post(url: String, body: String): HttpResponse<String> = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI(url)).POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json").build(),
        HttpResponse.BodyHandlers.ofString())

    /**
     * Deletes a tree without following symlinks.
     *
     * Kotlin's `File.deleteRecursively` follows them, and this file deliberately plants a link to
     * `/etc` to prove the reader refuses it — so the obvious cleanup walks `/etc` and tries to
     * delete it. It failed on permissions rather than doing damage, but "saved by chmod" is not a
     * property to rely on in a test that runs unattended.
     */
    private fun removeTree(root: File) {
        if (!root.exists() && !java.nio.file.Files.isSymbolicLink(root.toPath())) return
        java.nio.file.Files.walkFileTree(root.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes) =
                java.nio.file.FileVisitResult.CONTINUE.also { java.nio.file.Files.deleteIfExists(file) }
            override fun postVisitDirectory(dir: java.nio.file.Path, failure: java.io.IOException?) =
                java.nio.file.FileVisitResult.CONTINUE.also { java.nio.file.Files.deleteIfExists(dir) }
        })
    }

    private fun JsonObject.str(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonObject.obj(name: String) = getValue(name).jsonObject
    private fun JsonObject.arr(name: String) = getValue(name).jsonArray

    private fun git(root: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText(); check(process.waitFor() == 0) { output }
    }
    private fun source(): File = Files.createTempDirectory("connector-source").toFile().also {
        git(it, "init", "-q"); File(it, "graph.kt").writeText("graph base"); git(it, "add", ".")
        git(it, "-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-qm", "base")
    }
    private suspend fun stopped(workspace: AgentWorkspace, id: String) = withTimeout(30000) {
        while (workspace.get(id).status == AgentRunStatus.Running) delay(10)
    }
}

private const val REAKTOR_MCP_PROTOCOL_VERSION_FOR_TEST = "2025-06-18"
