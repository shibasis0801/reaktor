package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.AgentEvent
import dev.shibasis.reaktor.conductor.AgentId
import dev.shibasis.reaktor.conductor.AgentOutcome
import dev.shibasis.reaktor.conductor.AgentRequest
import dev.shibasis.reaktor.conductor.AgentUsage
import dev.shibasis.reaktor.conductor.EffortRecord
import dev.shibasis.reaktor.conductor.ReasoningFidelity
import dev.shibasis.reaktor.conductor.ProviderSession
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.AgentActivityItem
import dev.shibasis.reaktor.conductor.ActivityKind
import dev.shibasis.reaktor.conductor.ActivityStatus
import dev.shibasis.reaktor.tooling.SupervisedProcessExecutor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Claude Code as a runtime.
 *
 * Uses the harness rather than the model API, so the agent keeps its own tools, subagents, skills,
 * hooks, and permission handling. Reaktor supplies the prompt and reads the event stream.
 *
 * Verified against `claude 2.1.183`: `--output-format stream-json` emits one JSON object per line,
 * `{"type":"system","subtype":"init",...}` first with the session id, then `{"type":"assistant",...}`
 * per message, then exactly one `{"type":"result",...}` carrying the final text, cost, and usage.
 */
class ClaudeCodeRuntime(
    executor: SupervisedProcessExecutor,
    private val binary: String = "claude",
) : CliAgentRuntime(executor) {
    override val kind: RuntimeKind = RuntimeKind.ClaudeCode

    override fun argv(request: AgentRequest): List<String> = claudeArgv(request, binary)

    override fun parser(request: AgentRequest): CliEventParser = ClaudeCodeEventParser(
        request.agent.id,
        // The result envelope reports no effective effort, so `observed` stays unknown.
        request.agent.effort?.let { EffortRecord(requested = it, resolved = it) } ?: EffortRecord.none,
    )
}

/** The exact argv a Claude turn runs with, lifted out so the flag set can be asserted directly. */
internal fun claudeArgv(request: AgentRequest, binary: String = "claude"): List<String> = buildList {
    add(binary)
    add("-p")
    add(request.prompt)
    add("--output-format")
    add("stream-json")
    add("--verbose")

    request.agent.model?.let {
        add("--model")
        add(it)
    }
    request.agent.effort?.let {
        add("--effort")
        add(it.value)
    }
    request.agent.budget.maxCostUsd?.let {
        add("--max-budget-usd")
        add(it.toString())
    }

    val tools = request.agent.tools
    if (!tools.allowWrites) {
        // Restricts these built-in editors; Bash/MCP permissions still belong to the harness.
        add("--disallowedTools")
        addAll(listOf("Write", "Edit", "NotebookEdit"))
    }
    if (tools.allow.isNotEmpty()) {
        add("--allowedTools")
        addAll(tools.allow)
    }
    if (tools.deny.isNotEmpty()) {
        add("--disallowedTools")
        addAll(tools.deny)
    }
    tools.additionalDirectories.forEach {
        add("--add-dir")
        add(it)
    }
    tools.mcpConfig?.let {
        add("--mcp-config")
        add(it)
        if (tools.strictMcpConfig) add("--strict-mcp-config")
    }

    addAll(request.agent.harnessArgs)

    // The provider session is a cache: resuming is an optimization, never a requirement.
    request.resume?.takeIf { it.runtime == RuntimeKind.ClaudeCode }?.let {
        add("--resume")
        add(it.sessionId)
    }
}

