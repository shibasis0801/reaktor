package dev.shibasis.reaktor.conductor.gateway

import dev.shibasis.reaktor.cloud.cloudflare.AiCompletion
import dev.shibasis.reaktor.conductor.ActivityKind
import dev.shibasis.reaktor.conductor.ActivityStatus
import dev.shibasis.reaktor.conductor.AgentActivityItem
import dev.shibasis.reaktor.conductor.AgentEvent
import dev.shibasis.reaktor.conductor.AgentId
import dev.shibasis.reaktor.conductor.AgentOutcome
import dev.shibasis.reaktor.conductor.AgentRequest
import dev.shibasis.reaktor.conductor.AgentRuntime
import dev.shibasis.reaktor.conductor.AgentSpec
import dev.shibasis.reaktor.conductor.AgentUsage
import dev.shibasis.reaktor.conductor.ReasoningFidelity
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.workspace.HybridWorkspaceReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

class ChatRoute(
    val label: String,
    val defaultModel: String,
    val window: suspend (model: String) -> Int? = { null },
    val complete: suspend (body: JsonObject, metadata: Map<String, String>) -> AiCompletion,
)

class ChatToolRuntime(
    override val kind: RuntimeKind,
    private val clock: () -> Long = System::currentTimeMillis,
    private val open: suspend (File) -> ChatRoute,
) : AgentRuntime {
    override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
        val agent = request.agent.id
        emit(AgentEvent.Started(agent, null))
        val root = File(request.workingDirectory)
        val route = try {
            open(root)
        } catch (failure: Exception) {
            return@flow finish(agent, failure.message ?: "The model route could not be opened")
        }
        val model = request.agent.model?.takeIf { it.isNotBlank() } ?: route.defaultModel
        val reader = HybridWorkspaceReader(root)
        val messages = mutableListOf(message("system", instructions(request.agent, root)), message("user", request.prompt))
        val started = clock()
        var input = 0L
        var output = 0L
        var neurons = 0.0
        val logs = mutableListOf<String>()
        var window: Int? = null
        var reported = 0L
        fun outcome(text: String, failure: String? = null) = AgentOutcome(
            agent = agent, text = text, ok = failure == null, failure = failure,
            usage = AgentUsage(inputTokens = input, outputTokens = output, durationMillis = clock() - started),
            attributes = buildMap {
                put("model", model)
                put("route", route.label)
                if (neurons > 0) put("neurons", "%.1f".format(neurons))
                if (logs.isNotEmpty()) put("gatewayLogs", logs.joinToString(","))
            },
        )
        repeat(MaxSteps) {
            if (clock() - started > request.agent.budget.timeoutMillis) {
                return@flow emit(AgentEvent.Finished(agent, outcome("", "Stopped at the ${request.agent.budget.timeoutMillis / 1000} s budget before an answer")))
            }
            window?.let { limit ->
                val estimate = maxOf(reported, messages.sumOf { it.toString().length } / CharsPerToken.toLong())
                if (estimate > limit * WindowShare) return@flow emit(AgentEvent.Finished(agent, outcome("",
                    "Stopped before the conversation outgrew $model's $limit-token context (about $estimate tokens so far). " +
                        "Ask a narrower question, or raise the server's context length.")))
            }
            val completion = try {
                route.complete(body(model, messages, request.agent), metadata(request, root))
            } catch (failure: Exception) {
                return@flow emit(AgentEvent.Finished(agent, outcome("", "${route.label} refused $model: ${failure.message}")))
            }
            completion.logId?.let(logs::add)
            val usage = completion.body["usage"] as? JsonObject
            reported = usage.long("prompt_tokens")
            input += reported
            if (window == null) window = runCatching { route.window(model) }.getOrNull()
            output += usage.long("completion_tokens")
            neurons += (usage?.get("neurons") as? JsonPrimitive)?.doubleOrNull ?: 0.0
            val choice = (completion.body["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            val reply = choice?.get("message") as? JsonObject
                ?: return@flow emit(AgentEvent.Finished(agent, outcome("", "$model returned no message")))
            (reply.text("reasoning_content") ?: reply.text("reasoning"))?.let { emit(AgentEvent.Reasoning(agent, it, ReasoningFidelity.Thinking)) }
            val calls = (reply["tool_calls"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            if (calls.isEmpty()) {
                val text = reply.text("content").orEmpty()
                if (text.isNotBlank()) emit(AgentEvent.Delta(agent, text))
                return@flow emit(AgentEvent.Finished(agent, if (text.isBlank())
                    outcome("", "$model stopped without an answer (finish reason ${choice.text("finish_reason") ?: "unknown"})") else outcome(text)))
            }
            messages += buildJsonObject {
                put("role", "assistant")
                put("content", reply.text("content").orEmpty())
                put("tool_calls", JsonArray(calls))
            }
            calls.forEachIndexed { index, call ->
                val function = call["function"] as? JsonObject
                val name = function.text("name").orEmpty()
                val arguments = (function?.get("arguments") as? JsonObject) ?: runCatching {
                    Json.parseToJsonElement(function.text("arguments") ?: "{}").jsonObject
                }.getOrDefault(JsonObject(emptyMap()))
                val id = call.text("id") ?: "call-$index"
                emit(AgentEvent.ToolUse(agent, name, arguments.text("path") ?: arguments.text("query")))
                emit(AgentEvent.Activity(agent, AgentActivityItem(id, ActivityKind.Tool, name, ActivityStatus.Started, input = arguments.toString().take(ActivityChars))))
                val run = withContext(Dispatchers.IO) { runCatching { tool(reader, name, arguments) } }
                val result = run.getOrElse { "Refused: ${it.message}" }
                emit(AgentEvent.Activity(agent, AgentActivityItem(id, ActivityKind.Tool, name,
                    if (run.isSuccess) ActivityStatus.Completed else ActivityStatus.Failed, output = result.take(ActivityChars), detailTruncated = result.length > ActivityChars)))
                messages += buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", id)
                    put("content", result)
                }
            }
        }
        emit(AgentEvent.Finished(agent, outcome("", "Stopped after $MaxSteps tool steps without an answer")))
    }

    private suspend fun FlowCollector<AgentEvent>.finish(agent: AgentId, failure: String) =
        emit(AgentEvent.Finished(agent, AgentOutcome(agent = agent, text = "", ok = false, failure = failure)))

    private fun body(model: String, messages: List<JsonObject>, agent: AgentSpec) = buildJsonObject {
        put("model", model)
        put("max_tokens", 8192)
        put("messages", JsonArray(messages))
        put("tools", Tools)
        agent.effort?.let { put("reasoning_effort", it.value) }
    }

    private fun metadata(request: AgentRequest, root: File) = buildMap {
        put("source", "reaktor-agent")
        put("agent", request.agent.id.value)
        put("workspace", root.name)
        request.executionId?.let { put("run", it) }
    }

    private fun instructions(agent: AgentSpec, root: File) = buildString {
        if (agent.instructions.isNotBlank()) appendLine(agent.instructions.trim()).appendLine()
        append("You are working in the repository ${root.name}. Read it with read_file, search and list; those tools see only this repository. ")
        append("Search for specific identifiers and narrow with a glob: everything a tool returns is sent again on every later step. ")
        append("You cannot edit files or run commands, so say exactly what should change and where. ")
        append("Ground every claim in a file and line you read, and say plainly when something could not be checked.")
    }

    private fun tool(reader: HybridWorkspaceReader, name: String, arguments: JsonObject): String = when (name) {
        "read_file" -> reader.read(arguments.text("path").orEmpty(), (arguments["offset"] as? JsonPrimitive)?.intOrNull ?: 0,
            ((arguments["limit"] as? JsonPrimitive)?.intOrNull ?: 250).coerceAtMost(600)).let { read ->
            "${read.path} lines ${read.firstLine}-${read.firstLine + read.text.lines().size - 1} of ${read.totalLines}${if (read.truncated) " (more follows)" else ""}\n${read.text}"
        }
        "search" -> reader.search(arguments.text("query").orEmpty(), arguments.text("glob"), 30).let { found ->
            (found.matches.map { it.take(200) }.ifEmpty { listOf("No matches.") } +
                listOfNotNull("More matches exist; search for something more specific or pass a glob.".takeIf { found.truncated })).joinToString("\n")
        }
        "list" -> reader.list(arguments.text("path") ?: ".").joinToString("\n").ifBlank { "Empty directory." }
        else -> "Unknown tool $name."
    }

    private companion object {
        const val MaxSteps = 16
        const val CharsPerToken = 3
        const val WindowShare = 0.85
        const val ActivityChars = 2_000

        val Tools = buildJsonArray {
            addJsonObject { function("read_file", "Read a file of this repository with line numbers.") {
                putJsonObject("path") { put("type", "string"); put("description", "Path relative to the repository root") }
                putJsonObject("offset") { put("type", "integer"); put("description", "First line to read, counted from 0") }
                putJsonObject("limit") { put("type", "integer"); put("description", "How many lines to read, at most 1200") }
            } }
            addJsonObject { function("search", "Search this repository for a literal string. Build output and ignored files are skipped.") {
                putJsonObject("query") { put("type", "string") }
                putJsonObject("glob") { put("type", "string"); put("description", "Optional file pattern such as *.kt") }
            } }
            addJsonObject { function("list", "List one directory of this repository.") {
                putJsonObject("path") { put("type", "string"); put("description", "Directory relative to the repository root; . for the root") }
            } }
        }

        fun JsonObjectBuilder.function(name: String, description: String, properties: JsonObjectBuilder.() -> Unit) {
            put("type", "function")
            putJsonObject("function") {
                put("name", name)
                put("description", description)
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties", properties)
                    putJsonArray("required") { if (name != "list") add(if (name == "search") "query" else "path") }
                }
            }
        }
    }
}

private fun message(role: String, content: String) = buildJsonObject {
    put("role", role)
    put("content", content)
}

private fun JsonObject?.text(key: String): String? = ((this?.get(key)) as? JsonPrimitive)?.contentOrNull

private fun JsonObject?.long(key: String): Long = ((this?.get(key)) as? JsonPrimitive)?.longOrNull ?: 0

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
