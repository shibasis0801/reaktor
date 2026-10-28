package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.tooling.SupervisedProcessExecutor
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.koin.dsl.koinApplication
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class AgentBrowserMcpTest {
    @Test fun pairingIsExactPrivateAndRotatable() {
        val directory = Files.createTempDirectory("browser-pairing")
        try {
            for (origin in listOf("https://manna.ac/", "https://manna.ac/ask", "http://manna.ac", "https://user@manna.ac", "null"))
                assertFailsWith<IllegalArgumentException> { AgentBrowserPairing.pair(directory, origin, "http://127.0.0.1:1234") }
            val first = AgentBrowserPairing.pair(directory, "https://manna.ac", "http://127.0.0.1:1234/mcp")
            assertEquals(JsonPrimitive("http://127.0.0.1:1234/browser/mcp"), first["endpoint"])
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve("browser-mcp.json")))
            val second = AgentBrowserPairing.pair(directory, "https://manna.ac", "http://127.0.0.1:1234")
            assertNotEquals(first["token"], second["token"])
            assertEquals(second.getValue("token").jsonPrimitive.content, AgentBrowserPairing.credentials(directory)?.second)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun inferenceReceivesOnlyTheQuestionAndReportsTheObservedModel() = runBlocking {
        val directory = Files.createTempDirectory("browser-inference")
        val dependencies = koinApplication {}
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(dependencies))
        var calls = 0
        val fake = object : AgentRuntime {
            override val kind = RuntimeKind.Gemini
            override fun run(request: AgentRequest) = flow {
                calls++
                assertEquals("What is 17 times 23?", request.prompt)
                assertEquals("gemini-4-argon-high", request.agent.model)
                assertEquals(directory.resolve("browser-inference/workspace").toString(), request.workingDirectory)
                assertNull(request.resume); assertFalse(request.persistSession)
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "391", true, attributes = mapOf("model" to if (calls == 1) "gemini-4-argon-high" else "different-model"))))
            }
        }
        try {
            AgentBrowserMcp(directory, graph, SupervisedProcessExecutor(), fake).use { browser ->
                val arguments = buildJsonObject { put("question", "What is 17 times 23?"); put("requestId", "345c7b67-0f0a-4076-a08b-93d5114e4eb1") }
                val answer = browser.ask(arguments)
                assertFalse(answer.isError)
                assertEquals(JsonPrimitive("391"), answer.data.jsonObject["reply"])
                assertEquals(JsonPrimitive("gemini-4-argon-high"), answer.data.jsonObject["model"])
                assertEquals(answer, browser.ask(arguments), "A repeated request returns its retained answer")
                assertEquals(1, calls)
                assertTrue(browser.ask(JsonObject(arguments + ("question" to JsonPrimitive("Different question")))).isError)
                assertTrue(browser.ask(JsonObject(arguments + ("requestId" to JsonPrimitive("a6c61b8d-83f8-4219-b4dd-6f7e119d1d71")))).isError, "A different observed model must be refused")
                assertTrue(browser.ask(JsonObject(arguments + ("workspace" to JsonPrimitive("secret")))).isError)
                assertTrue(browser.ask(JsonObject(arguments + ("question" to JsonPrimitive("x".repeat(6001))))).isError)
                assertEquals(2, calls)
                val tools = browser.endpoint.handler.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").toString()
                assertTrue(tools.contains("inference.ask")); assertFalse(tools.contains("kernel"))
                val refused = browser.endpoint.handler.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"kernel","arguments":{}}}""").toString()
                assertTrue(refused.contains("error") || refused.contains("isError\":true"))
            }
            AgentBrowserMcp(directory, graph, SupervisedProcessExecutor(), fake).use { reopened ->
                val arguments = buildJsonObject { put("question", "What is 17 times 23?"); put("requestId", "345c7b67-0f0a-4076-a08b-93d5114e4eb1") }
                assertEquals(JsonPrimitive("391"), reopened.ask(arguments).data.jsonObject["reply"])
                assertEquals(2, calls, "Restarted projection must not start a completed request")
                val receipt = directory.resolve("browser-inference/requests/345c7b67-0f0a-4076-a08b-93d5114e4eb1.json")
                val saved = Json.parseToJsonElement(Files.readString(receipt)).jsonObject
                atomicWrite(receipt, JsonObject(saved.filterKeys { it == "digest" }).toString())
                assertTrue(reopened.ask(arguments).isError)
                assertEquals(2, calls, "An interrupted admission must not start again")
            }
        } finally { graph.close(); dependencies.close(); directory.toFile().deleteRecursively() }
    }
}
