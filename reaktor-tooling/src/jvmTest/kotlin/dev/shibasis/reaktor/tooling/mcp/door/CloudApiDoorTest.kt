package dev.shibasis.reaktor.tooling.mcp.door

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely

import com.sun.net.httpserver.HttpServer
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.tooling.operation.*
import org.koin.dsl.koinApplication
import dev.shibasis.reaktor.tooling.CallCaller
import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.api.CloudApiTools
import dev.shibasis.reaktor.tooling.api.CloudApis
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CloudApiDoorTest {
    private val directory = Files.createTempDirectory("cloud-door")
    private val dependencies = koinApplication {}
    private val graph = Graph(dependencyAdapter = KoinDependencyAdapter(dependencies))
    private val approvals = DoorApprovals(directory.resolve("approvals"))
    private lateinit var server: HttpServer
    private val hits = mutableListOf<String>()
    private lateinit var kernel: OperationKernel

    @BeforeTest fun cloudflare() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v4/accounts/acct/workers/scripts") { exchange ->
            hits += "${exchange.requestMethod} ${exchange.requestURI.path} ${exchange.requestHeaders.getFirst("Authorization")}"
            val bytes = """{"success":true,"result":[{"id":"bot-service"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v4/accounts/acct/workers/domains") { exchange ->
            hits += "${exchange.requestMethod} ${exchange.requestURI.path}"
            val bytes = """{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}""".toByteArray()
            exchange.sendResponseHeaders(403, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val spec = """
            {"openapi":"3.0.3","info":{"title":"Cloudflare API","version":"4"},"servers":[{"url":"http://127.0.0.1:${server.address.port}/v4"}],
             "paths":{"/accounts/{account_id}/workers/scripts":{"parameters":[{"name":"account_id","in":"path","required":true,"schema":{"type":"string"}}],
               "get":{"operationId":"worker-script-list-workers","summary":"List Workers"}},
              "/accounts/{account_id}/workers/domains":{"parameters":[{"name":"account_id","in":"path","required":true,"schema":{"type":"string"}}],
               "get":{"operationId":"worker-domain-list-domains","summary":"List Domains","x-api-token-group":["Workers Scripts Read","Workers Scripts Write"]}},
              "/accounts/{account_id}/workers/scripts/{script_name}":{"parameters":[{"name":"account_id","in":"path","required":true,"schema":{"type":"string"}},
                {"name":"script_name","in":"path","required":true,"schema":{"type":"string"}}],
               "put":{"operationId":"worker-script-upload-worker-module","summary":"Upload Worker Module"}}}}
        """.trimIndent()
        val workspace = directory.resolve("workspace").toFile().apply { File(this, "config").mkdirs() }
        File(workspace, "config/cloudflare.token").writeText("fixture-token\n")
        val apis = CloudApis(workspace, home = directory.resolve("home").toFile(), sources = mapOf("cloudflare" to "fixture"), fetch = { spec })
        graph.attach(OperationNode(graph, "cloud", CloudApiTools.operations(apis)))
        kernel = OperationKernel(graph, OperationAuthority(policy = DoorPolicy.Guarded, approvals = approvals,
            log = CallLog(directory.resolve("calls.jsonl"), CallCaller("codex"))))
        val catalog = kernel.catalog().filter { it.provider == "cloud" }
        assertEquals(6, catalog.size)
        assertEquals(SafetyClass.ProductionReversibleWrite, catalog.single { it.id.value == "cloud.api.write" }.effect)
        assertEquals(SafetyClass.Destructive, catalog.single { it.id.value == "cloud.api.delete" }.effect)
    }

    @AfterTest fun stop() {
        server.stop(0)
        graph.close()
        dependencies.close()
        directory.toFile().deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir")))
    }

    @Test fun anAgentFindsAnyOperationReadsFreelyAndWaitsForAPersonToChangeAnything() = runBlocking {
        val found = call("cloud.api.search", buildJsonObject { put("query", "list workers"); put("api", "cloudflare") }).text()
        assertTrue("cloudflare:worker-script-list-workers" in found, found)

        val read = call("cloud.api.read", buildJsonObject {
            put("operation", "cloudflare:worker-script-list-workers")
            putJsonObject("arguments") { put("account_id", "acct") }
        }).text()
        assertTrue("bot-service" in read, read)
        assertEquals(listOf("GET /v4/accounts/acct/workers/scripts Bearer fixture-token"), hits)

        val upload = buildJsonObject {
            put("operation", "cloudflare:worker-script-upload-worker-module")
            putJsonObject("arguments") { put("account_id", "acct"); put("script_name", "bot-service"); putJsonObject("body") { put("main_module", "index.js") } }
        }
        val request = Invocation(OperationId("cloud.api.write"), upload, CallCaller("codex"), "fixture-upload")
        val held = assertIs<Outcome.ApprovalRequired>(kernel.invoke(request))
        assertEquals(1, hits.size, "nothing changes on Cloudflare before a person approves")

        val misfiled = call("cloud.api.read", upload).text()
        assertTrue("causes ProductionReversibleWrite" in misfiled, misfiled)
        assertEquals(1, hits.size)
        approvals.decide(held.approval, approve = true, by = "fixture-person")
        assertIs<Outcome.Completed>(kernel.invoke(request))
        assertIs<Outcome.Completed>(kernel.invoke(request))
        assertEquals(2, hits.size, "the approved provider write executes once")
        assertTrue(hits.last().startsWith("PUT "))
    }

    @Test fun aRefusedReadSaysWhichSignInWasUsedAndWhatTheOperationNeeds() = runBlocking {
        val refused = call("cloud.api.read", buildJsonObject {
            put("operation", "cloudflare:worker-domain-list-domains")
            putJsonObject("arguments") { put("account_id", "acct") }
        }).text()
        assertTrue("config/cloudflare.token" in refused && "Workers Scripts Read, Workers Scripts Write" in refused, refused)
    }

    private suspend fun call(name: String, arguments: JsonObject): Outcome =
        kernel.invoke(Invocation(OperationId(name), arguments, CallCaller("codex")))

    private fun Outcome.text(): String = when (this) {
        is Outcome.Completed -> result.toString()
        is Outcome.Failed -> message
        else -> error("Unexpected outcome $this")
    }
}
