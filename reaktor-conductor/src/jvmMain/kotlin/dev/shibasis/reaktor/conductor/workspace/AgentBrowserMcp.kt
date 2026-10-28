package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.cli.AntigravityEventParser
import dev.shibasis.reaktor.conductor.cli.CliAgentRuntime
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.mcp.*
import dev.shibasis.reaktor.tooling.SupervisedProcessExecutor
import dev.shibasis.reaktor.tooling.mcp.BrowserMcpEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64

/** A separate, revocable browser credential. It never grants workspace operations. */
object AgentBrowserPairing {
    fun pair(directory: Path, origin: String, ownerUrl: String): JsonObject {
        require(validOrigin(origin)) { "Use an exact HTTPS origin, without a path, or a loopback development origin" }
        privateDirectory(directory)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        atomicWrite(directory.resolve("browser-mcp.json"), buildJsonObject { put("origin", origin); put("token", token) }.toString())
        return buildJsonObject { put("endpoint", java.net.URI(ownerUrl).resolve("/browser/mcp").toString()); put("token", token); put("origin", origin) }
    }

    internal fun credentials(directory: Path): Pair<String, String>? = runCatching {
        val value = Json.parseToJsonElement(Files.readString(directory.resolve("browser-mcp.json"))).jsonObject
        val origin = value.getValue("origin").jsonPrimitive.content
        val token = value.getValue("token").jsonPrimitive.content
        require(validOrigin(origin) && token.length >= 32)
        origin to token
    }.getOrNull()

    private fun validOrigin(origin: String): Boolean = runCatching {
        val uri = URI(origin)
        uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.isNullOrEmpty() && origin == "${uri.scheme}://${uri.rawAuthority}" &&
            (uri.scheme == "https" || uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]"))
    }.getOrDefault(false)
}

internal class AgentBrowserMcp(
    private val directory: Path,
    private val graph: Graph,
    private val executor: SupervisedProcessExecutor = SupervisedProcessExecutor(),
    private val runtime: AgentRuntime = BrowserAntigravityRuntime(executor, directory.resolve("browser-inference")),
) : AutoCloseable {
    private val busy = Mutex()
    private val node = ToolNode(graph, listOf(Tool(ToolDefinition(
        "inference.ask", "Answer the supplied question through Antigravity. No workspace context or development tools.",
        objectSchema(mapOf("question" to stringSchema("Question, at most 6000 characters"), "requestId" to stringSchema("A UUID for this request")), listOf("question", "requestId")),
        readOnly = true, openWorld = true,
    )) { arguments -> ask(arguments) })).also(graph::attach)
    private val adapter = McpAdapter(node, "reaktor-browser-inference", "1.0.0", projection = setOf("inference.ask"))
    val endpoint = BrowserMcpEndpoint({ AgentBrowserPairing.credentials(directory) }, McpMessageHandler { body -> runBlocking { adapter.handle(body) } })

    internal suspend fun ask(arguments: JsonObject): ToolResult {
        val question = (arguments["question"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        val id = (arguments["requestId"] as? JsonPrimitive)?.content.orEmpty()
        if (arguments.keys != setOf("question", "requestId") || question.isBlank() || question.length > 6000 ||
            !id.matches(Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"))) return ToolResult.failure("Supply a question of 1–6000 characters and a UUID requestId")
        if (!busy.tryLock()) return ToolResult.failure("Antigravity is answering another browser question. Try again when it finishes.")
        try {
            val work = directory.resolve("browser-inference/workspace").also(::privateDirectory)
            val started = System.currentTimeMillis()
            val agent = AgentSpec(AgentId(id), "Browser question", RuntimeKind.Gemini, "Answer the user's question using only the question text.",
                model = "gemini-4-argon-high", budget = AgentBudget(timeoutMillis = 90_000))
            var outcome: AgentOutcome? = null
            runtime.run(AgentRequest(agent, question, work.toString())).collect { if (it is AgentEvent.Finished) outcome = it.outcome }
            val answer = outcome ?: return ToolResult.failure("Antigravity ended without a result")
            if (!answer.ok || answer.text.isBlank()) return ToolResult.failure("Antigravity could not answer this question. Check the local account and try again.")
            if (answer.attributes["model"] != agent.model) return ToolResult.failure("Gemini Argon High is required. Antigravity returned a different or unknown model; no answer was accepted.")
            return ToolResult(buildJsonObject {
                put("requestId", id); put("provider", "Antigravity"); put("model", answer.attributes["model"] ?: "unknown")
                put("reply", answer.text); put("elapsedMillis", System.currentTimeMillis() - started)
                answer.session?.sessionId?.let { put("conversationId", it) }
            })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return ToolResult.failure("The local Antigravity process failed. Check that agy is installed and signed in.") }
        finally { busy.unlock() }
    }

    override fun close() { adapter.close(); graph.detach(node); executor.close() }
}

/** Own native configuration, empty working folder, no operator MCP servers or permission bypass. */
internal class BrowserAntigravityRuntime(
    executor: SupervisedProcessExecutor,
    private val directory: Path,
    private val operatorHome: Path = Path.of(System.getProperty("user.home")),
) : CliAgentRuntime(executor) {
    override val kind = RuntimeKind.Gemini

    override fun argv(request: AgentRequest): List<String> {
        val config = directory.resolve("home/.gemini/antigravity-cli").also(::privateDirectory)
        val source = operatorHome.resolve(".gemini/antigravity-cli")
        require(Files.isRegularFile(source.resolve("antigravity-oauth-token"))) { "Sign in to Antigravity first" }
        for (name in listOf("antigravity-oauth-token", "bin", "installation_id")) {
            val target = config.resolve(name)
            if (!Files.exists(target)) Files.createSymbolicLink(target, source.resolve(name))
            require(Files.isSymbolicLink(target) && Files.readSymbolicLink(target) == source.resolve(name))
        }
        atomicWrite(config.resolve("settings.json"), """{"permissions":{"deny":["read_file(*)","write_file(*)","command(*)","mcp(*)","read_url(*)","execute_url(*)"]},"toolPermission":"request-review"}""")
        return listOf(operatorHome.resolve(".local/bin/agy").toString(), "--print",
            "Answer only from the following question. File, shell, URL and MCP tools are denied. Do not attempt to use tools.\n\n${request.prompt}",
            "--output-format", "stream-json", "--model", requireNotNull(request.agent.model),
            "--print-timeout", "${request.agent.budget.timeoutMillis}ms", "--disable-slash-commands")
    }

    override fun environment(request: AgentRequest) = request.environment + ("HOME" to directory.resolve("home").toString())
    override fun parser(request: AgentRequest) = AntigravityEventParser(request.agent.id, budgetMillis = request.agent.budget.timeoutMillis)
}
