package dev.shibasis.reaktor.tooling.mcp.door

import dev.shibasis.reaktor.mcp.McpTool
import dev.shibasis.reaktor.mcp.ReaktorMcpServer
import dev.shibasis.reaktor.mcp.emptyObjectSchema
import dev.shibasis.reaktor.tooling.CallCaller
import dev.shibasis.reaktor.tooling.SafetyClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpDoorTest {
    private val directory: Path = Files.createTempDirectory("door-test")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest fun cleanUp() { scope.cancel(); directory.toFile().deleteRecursively() }

    @Test fun mergesProvidersKeepsOwnNamesAndPrefixesForeignOnes() {
        val door = door(mount("workspace", server("workspace", "agent_run", "agent_wait")), mount("compose", server("compose", "reload", "take_screenshot"), prefix = "compose"))
        val names = toolNames(door)
        assertEquals(listOf("reaktor_status", "reaktor_provider_refresh", "agent_run", "agent_wait", "compose_reload", "compose_take_screenshot"), names)
        assertEquals("reload from compose", call(door, "compose_reload").text())
        assertEquals("agent_run from workspace", call(door, "agent_run").text())
    }

    @Test fun aProviderThatGoesDownKeepsItsCallsListedAndSaysWhy() {
        var up = true
        val kernel = server("kernel", "graph_query")
        val link = FunctionMcpLink("kernel") { body -> if (up) kernel.handle(body) else error("Connection refused") }
        val door = door(DoorMount(McpLinkProvider("kernel", link), offlineHint = "Start the desktop app.", selfGoverned = true), mount("workspace", server("workspace", "agent_run")))
        assertTrue("graph_query" in toolNames(door))

        up = false
        assertTrue("graph_query" in toolNames(door), "a provider that is down must not vanish from the listing")
        val answer = call(door, "graph_query")
        assertEquals(true, answer["isError"]?.jsonPrimitive?.content?.toBoolean())
        assertTrue(answer.text().contains("Connection refused") && answer.text().contains("Start the desktop app."))
        assertEquals("agent_run from workspace", call(door, "agent_run").text(), "the other provider must keep working")

        val status = call(door, "reaktor_status")["structuredContent"]!!.jsonObject["providers"]!!.jsonArray
        assertEquals("Unavailable", status.first { it.jsonObject["id"]!!.jsonPrimitive.content == "kernel" }.jsonObject["availability"]!!.jsonPrimitive.content)
    }

    @Test fun snapshotsSurviveARestartSoListingNeverWaitsForAProvider() {
        door(mount("compose", server("compose", "reload"), prefix = "compose", cheap = false)).also { first ->
            assertFalse("compose_reload" in toolNames(first), "nothing is listed before the provider has ever been asked")
            call(first, "reaktor_provider_refresh", buildJsonObject { put("provider", "compose") })
            assertTrue("compose_reload" in toolNames(first))
        }
        var asked = 0
        val silent = FunctionMcpLink("compose") { asked++; error("must not be started just to list") }
        val second = door(DoorMount(McpLinkProvider("compose", NotCheap(silent)), prefix = "compose", selfGoverned = true))
        assertTrue("compose_reload" in toolNames(second))
        assertEquals(0, asked)
    }

    @Test fun theFirstProviderKeepsAClashingWriteAndTheDoorSaysSo() {
        val door = door(mount("one", server("one", "shared", readOnly = false)), mount("two", server("two", "shared", "only_two", readOnly = false)))
        assertEquals(1, toolNames(door).count { it == "shared" })
        assertEquals("shared from one", call(door, "shared").text())
        val status = call(door, "reaktor_status")["structuredContent"]!!.jsonObject
        assertEquals("one", status["hiddenByNameCollision"]!!.jsonArray.single().jsonObject["keptFrom"]!!.jsonPrimitive.content)
    }

    @Test fun aReadFallsThroughToTheNextProviderThatOffersItButAWriteDoesNot() {
        var kernelUp = true
        val kernel = ReaktorMcpServer("kernel", "1", "", listOf(tool("kernel", "graph_query", true), tool("kernel", "apply", false)))
        val desktop = ReaktorMcpServer("desktop", "1", "", listOf(tool("desktop", "graph_query", true), tool("desktop", "apply", false)))
        val link = FunctionMcpLink("kernel") { body -> if (kernelUp) kernel.handle(body) else error("Connection refused") }
        val door = door(DoorMount(McpLinkProvider("kernel", link), selfGoverned = true), mount("desktop", desktop))
        assertEquals("graph_query from kernel", call(door, "graph_query").text())

        kernelUp = false
        assertEquals("graph_query from desktop", call(door, "graph_query").text(), "either of two providers that answer the same read may be the one running")
        assertTrue(call(door, "apply").text().contains("Connection refused"), "a write is never handed to a different provider")
    }

    @Test fun everyCallLeavesOneLineWithoutItsArguments() {
        val door = door(mount("workspace", server("workspace", "agent_run")))
        toolNames(door)
        call(door, "agent_run", buildJsonObject { put("secret", "do-not-log-me") })
        val line = Files.readAllLines(directory.resolve("calls.jsonl")).single()
        val entry = DoorJson.parseToJsonElement(line).jsonObject
        assertEquals("claude-code", entry["seat"]!!.jsonPrimitive.content)
        assertEquals("workspace", entry["provider"]!!.jsonPrimitive.content)
        assertEquals("ok", entry["outcome"]!!.jsonPrimitive.content)
        assertFalse(line.contains("do-not-log-me"))
    }

    @Test fun aChildProcessIsStartedByItsFirstCallAndItsNoiseIsIgnored() {
        val environment = ChildEnvironment.of(DoorTransport.Stdio(emptyList()), File("."))
        val link = StdioMcpLink("fake", fakeServerCommand(), File("."), environment, scope, startupTimeoutMillis = 30_000, callTimeoutMillis = 30_000)
        val provider = McpLinkProvider("fake", link, SafetyClass.LocalEphemeral)
        try {
            val door = door(DoorMount(provider, prefix = "fake"))  // LocalEphemeral: below every seat's ceiling
            assertEquals("Unknown", provider.state().availability.name)
            assertFalse("fake_echo" in toolNames(door), "listing must not start the child")
            assertEquals("Unknown", provider.state().availability.name)
            call(door, "reaktor_provider_refresh", buildJsonObject { put("provider", "fake") })
            assertEquals(listOf("fake_echo", "fake_environment"), toolNames(door).filter { it.startsWith("fake_") })
            assertEquals("Available", provider.state().availability.name)
            assertEquals("hello", call(door, "fake_echo", buildJsonObject { put("value", "hello") }).text())
            val seen = call(door, "fake_environment").text().split(",").toSet()
            val withheld = System.getenv().keys - environment.keys
            assertEquals(emptySet(), (seen intersect withheld).filterNot { it.startsWith("__CF") }.toSet(), "the child saw variables outside the allow-list")
        } finally { runBlocking { provider.close() } }
        assertEquals("Unknown", provider.state().availability.name, "a closed child is simply not running")
    }

    @Test fun aChildSeesOnlyTheAllowListAndWhatItsEntryAdds() {
        val ambient = mapOf("PATH" to "/usr/bin", "HOME" to "/home/me", "AWS_SECRET_ACCESS_KEY" to "x", "FIREBASE_TOKEN" to "y", "EXTRA" to "z")
        val environment = ChildEnvironment.of(DoorTransport.Stdio(emptyList(), env = mapOf("ROOT" to "{workspace}/app"), inheritEnv = listOf("EXTRA")), File("/w"), ambient)
        assertEquals(setOf("PATH", "HOME", "EXTRA", "ROOT"), environment.keys)
        assertEquals("/w/app", environment["ROOT"])
        assertTrue(environment.getValue("PATH").startsWith("/usr/bin") && environment.getValue("PATH").contains("/opt/homebrew/bin"))
    }

    @Test fun aCommittedProviderListNamesWhereASecretIsAndTheMachinesListIsOverriddenByTheWorkspaces() {
        val home = Files.createTempDirectory("door-home").toFile()
        val workspace = Files.createTempDirectory("door-ws").toFile()
        try {
            File(home, ".reaktor").mkdirs(); File(workspace, ".reaktor").mkdirs()
            File(home, DoorConfig.PATH).writeText("""{"providers":[
                {"id":"github","transport":{"type":"http","url":"https://api.githubcopilot.com/mcp/readonly","bearer":{"type":"command","command":["gh","auth","token"]}}},
                {"id":"compose","mode":"Off","transport":{"type":"stdio","command":["./gradlew","hotMcpServer"]}}]}""")
            File(workspace, DoorConfig.PATH).writeText("""{"providers":[{"id":"compose","transport":{"type":"stdio","command":["./gradlew",":app:hotMcpServer"]}}]}""")
            val providers = DoorConfig.load(workspace, home).providers.associateBy { it.id }
            assertEquals(setOf("github", "compose"), providers.keys)
            assertEquals(DoorMode.OnDemand, providers.getValue("compose").mode, "the workspace's entry replaces the machine's")
            val bearer = (providers.getValue("github").transport as DoorTransport.Http).bearer
            assertEquals(DoorCredential.Command(listOf("gh", "auth", "token")), bearer)

            File(workspace, DoorConfig.PATH).writeText("""{"providers":[{"id":"leaky","transport":{"type":"http","url":"http://example.com/mcp"}}]}""")
            assertTrue(runCatching { DoorConfig.load(workspace, home) }.exceptionOrNull()?.message.orEmpty().contains("https"))
        } finally { home.deleteRecursively(); workspace.deleteRecursively() }
    }

    @Test fun aMissingCredentialIsAStateWithAnInstructionNotACrash() {
        val credentials = DoorCredentials(ambient = mapOf("PATH" to "/usr/bin:/bin"))
        val missing = runCatching { credentials.resolve(DoorCredential.Env("NOT_SET_ANYWHERE", whenMissing = "Create a token at example.com and export NOT_SET_ANYWHERE.")) }.exceptionOrNull()
        assertTrue(missing is CredentialMissing && missing.message!!.contains("Create a token"))
        assertEquals("from-a-tool", credentials.resolve(DoorCredential.Command(listOf("/bin/echo", "from-a-tool"))))
        val link = HttpMcpLink("cloud", { java.net.URI("https://example.invalid/mcp") }, headers = { mapOf("Authorization" to credentials.resolve(DoorCredential.Env("NOT_SET_ANYWHERE"))) })
        runCatching { runBlocking { link.exchange(buildJsonObject { put("id", 1) }) } }
        assertEquals("AuthRequired", link.state().availability.name)
    }

    private fun door(vararg mounts: DoorMount) = McpDoor(mounts.toList(), CallSnapshots(directory.resolve("snapshots")),
        CallLog(directory.resolve("calls.jsonl"), CallCaller(seat = "claude-code")), CallCaller(seat = "claude-code"), scope)

    private fun mount(id: String, server: ReaktorMcpServer, prefix: String = "", cheap: Boolean = true): DoorMount {
        val link = FunctionMcpLink(id) { body -> server.handle(body) }
        return DoorMount(McpLinkProvider(id, if (cheap) link else NotCheap(link)), prefix = prefix, selfGoverned = true)
    }

    private fun server(owner: String, vararg tools: String, readOnly: Boolean = true) = ReaktorMcpServer("test", "1", "", tools.map { tool(owner, it, readOnly) })

    private fun tool(owner: String, name: String, readOnly: Boolean) =
        McpTool(name, "Says who answered.", emptyObjectSchema(), readOnly = readOnly, idempotent = true) { buildJsonObject { put("said", "$name from $owner") } }

    /** Stands in for a provider that is expensive to start, without starting a process. */
    private class NotCheap(private val inner: McpLink) : McpLink by inner { override val cheap = false }

    private fun toolNames(door: McpDoor): List<String> =
        rpc(door, "tools/list")["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    private fun call(door: McpDoor, name: String, arguments: JsonObject = buildJsonObject {}): JsonObject =
        rpc(door, "tools/call", buildJsonObject { put("name", name); put("arguments", arguments) })

    private fun rpc(door: McpDoor, method: String, params: JsonObject = buildJsonObject {}): JsonObject =
        door.handle(buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", method); put("params", params) }.toString())!!
            .jsonObject["result"]!!.jsonObject

    /** The in-process servers answer `{"said": …}` wrapped as text; the child process answers with plain text. */
    private fun JsonObject.text(): String {
        val text = this["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        return runCatching { DoorJson.parseToJsonElement(text).jsonObject["said"]?.jsonPrimitive?.content }.getOrNull() ?: text
    }

    private fun fakeServerCommand(): List<String> = listOf(
        File(System.getProperty("java.home"), "bin/java").path, "-cp", System.getProperty("java.class.path"),
        "dev.shibasis.reaktor.tooling.mcp.door.FakeStdioMcpServerKt")
}
