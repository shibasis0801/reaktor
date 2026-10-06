package dev.shibasis.reaktor.tooling.mcp.door

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely

import com.sun.net.httpserver.HttpServer
import dev.shibasis.reaktor.mcp.ReaktorMcpServer
import dev.shibasis.reaktor.tooling.CallCaller
import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.api.CloudApiTools
import dev.shibasis.reaktor.tooling.api.CloudApis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CloudApiDoorTest {
    private val directory = Files.createTempDirectory("cloud-door")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val approvals = DoorApprovals(directory.resolve("approvals"))
    private lateinit var server: HttpServer
    private val hits = mutableListOf<String>()
    private lateinit var door: McpDoor

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
        val handler = ReaktorMcpServer("cloud", "1", "", CloudApiTools.tools(apis))
        val mount = DoorMount(McpLinkProvider("cloud", FunctionMcpLink("cloud") { handler.handle(it) }, SafetyClass.UnknownRemoteEffect), callClasses = CloudApiTools.classes)
        door = McpDoor(listOf(mount), CallSnapshots(directory.resolve("snapshots")), CallLog(directory.resolve("calls.jsonl"), CallCaller("codex")),
            CallCaller("codex"), scope, approvals = approvals, workspace = workspace.path)
        rpc("tools/list")
    }

    @AfterTest fun stop() {
        server.stop(0)
        scope.cancel()
        directory.toFile().deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir")))
    }

    @Test fun anAgentFindsAnyOperationReadsFreelyAndWaitsForAPersonToChangeAnything() {
        val found = call("cloud_api_search", buildJsonObject { put("query", "list workers"); put("api", "cloudflare") }).text()
        assertTrue("cloudflare:worker-script-list-workers" in found, found)

        val read = call("cloud_api_read", buildJsonObject {
            put("operation", "cloudflare:worker-script-list-workers")
            putJsonObject("arguments") { put("account_id", "acct") }
        }).text()
        assertTrue("bot-service" in read, read)
        assertEquals(listOf("GET /v4/accounts/acct/workers/scripts Bearer fixture-token"), hits)

        val upload = buildJsonObject {
            put("operation", "cloudflare:worker-script-upload-worker-module")
            putJsonObject("arguments") { put("account_id", "acct"); put("script_name", "bot-service"); putJsonObject("body") { put("main_module", "index.js") } }
        }
        val held = call("cloud_api_write", upload)
        assertEquals("approval_required", held["structuredContent"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(1, hits.size, "nothing changes on Cloudflare before a person approves")

        val misfiled = call("cloud_api_read", upload).text()
        assertTrue("call it with cloud_api_write" in misfiled, misfiled)
        assertEquals(1, hits.size)
    }

    @Test fun aRefusedReadSaysWhichSignInWasUsedAndWhatTheOperationNeeds() {
        val refused = call("cloud_api_read", buildJsonObject {
            put("operation", "cloudflare:worker-domain-list-domains")
            putJsonObject("arguments") { put("account_id", "acct") }
        }).text()
        assertTrue("config/cloudflare.token" in refused && "Workers Scripts Read, Workers Scripts Write" in refused, refused)
    }

    private fun call(name: String, arguments: JsonObject): JsonObject =
        assertNotNull(rpc("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) }))

    private fun rpc(method: String, params: JsonObject = buildJsonObject {}): JsonObject? =
        door.handle(buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", method); put("params", params) }.toString())!!.jsonObject["result"]?.jsonObject

    private fun JsonObject.text(): String = this["content"]!!.jsonArray.joinToString("\n") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
}
