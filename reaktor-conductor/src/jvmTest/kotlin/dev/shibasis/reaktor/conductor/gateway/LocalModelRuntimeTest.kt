package dev.shibasis.reaktor.conductor.gateway

import com.sun.net.httpserver.HttpServer
import dev.shibasis.reaktor.conductor.ActivityKind
import dev.shibasis.reaktor.conductor.ActivityStatus
import dev.shibasis.reaktor.conductor.AgentEvent
import dev.shibasis.reaktor.conductor.AgentId
import dev.shibasis.reaktor.conductor.AgentRequest
import dev.shibasis.reaktor.conductor.AgentSpec
import dev.shibasis.reaktor.conductor.Entitlement
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalModelRuntimeTest {
    private lateinit var server: HttpServer
    private lateinit var root: File
    private val bodies = mutableListOf<String>()
    private var models = """{"object":"list","data":[{"id":"qwen3.6:35b-a3b","object":"model"},{"id":"qwen3.8:27b-mlx","object":"model"}]}"""
    private var replies: List<String> = emptyList()
    private var loaded = """{"models":[]}"""

    @BeforeTest fun serve() {
        root = Files.createTempDirectory("local-workspace").toFile()
        File(root, "modules/Tabs.kt").apply { parentFile.mkdirs() }.writeText("class Tabs\nfun draft() = 1\n")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/models") { exchange ->
            val bytes = models.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/ps") { exchange ->
            val bytes = loaded.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/chat/completions") { exchange ->
            bodies += exchange.requestBody.readBytes().decodeToString()
            val bytes = replies[bodies.size - 1].toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterTest fun stop() {
        server.stop(0)
        root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
    }

    private val local get() = LocalModelServer(URI("http://127.0.0.1:${server.address.port}"))

    private fun request(model: String? = null) = AgentRequest(
        agent = AgentSpec(AgentId("scout"), "Scout", RuntimeKind.Local, "Find where things live.", model = model),
        prompt = "Where are drafts kept?",
        workingDirectory = root.path,
    )

    @Test fun aLocalModelReadsTheWorkspaceThroughTheSharedToolsAndAnswersOnTheFirstServedModel() = runBlocking {
        replies = listOf(
            """{"choices":[{"message":{"role":"assistant","content":"","reasoning":"Tabs.kt first.","tool_calls":[{"id":"call_a","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"modules/Tabs.kt\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":200,"completion_tokens":20}}""",
            """{"choices":[{"message":{"role":"assistant","content":"Drafts live in modules/Tabs.kt line 2."},"finish_reason":"stop"}],"usage":{"prompt_tokens":400,"completion_tokens":30}}""",
        )
        val events = LocalModelRuntime({ local }).run(request()).toList()

        assertEquals(listOf("Started", "Reasoning", "ToolUse", "Activity", "Activity", "Delta", "Finished"), events.map { it::class.simpleName })
        val started = events[3] as AgentEvent.Activity
        val finished = events[4] as AgentEvent.Activity
        assertEquals(listOf("call_a", "call_a"), listOf(started.item.id, finished.item.id))
        assertEquals(listOf(ActivityStatus.Started, ActivityStatus.Completed), listOf(started.item.status, finished.item.status))
        assertEquals(ActivityKind.Tool, finished.item.kind)
        assertTrue("\"path\":\"modules/Tabs.kt\"" in started.item.input.orEmpty())
        assertTrue("fun draft() = 1" in finished.item.output.orEmpty())
        val outcome = (events.last() as AgentEvent.Finished).outcome
        assertTrue(outcome.ok)
        assertEquals("Drafts live in modules/Tabs.kt line 2.", outcome.text)
        assertEquals(600L, outcome.usage?.inputTokens)
        assertEquals(50L, outcome.usage?.outputTokens)
        assertEquals("qwen3.6:35b-a3b", outcome.attributes["model"])
        assertEquals("Local server ${local.base}", outcome.attributes["route"])
        assertTrue("\"model\":\"qwen3.6:35b-a3b\"" in bodies[0])
        assertTrue("2\\tfun draft() = 1" in bodies[1])
        assertTrue("\"tool_call_id\":\"call_a\"" in bodies[1])
    }

    @Test fun aConversationThatWouldOutgrowTheLoadedContextStopsBeforeTheServerTruncatesIt() = runBlocking {
        File(root, "modules/Big.kt").writeText((1..400).joinToString("\n") { "val line$it = \"${"x".repeat(60)}\"" })
        loaded = """{"models":[{"name":"qwen3.6:35b-a3b","model":"qwen3.6:35b-a3b","context_length":4096}]}"""
        replies = listOf(
            """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"call_a","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"modules/Big.kt\",\"limit\":600}"}}]}}],"usage":{"prompt_tokens":900,"completion_tokens":20}}""",
            """{"choices":[{"message":{"role":"assistant","content":"Never sent."}}],"usage":{}}""",
        )
        val outcome = (LocalModelRuntime({ local }).run(request()).toList().last() as AgentEvent.Finished).outcome
        assertEquals(false, outcome.ok)
        assertTrue(outcome.failure.orEmpty().startsWith("Stopped before the conversation outgrew qwen3.6:35b-a3b's 4096-token context"), outcome.failure)
        assertEquals(1, bodies.size)
    }

    @Test fun aNamedModelIsUsedAsIs() = runBlocking {
        replies = listOf("""{"choices":[{"message":{"role":"assistant","content":"Done."}}],"usage":{}}""")
        val outcome = (LocalModelRuntime({ local }).run(request("qwen3.8:27b-mlx")).toList().last() as AgentEvent.Finished).outcome
        assertTrue(outcome.ok)
        assertTrue("\"model\":\"qwen3.8:27b-mlx\"" in bodies[0])
    }

    @Test fun aServerWithNoModelsOrNoServerAtAllFailsWithWhatToDo() = runBlocking {
        models = """{"object":"list","data":[]}"""
        val empty = (LocalModelRuntime({ local }).run(request()).toList().last() as AgentEvent.Finished).outcome
        assertEquals("The local model server at ${local.base} serves no models. Load or pull a model into it.", empty.failure)
        val gone = LocalModelServer(URI("http://127.0.0.1:1"))
        val absent = (LocalModelRuntime({ gone }).run(request()).toList().last() as AgentEvent.Finished).outcome
        assertEquals("No local model server answered at http://127.0.0.1:1. Start an OpenAI-compatible model server such as Ollama, " +
            "LM Studio or llama.cpp, or set REAKTOR_LOCAL_MODEL_URL to where it listens.", absent.failure)
        assertEquals(null, gone.capability().defaultModel)
        assertTrue(gone.capability().notes.single().startsWith("No local model server answered"))
    }

    @Test fun theCapabilityListsTheServedModelsAndSpendsTheLocalPool() {
        val capability = local.capability()
        assertEquals(listOf("qwen3.6:35b-a3b", "qwen3.8:27b-mlx"), capability.models.map { it.id })
        assertEquals("qwen3.6:35b-a3b", capability.defaultModel)
        assertEquals(listOf(Entitlement.LocalModel), capability.entitlements.map { it.entitlement })
    }

    @Test fun theReaktorSettingNamesAnyServerAndOllamaHostIsTheFallback() {
        assertEquals(URI("http://127.0.0.1:1234"), LocalModelServer.address("http://127.0.0.1:1234", "0.0.0.0:9999"))
        assertEquals(URI("http://studio.local:8080"), LocalModelServer.address("studio.local:8080"))
        assertEquals(URI("https://models.example"), LocalModelServer.address("https://models.example/v1"))
        assertEquals(URI("http://127.0.0.1:9999"), LocalModelServer.address("  ", "0.0.0.0:9999"))
        assertEquals(URI("http://127.0.0.1:1234"), LocalModelServer.fromEnvironment(mapOf("REAKTOR_LOCAL_MODEL_URL" to "127.0.0.1:1234", "OLLAMA_HOST" to "x:1")).base)
        assertEquals(URI("http://127.0.0.1:11434"), LocalModelServer.fromEnvironment(emptyMap()).base)
    }

    @Test fun theAddressFollowsOllamaHost() {
        assertEquals(URI("http://127.0.0.1:11434"), LocalModelServer.address(null, null))
        assertEquals(URI("http://127.0.0.1:11434"), LocalModelServer.address(null, "0.0.0.0"))
        assertEquals(URI("http://127.0.0.1:8080"), LocalModelServer.address(null, "0.0.0.0:8080"))
        assertEquals(URI("http://studio.local:11434"), LocalModelServer.address(null, "http://studio.local"))
        assertEquals(URI("https://models.example:443"), LocalModelServer.address(null, "https://models.example:443"))
    }
}
