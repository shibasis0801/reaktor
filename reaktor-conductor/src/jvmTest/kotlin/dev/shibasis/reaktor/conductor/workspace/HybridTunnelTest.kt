package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.*

class HybridTunnelTest {

    @Test fun theHostnameIsReadFromTheAddressAndNotFromTheBannerAroundIt() {
        assertEquals("https://cute-fish-abc.trycloudflare.com",
            tunnelOrigin("|  https://cute-fish-abc.trycloudflare.com                                     |"))
        assertEquals("https://planner.shibasis.dev", tunnelOrigin("INF |  https://planner.shibasis.dev  |"))
        assertNull(tunnelOrigin("INF Registered tunnel connection connIndex=0"))
        // cloudflared's first line is prose containing the terms URL. Taking the first link on the
        // page publishes www.cloudflare.com and every ChatGPT request comes back 301.
        assertNull(tunnelOrigin("INF Thank you for trying Cloudflare Tunnel. ... subject to the Cloudflare " +
            "Online Services Terms of Use (https://www.cloudflare.com/website-terms/), and Cloudflare reserves " +
            "the right ... follow: https://developers.cloudflare.com/cloudflare-one/connections/connect-apps"))
        assertEquals("https://releases-edwards-investigations-convergence.trycloudflare.com",
            tunnelOrigin("2026-09-15T08:35:29Z INF |  https://releases-edwards-investigations-convergence.trycloudflare.com    |"))
    }

    /**
     * The whole path, from outside.
     *
     * Reachability is the one property this feature cannot be unit tested into having: ChatGPT calls
     * from OpenAI's servers, so the only question that matters is whether a request that leaves this
     * machine comes back with the tool list.
     */
    @Test fun arequestFromThePublicInternetReachesTheConnector() {
        assumeTrue(System.getenv("REAKTOR_TUNNEL_LIVE") == "1")
        val root = source(); val directory = Files.createTempDirectory("tunnel-live")
        AgentWorkspace(root, directory, mapOf(RuntimeKind.Gemini to object : AgentRuntime {
            override val kind = RuntimeKind.Gemini
            override fun run(request: AgentRequest) = flow<AgentEvent> {}
        }), discover = { ProviderCapability(it) }).use { workspace ->
            HybridConnector.start(workspace).use { connector ->
                HybridTunnel.start(connector.port()).use { tunnel ->
                    val url = tunnel.url(connector)
                    assertTrue(url.startsWith("https://"), url)
                    val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""
                    val response = retry { post(url, body) }
                    assertEquals(200, response.statusCode(), response.body())
                    val tools = Json.parseToJsonElement(response.body()).jsonObject
                        .getValue("result").jsonObject.getValue("tools").jsonArray
                        .map { it.jsonObject.getValue("name").jsonPrimitive.content }
                    assertEquals(setOf("reaktor_waiting_tasks", "reaktor_packet", "reaktor_reply", "reaktor_await"), tools.toSet())

                    // The secret still guards it once it is public, which is the only thing between
                    // this address and anyone who finds it.
                    assertEquals(404, post(tunnel.origin + "/mcp/" + HybridConnector.newSecret(), body).statusCode())
                    println("Connector reachable from the public internet at $url")
                }
            }
        }
        root.deleteRecursively(); directory.toFile().deleteRecursively()
    }

    private fun retry(attempts: Int = 10, block: () -> HttpResponse<String>): HttpResponse<String> {
        var last: HttpResponse<String>? = null
        repeat(attempts) {
            last = runCatching(block).getOrNull()
            if (last?.statusCode() == 200) return last!!
            Thread.sleep(1500)
        }
        return last ?: error("The tunnel never answered")
    }

    private fun post(url: String, body: String): HttpResponse<String> = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI(url)).POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString())

    private fun git(root: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText(); check(process.waitFor() == 0) { output }
    }
    private fun source(): File = Files.createTempDirectory("tunnel-source").toFile().also {
        git(it, "init", "-q"); File(it, "graph.kt").writeText("graph base"); git(it, "add", ".")
        git(it, "-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-qm", "base")
    }
}
