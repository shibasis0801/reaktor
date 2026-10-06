package dev.shibasis.reaktor.conductor.gateway

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely

import com.sun.net.httpserver.HttpServer
import dev.shibasis.reaktor.conductor.AgentEvent
import dev.shibasis.reaktor.conductor.AgentId
import dev.shibasis.reaktor.conductor.AgentRequest
import dev.shibasis.reaktor.conductor.AgentSpec
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.tooling.cloud.CloudflareAi
import dev.shibasis.reaktor.tooling.cloud.CloudflareApiToken
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GatewayRuntimeTest {
    private lateinit var server: HttpServer
    private lateinit var root: File
    private val bodies = mutableListOf<String>()
    private val headers = mutableListOf<Map<String, String>>()
    private var replies: List<String> = emptyList()

    @BeforeTest fun cloudflare() {
        root = Files.createTempDirectory("gateway-workspace").toFile()
        File(root, "modules/Tabs.kt").apply { parentFile.mkdirs() }.writeText("class Tabs\nfun draft() = 1\n")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/accounts/acct/ai/v1/chat/completions") { exchange ->
            bodies += exchange.requestBody.readBytes().decodeToString()
            headers += exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
            val bytes = replies[bodies.size - 1].toByteArray()
            exchange.responseHeaders.add("cf-aig-log-id", "log-${bodies.size}")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest fun stop() {
        server.stop(0)
        root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir")))
    }

    private fun runtime(gateway: String?) = GatewayRuntime(
        connect = { CloudflareAi("acct", CloudflareApiToken("t"), base = "http://127.0.0.1:${server.address.port}") },
        gateway = { gateway },
    )

    private fun request(model: String? = null) = AgentRequest(
        agent = AgentSpec(AgentId("reviewer"), "Reviewer", RuntimeKind.Gateway, "Review for correctness.", model = model),
        prompt = "Where are drafts kept?",
        workingDirectory = root.path,
        executionId = "run-1",
    )

    @Test fun theModelReadsTheWorkspaceThroughItsToolsAndAnswers() = runBlocking {
        replies = listOf(
            """{"choices":[{"message":{"role":"assistant","content":"","reasoning_content":"Look at Tabs.kt first.","tool_calls":[{"id":"call-1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"modules/Tabs.kt\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":120,"completion_tokens":30,"neurons":4.5}}""",
            """{"choices":[{"message":{"role":"assistant","content":"Drafts live in modules/Tabs.kt line 2."},"finish_reason":"stop"}],"usage":{"prompt_tokens":300,"completion_tokens":40,"neurons":9.0}}""",
        )
        val events = runtime("reaktor").run(request()).toList()

        assertEquals(listOf("Started", "Reasoning", "ToolUse", "Delta", "Finished"), events.map { it::class.simpleName })
        assertEquals("modules/Tabs.kt", (events[2] as AgentEvent.ToolUse).detail)
        val outcome = (events.last() as AgentEvent.Finished).outcome
        assertTrue(outcome.ok)
        assertEquals("Drafts live in modules/Tabs.kt line 2.", outcome.text)
        assertEquals(420L, outcome.usage?.inputTokens)
        assertEquals(70L, outcome.usage?.outputTokens)
        assertEquals("AI Gateway reaktor", outcome.attributes["route"])
        assertEquals(GatewayRuntime.DefaultModel, outcome.attributes["model"])
        assertEquals("13.5", outcome.attributes["neurons"])
        assertEquals("log-1,log-2", outcome.attributes["gatewayLogs"])

        assertTrue("2\\tfun draft() = 1" in bodies[1], "the second turn carries the file the model asked for")
        assertTrue("\"tool_call_id\":\"call-1\"" in bodies[1])
        assertEquals("reaktor", headers[0]["cf-aig-gateway-id"])
        assertTrue("\"run\":\"run-1\"" in headers[0]["cf-aig-metadata"].orEmpty())
    }

    @Test fun aPathOutsideTheWorkspaceIsRefusedAndTheRefusalGoesBackToTheModel() = runBlocking {
        replies = listOf(
            """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"call-1","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"../../etc/passwd\"}"}}]}}],"usage":{}}""",
            """{"choices":[{"message":{"role":"assistant","content":"I could not read that file."}}],"usage":{}}""",
        )
        val outcome = (runtime(null).run(request("@cf/zai-org/glm-5.3")).toList().last() as AgentEvent.Finished).outcome
        assertTrue(outcome.ok)
        assertTrue("Refused: Path is outside the workspace" in bodies[1])
        assertEquals("Workers AI, no gateway", outcome.attributes["route"])
        assertEquals(null, headers[0]["cf-aig-gateway-id"])
        assertTrue("\"model\":\"@cf/zai-org/glm-5.3\"" in bodies[0])
    }

    @Test fun anAnswerlessStopIsAFailureThatSaysWhy() = runBlocking {
        replies = listOf("""{"choices":[{"message":{"role":"assistant","content":null,"reasoning_content":"thinking"},"finish_reason":"length"}],"usage":{}}""")
        val outcome = (runtime(null).run(request()).toList().last() as AgentEvent.Finished).outcome
        assertEquals(false, outcome.ok)
        assertEquals("${GatewayRuntime.DefaultModel} stopped without an answer (finish reason length)", outcome.failure)
    }
}
