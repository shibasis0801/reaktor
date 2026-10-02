package dev.shibasis.reaktor.tooling.cloud

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudflareAiTest {
    private lateinit var server: HttpServer
    private val seen = mutableMapOf<String, Map<String, String>>()

    @BeforeTest fun cloudflare() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun route(path: String, status: Int = 200, headers: Map<String, String> = emptyMap(), body: (String) -> String) = server.createContext(path) { exchange ->
            val request = exchange.requestBody.readBytes().decodeToString()
            seen[path] = exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() } + ("body" to request)
            val bytes = body(request).toByteArray()
            headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        route("/accounts/acct/ai/models/search") {
            """{"success":true,"result":[
              {"name":"@cf/moonshotai/kimi-k2.6","description":"Agentic coder","task":{"name":"Text Generation"},"properties":[
                {"property_id":"function_calling","value":"true"},{"property_id":"reasoning","value":"true"},
                {"property_id":"context_window","value":"262144"},{"property_id":"require_workers_paid","value":"true"},
                {"property_id":"price","value":[{"unit":"per M input tokens","price":0.95,"currency":"USD"},{"unit":"per M output tokens","price":4.0,"currency":"USD"}]}]},
              {"name":"@cf/baai/bge-m3","description":"Embeddings","task":{"name":"Text Embeddings"},"properties":[]}]}"""
        }
        route("/graphql") { query ->
            if ("aiInferenceAdaptiveGroups" in query) """{"data":{"viewer":{"accounts":[{"aiInferenceAdaptiveGroups":[
              {"count":1,"dimensions":{"modelId":"@cf/llava-hf/llava-1.5-7b-hf","errorCode":0},"sum":{"totalNeurons":0,"totalInputTokens":0,"totalOutputTokens":0,"totalInferenceTimeMs":40}},
              {"count":37,"dimensions":{"modelId":"@cf/llava-hf/llava-1.5-7b-hf","errorCode":5006},"sum":{"totalNeurons":0,"totalInputTokens":0,"totalOutputTokens":0,"totalInferenceTimeMs":10}},
              {"count":41,"dimensions":{"modelId":"@cf/meta/llama-3.3-70b-instruct-fp8-fast","errorCode":0},"sum":{"totalNeurons":1027.9,"totalInputTokens":9000,"totalOutputTokens":700,"totalInferenceTimeMs":20000}}]}]}}}"""
            else """{"data":{"viewer":{"accounts":[{"aiGatewayRequestsAdaptiveGroups":[
              {"count":12,"dimensions":{"gateway":"budbot","model":"@cf/meta/llama-3.3-70b-instruct-fp8-fast","provider":"workers-ai"},"sum":{"cost":0.01,"tokensIn":800,"tokensOut":90,"cachedRequests":2,"erroredRequests":1}}]}]}}}"""
        }
        route("/accounts/acct/ai-gateway/gateways", status = 401) { """{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}""" }
        route("/accounts/acct/ai/v1/chat/completions", headers = mapOf("cf-aig-log-id" to "log-1")) {
            """{"choices":[{"message":{"role":"assistant","content":"pong"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2}}"""
        }
        server.start()
    }

    @AfterTest fun stop() = server.stop(0)

    private fun ai() = CloudflareAi("acct", CloudflareApiToken("t"), base = "http://127.0.0.1:${server.address.port}", clock = { 1_790_000_000_000 })

    @Test fun aReadKeepsWhatItCouldReadAndNamesWhatItCouldNot() = runBlocking {
        val reading = ai().read("test")
        assertEquals(listOf("@cf/baai/bge-m3", "@cf/moonshotai/kimi-k2.6"), reading.models.map { it.id })
        val kimi = reading.models.last()
        assertTrue(kimi.functionCalling && kimi.reasoning && kimi.paidOnly)
        assertEquals(262_144L, kimi.contextWindow)
        assertEquals(listOf(0.95, 4.0), kimi.prices.map { it.usd })

        val llava = reading.usage.first { it.model.startsWith("@cf/llava") }
        assertEquals(38L, llava.requests)
        assertEquals(37L, llava.failed)
        assertEquals(mapOf(5006 to 37L), llava.failures)

        assertEquals(listOf("budbot"), reading.traffic.map { it.gateway })
        assertEquals(1L, reading.traffic.single().errors)
        assertEquals(emptyList(), reading.gateways)
        assertEquals("/ai-gateway/gateways: Authentication error", reading.failures["gateways"])
        assertEquals(setOf("gateways"), reading.failures.keys)
    }

    @Test fun aCompletionGoesThroughTheNamedGatewayWithItsMetadata() = runBlocking {
        val completion = ai().complete(buildJsonObject { put("model", "@cf/zai-org/glm-5.3") }, gateway = "reaktor", metadata = mapOf("seat" to "gateway"))
        assertEquals("log-1", completion.logId)
        val sent = seen.getValue("/accounts/acct/ai/v1/chat/completions")
        assertEquals("reaktor", sent["cf-aig-gateway-id"])
        assertEquals("""{"seat":"gateway"}""", sent["cf-aig-metadata"])
        assertEquals("Bearer t", sent["authorization"])
    }

    @Test fun theAccountIsTheOneMostWranglerConfigsName() {
        val root = Files.createTempDirectory("accounts").toFile()
        try {
            File(root, "targets/a/wrangler.json").apply { parentFile.mkdirs() }.writeText("""{"account_id": "${"a".repeat(32)}"}""")
            File(root, "targets/b/wrangler.jsonc").apply { parentFile.mkdirs() }.writeText("""{"account_id": "${"a".repeat(32)}"}""")
            File(root, "targets/c/wrangler.toml").apply { parentFile.mkdirs() }.writeText("""account_id = "${"b".repeat(32)}"""")
            File(root, "node_modules/x/wrangler.json").apply { parentFile.mkdirs() }.writeText("""{"account_id": "${"c".repeat(32)}"}""")
            if (System.getenv("CLOUDFLARE_ACCOUNT_ID").isNullOrBlank()) assertEquals("a".repeat(32), CloudflareAccounts.of(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
