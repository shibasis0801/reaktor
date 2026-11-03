package dev.shibasis.reaktor.tooling.cloud

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely

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
    private var compatReply: (String) -> String = {
        """{"model":"workers-ai/@cf/meta/llama-3.3-70b-instruct-fp8-fast","choices":[{"message":{"role":"assistant","content":"gateway pong","reasoning_content":"checked cache"},"finish_reason":"stop"}],"usage":{"prompt_tokens":14,"completion_tokens":3,"total_tokens":17,"neurons":1.25,"cost":0.0004}}"""
    }

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
        route("/acct/reaktor/compat/chat/completions", headers = mapOf("cf-aig-log-id" to "gw-log-1", "cf-aig-cache-status" to "HIT")) { request ->
            compatReply(request)
        }
        route("/acct/reaktor/workers-ai/v1/chat/completions", headers = mapOf("cf-aig-log-id" to "gw-log-2", "cf-aig-cache-status" to "MISS")) {
            """{"model":"@cf/moonshotai/kimi-k2.7-code","choices":[{"message":{"role":"assistant","content":"workers-ai pong"},"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":5,"neurons":2.5}}"""
        }
        route("/acct/missing/compat/chat/completions", status = 400) {
            """{"success":false,"error":[{"code":2001,"message":"Please configure AI Gateway in the Cloudflare dashboard"}],"message":"Please configure AI Gateway in the Cloudflare dashboard"}"""
        }
        route("/accounts/acct/ai-gateway/gateways/reaktor/logs") {
            """{"success":true,"result":[
              {"id":"gw-log-1","created_at":"2026-10-30T07:45:00Z","model":"workers-ai/@cf/meta/llama-3.3-70b-instruct-fp8-fast","provider":"workers-ai","success":true,"cached":true,"tokens_in":14,"tokens_out":3,"cost":0.0004,"duration":42,"status_code":200,"metadata":"{\"source\":\"reaktor-orchestration\",\"task\":\"gateway-titles\"}"}
            ]}"""
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

    @Test fun gatewayClientSendsCompatAndWorkersAiCompletionsWithCacheAndAuthHeaders() = runBlocking {
        val client = ai().gateway("reaktor")
        val compat = client.complete(
            prompt = "ping",
            model = "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
            system = "Answer concisely.",
            cacheKey = "reaktor-key-1",
            cacheTtlSeconds = 1800,
            skipCache = true,
            metadata = mapOf("task" to "gateway-client", "seat" to "s4"),
        )
        assertEquals("gateway pong", compat.text)
        assertEquals("checked cache", compat.reasoning)
        assertEquals("gw-log-1", compat.logId)
        assertTrue(compat.cached)
        assertEquals("reaktor", compat.gateway)
        assertEquals("workers-ai/@cf/meta/llama-3.3-70b-instruct-fp8-fast", compat.model)
        assertEquals(14L, compat.usage.inputTokens)
        assertEquals(3L, compat.usage.outputTokens)
        assertEquals(17L, compat.usage.totalTokens)
        assertEquals(1.25, compat.usage.neurons)
        assertEquals(0.0004, compat.usage.cost)

        val compatSent = seen.getValue("/acct/reaktor/compat/chat/completions")
        assertEquals("Bearer t", compatSent["authorization"])
        assertEquals("Bearer t", compatSent["cf-aig-authorization"])
        assertEquals("reaktor-key-1", compatSent["cf-aig-cache-key"])
        assertEquals("1800", compatSent["cf-aig-cache-ttl"])
        assertEquals("true", compatSent["cf-aig-skip-cache"])
        assertEquals("""{"task":"gateway-client","seat":"s4"}""", compatSent["cf-aig-metadata"])
        assertTrue(""""model":"workers-ai/@cf/meta/llama-3.3-70b-instruct-fp8-fast"""" in compatSent.getValue("body"))

        val workersAi = client.complete(
            request = buildJsonObject {
                put("model", "workers-ai/@cf/moonshotai/kimi-k2.7-code")
                put("max_tokens", 256)
            },
            route = AiGatewayRoute.WorkersAi,
            cacheKey = AiGatewayClient.cacheKeyFor("workers", "kimi"),
            cacheTtlSeconds = 600,
        )
        assertEquals("workers-ai pong", workersAi.text)
        assertEquals("gw-log-2", workersAi.logId)
        assertEquals(false, workersAi.cached)
        assertEquals("@cf/moonshotai/kimi-k2.7-code", workersAi.model)
        assertEquals(20L, workersAi.usage.inputTokens)
        assertEquals(5L, workersAi.usage.outputTokens)
        assertEquals(25L, workersAi.usage.totalTokens)
        assertEquals(2.5, workersAi.usage.neurons)

        val workersSent = seen.getValue("/acct/reaktor/workers-ai/v1/chat/completions")
        assertEquals("Bearer t", workersSent["cf-aig-authorization"])
        assertEquals("600", workersSent["cf-aig-cache-ttl"])
        assertEquals(null, workersSent["cf-aig-skip-cache"])
        assertTrue(""""model":"@cf/moonshotai/kimi-k2.7-code"""" in workersSent.getValue("body"))

        val failure = runCatching { ai().gateway("missing").complete(prompt = "ping") }.exceptionOrNull()
        assertEquals("/missing/compat/chat/completions: Please configure AI Gateway in the Cloudflare dashboard", failure?.message)
    }

    @Test fun orchestrationTasksProduceTitlesDigestsAndTriageWithRecordedAnswersAndLogs() = runBlocking {
        val client = ai().gateway("reaktor")

        compatReply = {
            """{"choices":[{"message":{"role":"assistant","content":"```json\n{\"title\":\"Cloudflare AI Gateway Client\",\"summary\":\"Builds the gateway client and verifies completion logs.\"}\n```"},"finish_reason":"stop"}],"usage":{"prompt_tokens":45,"completion_tokens":18,"cost":0.0002}}"""
        }
        val titled = client.titleRun(
            firstPrompt = "You are Argon, attached to hangar:launch as seat s4 to build the Cloudflare AI Gateway client.",
            latestMessages = listOf("Claimed gateway-client", "Verified /compat/chat/completions headers and usage"),
        )
        assertEquals("Cloudflare AI Gateway Client", titled.title)
        assertTrue(titled.title.split(" ").size in 3..6)
        assertEquals("Builds the gateway client and verifies completion logs.", titled.summary)
        assertEquals("gw-log-1", titled.logId)
        assertTrue(titled.cached)
        assertEquals(45L, titled.usage.inputTokens)
        assertEquals(18L, titled.usage.outputTokens)
        assertEquals(0.0002, titled.usage.cost)
        val titleSent = seen.getValue("/acct/reaktor/compat/chat/completions")
        assertTrue(titleSent["cf-aig-cache-key"].orEmpty().startsWith("title-"))
        assertTrue(""""task":"gateway-titles"""" in titleSent["cf-aig-metadata"].orEmpty())

        compatReply = {
            """{"choices":[{"message":{"role":"assistant","content":"{\"title\":\"Too Many Words In This Generated Run Title\",\"summary\":\"Line one\\nLine two\"}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":10}}"""
        }
        val boundedTitle = client.titleRun(firstPrompt = "Shorten overlong titles.")
        assertEquals("Too Many Words In This Generated", boundedTitle.title)
        assertEquals(6, boundedTitle.title.split(" ").size)
        assertEquals("Line one", boundedTitle.summary)

        compatReply = {
            """{"choices":[{"message":{"role":"assistant","content":"{\"title\":\"Memory And Gateway Ready\",\"summary\":\"Osprey assembled memory actors while gateway-client landed in reaktor-tooling.\",\"highlights\":[\"memory-store in progress\",\"gateway-client symbols published\"]}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":60,"completion_tokens":24,"cost":0.0003}}"""
        }
        val digest = client.digestChanges(
            inbox = listOf("Osprey: memory-extract waits for gateway-client symbol"),
            planChanges = listOf("memory-store -> Doing", "gateway-client -> Doing"),
            agent = "gemini-argon",
        )
        assertEquals("Memory And Gateway Ready", digest.title)
        assertTrue(digest.title.split(" ").size in 3..6)
        assertEquals("Osprey assembled memory actors while gateway-client landed in reaktor-tooling.", digest.summary)
        assertEquals(listOf("memory-store in progress", "gateway-client symbols published"), digest.highlights)
        assertEquals("gw-log-1", digest.logId)
        val digestSent = seen.getValue("/acct/reaktor/compat/chat/completions")
        assertTrue(digestSent["cf-aig-cache-key"].orEmpty().startsWith("digest-"))
        assertTrue(""""agent":"gemini-argon"""" in digestSent["cf-aig-metadata"].orEmpty())

        compatReply = {
            """{"choices":[{"message":{"role":"assistant","content":"{\"priority\":\"high\",\"parent\":\"agents-gateway\",\"after\":[\"gateway-client\",\"unknown-id\",\"agents-gateway\"],\"summary\":\"Depends on gateway-client under the agents-gateway component.\"}"},"finish_reason":"stop"}],"usage":{"prompt_tokens":70,"completion_tokens":22,"cost":0.0004}}"""
        }
        val triage = client.triageItem(
            title = "Runs and threads get short titles",
            detail = "Summarize first prompt and latest messages through AI Gateway.",
            existingItems = listOf(
                AiPlanItemCandidate(id = "agents-gateway", title = "Orchestration tasks call models through Cloudflare AI Gateway", kind = "Component", priority = "High"),
                AiPlanItemCandidate(id = "gateway-client", title = "One client sends completions through the gateway", kind = "Task", priority = "High", parent = "agents-gateway"),
            ),
        )
        assertEquals("High", triage.priority)
        assertEquals("agents-gateway", triage.parent)
        assertEquals(listOf("gateway-client"), triage.after)
        assertEquals("Depends on gateway-client under the agents-gateway component.", triage.summary)
        assertEquals("gw-log-1", triage.logId)
        val triageSent = seen.getValue("/acct/reaktor/compat/chat/completions")
        assertTrue(triageSent["cf-aig-cache-key"].orEmpty().startsWith("triage-"))
        assertTrue(""""task":"gateway-triage"""" in triageSent["cf-aig-metadata"].orEmpty())

        val logs = ai().logs("reaktor")
        assertEquals(1, logs.size)
        val log = logs.single()
        assertEquals("gw-log-1", log.id)
        assertEquals(0.0004, log.cost)
        assertTrue(log.cached)
        assertEquals(14L, log.tokensIn)
        assertEquals(3L, log.tokensOut)
        assertTrue(log.metadata.orEmpty().contains("gateway-titles"))
    }

    @Test fun theAccountComesOnlyFromTheDeclaredFleet() {
        val root = Files.createTempDirectory("accounts").toFile()
        try {
            val first = File(root, "targets/a/final-prod.json").apply { parentFile.mkdirs() }
            val second = File(root, "targets/b/final-prod.json").apply { parentFile.mkdirs() }
            first.writeText("""{"name":"a","account_id":"${"a".repeat(32)}"}""")
            second.writeText("""{"name":"b","account_id":"${"a".repeat(32)}"}""")
            File(root, "targets/decoy/wrangler.json").apply { parentFile.mkdirs() }
                .writeText("""{"account_id":"${"b".repeat(32)}"}""")
            val fleet = File(root, "build/dependeasy/contracts/cloudflare/fleet.json").apply { parentFile.mkdirs() }
            fleet.writeText("""{"schema":1,"defaultEnvironment":"prod","workers":[
                {"name":"a","node":{"build":"fixture","project":":a","name":"deploy"},"directory":"targets/a",
                 "configurations":{"prod":"targets/a/final-prod.json"},"services":{"prod":"a"}},
                {"name":"b","node":{"build":"fixture","project":":b","name":"deploy"},"directory":"targets/b",
                 "configurations":{"prod":"targets/b/final-prod.json"},"services":{"prod":"b"}}]}""")
            if (System.getenv("CLOUDFLARE_ACCOUNT_ID").isNullOrBlank()) {
                assertEquals("a".repeat(32), CloudflareAccounts.of(root))
                second.writeText("""{"name":"b","account_id":"${"b".repeat(32)}"}""")
                assertEquals(null, CloudflareAccounts.of(root))
                fleet.delete()
                assertEquals(null, CloudflareAccounts.of(root))
            }
        } finally {
            root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir")))
        }
    }
}
