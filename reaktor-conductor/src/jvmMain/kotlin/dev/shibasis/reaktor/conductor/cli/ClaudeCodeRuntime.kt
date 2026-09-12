package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.AgentEvent
import dev.shibasis.reaktor.conductor.AgentId
import dev.shibasis.reaktor.conductor.AgentOutcome
import dev.shibasis.reaktor.conductor.AgentRequest
import dev.shibasis.reaktor.conductor.AgentUsage
import dev.shibasis.reaktor.conductor.ProviderSession
import dev.shibasis.reaktor.conductor.RuntimeKind
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

    override fun argv(request: AgentRequest): List<String> = buildList {
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
        request.agent.budget.maxCostUsd?.let {
            add("--max-budget-usd")
            add(it.toString())
        }

        val tools = request.agent.tools
        if (!tools.allowWrites) {
            // Read, search, and run analysis; never edit the checkout.
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

    override fun parser(request: AgentRequest): CliEventParser =
        ClaudeCodeEventParser(request.agent.id)
}

/** Parses Claude Code's `stream-json` line protocol. */
class ClaudeCodeEventParser(
    private val agent: AgentId,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CliEventParser {
    private var session: ProviderSession? = null
    private var result: String? = null
    private var failed: String? = null
    private var usage: AgentUsage? = null
    private val text = StringBuilder()

    override fun onLine(line: String): List<AgentEvent> {
        val payload = line.jsonLineOrNull() ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return emptyList()

        return when (root.string("type")) {
            "system" -> {
                root.string("session_id")?.let {
                    session = ProviderSession(RuntimeKind.ClaudeCode, it)
                }
                listOf(AgentEvent.Started(agent, session))
            }

            "assistant" -> {
                val content = root["message"]?.jsonObject?.get("content")?.jsonArray.orEmpty()
                content.mapNotNull { element ->
                    val block = element.jsonObject
                    when (block.string("type")) {
                        "text" -> block.string("text")?.let { chunk ->
                            text.append(chunk)
                            AgentEvent.Delta(agent, chunk)
                        }

                        "tool_use" -> AgentEvent.ToolUse(agent, block.string("name") ?: "tool")
                        else -> null
                    }
                }
            }

            "result" -> {
                root.string("session_id")?.let {
                    session = ProviderSession(RuntimeKind.ClaudeCode, it)
                }
                val isError = root.boolean("is_error") == true
                val body = root.string("result")
                if (isError) failed = body ?: "Claude Code reported an error" else result = body
                usage = AgentUsage(
                    inputTokens = root.nested("usage")?.long("input_tokens"),
                    outputTokens = root.nested("usage")?.long("output_tokens"),
                    costUsd = root.double("total_cost_usd"),
                    durationMillis = root.long("duration_ms"),
                )
                emptyList()
            }

            else -> emptyList()
        }
    }

    override fun finish(exitCode: Int, stderr: String): AgentOutcome {
        val body = result ?: text.toString().ifBlank { null }
        val ok = failed == null && exitCode == 0 && body != null
        return AgentOutcome(
            agent = agent,
            text = body.orEmpty(),
            ok = ok,
            failure = when {
                ok -> null
                failed != null -> failed
                stderr.isNotBlank() -> stderr.trim().take(2000)
                else -> "claude exited with code $exitCode"
            },
            session = session,
            usage = usage,
        )
    }
}
