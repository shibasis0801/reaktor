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
import kotlinx.serialization.json.jsonObject

/**
 * Codex as a runtime.
 *
 * Verified against `codex-cli 0.131.0`: `codex exec --json` emits one JSON object per line,
 * starting with `{"type":"thread.started","thread_id":...}`, then `{"type":"turn.started"}`, then
 * one `{"type":"item.completed","item":{...}}` per produced item, and ending with either
 * `{"type":"turn.completed","usage":{...}}` or `{"type":"turn.failed","error":{...}}`.
 *
 * The operator's own `config.toml` is used by default, so an agent here behaves like the same
 * agent run by hand: their model, profile, MCP servers and rules. Set
 * `ToolPolicy.isolateOperatorConfig` to run with `--ignore-user-config` instead, which is worth
 * doing for a reproducible run, or to route around a `config.toml` the installed CLI cannot parse.
 * Authentication resolves from `CODEX_HOME` either way.
 */
class CodexRuntime(
    executor: SupervisedProcessExecutor,
    private val binary: String = "codex",
) : CliAgentRuntime(executor) {
    override val kind: RuntimeKind = RuntimeKind.Codex

    override fun argv(request: AgentRequest): List<String> = buildList {
        add(binary)
        add("exec")

        // Resuming is an optimization over the canonical thread, never a dependency on it.
        val resume = request.resume?.takeIf { it.runtime == RuntimeKind.Codex }
        if (resume != null) {
            add("resume")
            add(resume.sessionId)
        }

        add("--json")
        add("--skip-git-repo-check")
        add("-C")
        add(request.workingDirectory)
        if (request.agent.tools.isolateOperatorConfig) add("--ignore-user-config")
        if (resume == null) add("--ephemeral")

        add("-s")
        add(if (request.agent.tools.allowWrites) "workspace-write" else "read-only")

        request.agent.model?.let {
            add("-m")
            add(it)
        }
        request.agent.tools.additionalDirectories.forEach {
            add("--add-dir")
            add(it)
        }

        addAll(request.agent.harnessArgs)

        // The prompt is positional and must be present, or codex reads stdin and blocks.
        add(request.prompt)
    }

    override fun parser(request: AgentRequest): CliEventParser = CodexEventParser(request.agent.id)
}

/** Parses Codex's `exec --json` line protocol. */
class CodexEventParser(
    private val agent: AgentId,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CliEventParser {
    private var session: ProviderSession? = null
    private var failed: String? = null
    private var usage: AgentUsage? = null
    private var completed = false
    private val text = StringBuilder()

    override fun onLine(line: String): List<AgentEvent> {
        val payload = line.jsonLineOrNull() ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return emptyList()

        return when (root.string("type")) {
            "thread.started" -> {
                root.string("thread_id")?.let {
                    session = ProviderSession(RuntimeKind.Codex, it)
                }
                listOf(AgentEvent.Started(agent, session))
            }

            "item.completed" -> {
                val item = root.nested("item") ?: return emptyList()
                when (item.string("type")) {
                    "agent_message" -> item.string("text")?.let { chunk ->
                        text.append(chunk)
                        listOf(AgentEvent.Delta(agent, chunk))
                    }.orEmpty()

                    "command_execution" -> listOf(
                        AgentEvent.ToolUse(agent, "command", item.string("command")),
                    )

                    "file_change", "patch_apply" -> listOf(
                        AgentEvent.ToolUse(agent, item.string("type") ?: "file_change"),
                    )

                    else -> emptyList()
                }
            }

            "turn.completed" -> {
                completed = true
                val reported = root.nested("usage")
                usage = AgentUsage(
                    inputTokens = reported?.long("input_tokens"),
                    outputTokens = reported?.long("output_tokens"),
                )
                emptyList()
            }

            "turn.failed" -> {
                failed = root.nested("error")?.string("message") ?: "Codex turn failed"
                emptyList()
            }

            "error" -> {
                failed = root.string("message") ?: "Codex reported an error"
                emptyList()
            }

            else -> emptyList()
        }
    }

    override fun finish(exitCode: Int, stderr: String): AgentOutcome {
        val body = text.toString().ifBlank { null }
        val ok = failed == null && exitCode == 0 && body != null
        return AgentOutcome(
            agent = agent,
            text = body.orEmpty(),
            ok = ok,
            failure = when {
                ok -> null
                failed != null -> failed
                !completed && stderr.isNotBlank() -> stderr.trim().take(2000)
                else -> "codex exited with code $exitCode"
            },
            session = session,
            usage = usage,
        )
    }
}