/** Parses Claude Code's `stream-json` line protocol. */
class ClaudeCodeEventParser(
    private val agent: AgentId,
    private val effort: EffortRecord = EffortRecord.none,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CliEventParser {
    private var session: ProviderSession? = null
    private var result: String? = null
    private var failed: String? = null
    private var usage: AgentUsage? = null
    private var serviceTier: String? = null
    private var completed = false
    private val text = StringBuilder()
    private val toolNames = mutableMapOf<String, String>()

    override fun onLine(line: String): List<AgentEvent> {
        val payload = line.jsonLineOrNull() ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return emptyList()

        val events = when (root.string("type")) {
            "system" -> {
                if (root.string("subtype") != "init") return emptyList()
                root.string("session_id")?.let {
                    session = ProviderSession(RuntimeKind.ClaudeCode, it)
                }
                listOf(AgentEvent.Started(agent, session))
            }

            "assistant" -> {
                val content = root["message"]?.jsonObject?.get("content")?.jsonArray.orEmpty()
                val parentToolUse = root.string("parent_tool_use_id")
                content.mapNotNull { element ->
                    val block = element.jsonObject
                    when (block.string("type")) {
                        "text" -> block.string("text")?.let { chunk ->
                            text.append(chunk)
                            AgentEvent.Delta(agent, chunk)
                        }

                        // Verified against 2.1.270: the payload is `thinking`, not `text`, and it
                        // carries a `signature` that is deliberately dropped — it authenticates the
                        // block for the API and is not content anyone should be shown.
                        "thinking" -> block.string("thinking")?.takeIf { it.isNotBlank() }?.let { chunk ->
                            AgentEvent.Reasoning(agent, chunk, ReasoningFidelity.Thinking, parentToolUse)
                        }

                        "tool_use" -> {
                            val id = block.string("id") ?: "unknown-tool"
                            val name = block.string("name") ?: "tool"
                            toolNames[id] = name
                            AgentEvent.Activity(agent, AgentActivityItem(id, ActivityKind.Tool, name,
                                ActivityStatus.Started, input = block["input"]?.toString(), parentId = parentToolUse))
                        }
                        else -> null
                    }
                }
            }

            "user" -> root.nested("message")?.get("content")?.jsonArray.orEmpty().mapNotNull { element ->
                val block = element.jsonObject
                if (block.string("type") != "tool_result") null else {
                    val id = block.string("tool_use_id") ?: return@mapNotNull null
                    AgentEvent.Activity(agent, AgentActivityItem(id, ActivityKind.Tool, toolNames.remove(id) ?: "Tool result",
                        if (block.boolean("is_error") == true) ActivityStatus.Failed else ActivityStatus.Completed,
                        output = block["content"]?.toString(), parentId = root.string("parent_tool_use_id")))
                }
            }

            "result" -> {
                completed = true
                root.string("session_id")?.let {
                    session = ProviderSession(RuntimeKind.ClaudeCode, it)
                }
                val isError = root.boolean("is_error") != false || root.string("subtype") != "success"
                val body = root.string("result")
                if (isError) failed = body ?: "Claude Code reported an error" else result = body
                val reported = root.nested("usage")
                val input = reported?.long("input_tokens")
                val cached = reported?.long("cache_read_input_tokens")
                val written = reported?.long("cache_creation_input_tokens")
                usage = AgentUsage(
                    inputTokens = if (input != null && cached != null && written != null) input + cached + written else null,
                    cachedInputTokens = cached,
                    cacheWriteInputTokens = written,
                    outputTokens = reported?.long("output_tokens"),
                    reasoningOutputTokens = reported?.nested("output_tokens_details")?.long("thinking_tokens"),
                    costUsd = root.double("total_cost_usd"),
                    durationMillis = root.long("duration_ms"),
                )
                serviceTier = reported?.string("service_tier")
                emptyList()
            }

            else -> emptyList()
        }
        return events.flatMap { event ->
            if (event is AgentEvent.Activity && event.item.kind == ActivityKind.Tool && event.item.status == ActivityStatus.Started)
                listOf(AgentEvent.ToolUse(agent, event.item.title), event) else listOf(event)
        }
    }

    override fun finish(exitCode: Int, stderr: String): AgentOutcome {
        val body = result ?: text.toString().ifBlank { null }
        val ok = completed && failed == null && exitCode == 0 && body != null
        return AgentOutcome(
            agent = agent,
            text = body.orEmpty(),
            ok = ok,
            failure = when {
                ok -> null
                failed != null -> failed
                !completed && stderr.isBlank() -> "Claude stream ended without a result envelope"
                stderr.isNotBlank() -> stderr.trim().take(2000)
                else -> "claude exited with code $exitCode"
            },
            session = session,
            usage = usage,
            effort = effort,
            serviceTier = serviceTier,
        )
    }
}
