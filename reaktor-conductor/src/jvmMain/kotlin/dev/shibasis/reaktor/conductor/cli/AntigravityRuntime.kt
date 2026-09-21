package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.SupervisedProcessExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import java.io.File

/**
 * Gemini through Antigravity, on the saved Google account.
 *
 * The seat exists to spend an allowance nothing else is spending, so what it runs on has to be
 * worth spending it on. Two things follow, and both used to be wrong here.
 *
 * It **names a model rather than inheriting one.** Antigravity's own setting is a UI preference that
 * goes stale — this machine's says `Gemini 3.5 Flash (High)`, which the service no longer serves —
 * and an unnamed model silently becomes whatever the CLI falls back to, which is the cheapest tier.
 * A fallback seat that quietly runs the weakest model is worse than no fallback, because it looks
 * like it worked.
 *
 * And it **treats "no capacity" as a routing problem, not a failure.** Capacity is per model and
 * transient; the turn is fine, that one model is full. So [candidates] is a preference order and a
 * capacity refusal moves down it, which is the difference between a dead run and a slower one.
 */
class AntigravityRuntime(
    executor: SupervisedProcessExecutor,
    private val binary: String = "agy",
    /**
     * Models to try, best first.
     *
     * Pro before Flash because this seat does real work; the Flash entries are the degradation path
     * for a busy hour, not a cost saving. Every entry is on the same Google allowance, so moving
     * down the list changes what answers, never who pays.
     */
    private val candidates: List<String> = defaultAntigravityModels,
) : CliAgentRuntime(executor) {
    override val kind = RuntimeKind.Gemini

    override fun argv(request: AgentRequest): List<String> {
        val file = File(System.getProperty("user.home"), ".gemini/antigravity-cli/settings.json")
        val settings = if (file.isFile) Json.parseToJsonElement(file.readText()).jsonObject else JsonObject(emptyMap())
        // The one guard that decides which pool pays. A configured API provider would bill the
        // operator directly, which is the opposite of what a spare-capacity seat is for.
        require(settings["modelProvider"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
            "The hybrid seat requires Antigravity Google-account authentication. Its configuration currently selects a model API provider."
        }
        return antigravityArgv(request.withModel(request.agent.model ?: candidates.first()), binary)
    }

    override fun parser(request: AgentRequest): CliEventParser =
        AntigravityEventParser(request.agent.id, request.agent.effort, request.agent.budget.timeoutMillis)

    /**
     * Runs the turn, stepping down [candidates] while the service says it is full.
     *
     * Only a capacity refusal advances: a turn that failed on its own merits has an answer, and
     * re-running it on another model would spend the allowance to get the same answer again.
     */
    override fun run(request: AgentRequest): Flow<AgentEvent> = flow {
        val requested = request.agent.model
        val order = if (requested != null) listOf(requested) + candidates.filter { it != requested } else candidates
        for ((index, model) in order.withIndex()) {
            var outcome: AgentOutcome? = null
            val pending = mutableListOf<AgentEvent>()
            val last = index == order.lastIndex
            super.run(request.withModel(model)).collect { event ->
                if (event is AgentEvent.Finished) outcome = event.outcome
                // Held rather than forwarded: a turn that never started must not leave a half
                // transcript behind when the next model produces the real one.
                else if (last) emit(event) else pending += event
            }
            val finished = requireNotNull(outcome)
            if (last || !finished.outOfCapacity()) {
                if (!last) pending.forEach { emit(it) }
                emit(AgentEvent.Finished(request.agent.id, finished))
                return@flow
            }
            emit(AgentEvent.Activity(request.agent.id, AgentActivityItem("antigravity-capacity-$index",
                ActivityKind.Control, "$model is at capacity; trying ${order[index + 1]}",
                ActivityStatus.Recorded, output = finished.failure?.take(2000))))
        }
    }
}

/**
 * Antigravity's preference order for this seat, best first.
 *
 * Verified present on `agy models` and answering on this account, 15 September 2026. Antigravity
 * also serves `claude-*` and `gpt-oss-*` on the same allowance; those are reachable by naming one
 * explicitly, and are deliberately not the default, because a seat called ChatGPT + Gemini that
 * quietly runs Claude is a seat nobody can reason about when they are choosing a fallback.
 */
val defaultAntigravityModels: List<String> = listOf(
    "gemini-3.1-pro-high",
    "gemini-3.8-flash-high",
    "gemini-3.7-flash-high",
    "gemini-3.6-flash-high",
)

private fun AgentRequest.withModel(model: String) =
    if (agent.model == model) this else copy(agent = agent.copy(model = model))

/** Whether the service refused because a model is full, rather than because the turn was bad. */
private fun AgentOutcome.outOfCapacity(): Boolean {
    val text = failure.orEmpty()
    return text.contains("No capacity available", ignoreCase = true) ||
        text.contains("UNAVAILABLE (code 503)", ignoreCase = true) ||
        text.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
        text.contains("model is overloaded", ignoreCase = true)
}

/**
 * The guards that hold for every Antigravity turn, whichever transport carries it.
 *
 * Antigravity headless has no per-turn permission overrides: it uses the permissions the operator
 * configured. Accepting an allow/deny list here and dropping it would be worse than refusing it,
 * because the caller would believe a bound it never got.
 */
internal fun antigravityGuard(request: AgentRequest) {
    val policy = request.agent.tools
    require(request.agent.harnessArgs.isEmpty() && policy.allow.isEmpty() && policy.deny.isEmpty() && !policy.strictMcpConfig && !policy.isolateOperatorConfig) {
        "Antigravity headless uses native permissions; per-turn permission overrides are unsupported"
    }
    require(request.agent.effort == null || request.agent.effort.value in listOf("low", "medium", "high"))
}

/** The workspace preamble Reaktor puts in front of every Antigravity prompt. */
internal fun antigravityPrompt(request: AgentRequest): String {
    val policy = request.agent.tools
    val root = File(request.workingDirectory).canonicalPath
    return "Workspace: $root. Use absolute paths within this workspace.\n" +
        (if (policy.allowWrites) "Repository edits are authorized within this task. Native command and MCP permission rules still apply.\n" else "Inspect only. Do not modify repository files, run mutating commands, or use mutating MCP tools.\n") +
        policy.mcpConfig?.let { "Reaktor's workspace and graph MCP servers are registered with this harness and resolve this workspace. Use their tools, or the workspace CLI, for graph context and actual check receipts. The launch configuration at $it is Reaktor's own copy; this harness loads its servers from your Antigravity configuration, not from that file.\n" }.orEmpty() +
        request.prompt
}

/** Everything after the prompt. Shared so the batch and session transports cannot drift apart. */
private fun antigravityFlags(request: AgentRequest): List<String> = buildList {
    val policy = request.agent.tools
    val root = File(request.workingDirectory).canonicalPath
    add("--output-format"); add("stream-json")
    // Slash-command expansion and --mode are mutually exclusive in this harness: with
    // --disable-slash-commands the CLI warns that --mode has no effect, which would leave an
    // inspect-only turn free to write, because Antigravity auto-allows writes inside the
    // workspace. Verified against agy 1.2.2 on 14 September 2026: with the mode applied a write
    // is refused and reported in denied_actions. Expansion only reads a prompt that begins with a
    // slash, and this prompt always begins with Reaktor's own workspace line.
    add("--mode"); add(if (policy.allowWrites) "accept-edits" else "plan")
    add("--add-dir"); add(root)
    policy.additionalDirectories.forEach { add("--add-dir"); add(it) }
    add("--print-timeout"); add("${request.agent.budget.timeoutMillis}ms")
    request.agent.model?.let { add("--model"); add(it) }
    request.agent.effort?.let { add("--effort"); add(it.value) }
    request.resume?.takeIf { it.runtime == RuntimeKind.Gemini }?.let { add("--conversation"); add(it.sessionId) }
}

internal fun antigravityArgv(request: AgentRequest, binary: String = "agy"): List<String> = buildList {
    antigravityGuard(request)
    add(binary); add("--print"); add(antigravityPrompt(request))
    addAll(antigravityFlags(request))
}

/**
 * The same turn, on the transport that keeps the conversation open.
 *
 * `--print=` with an attached empty value is not a style choice: `--print` takes its value
 * positionally, so a bare `--print` followed by `--input-format` consumes the flag as the prompt
 * and the CLI says so. Verified against agy 1.2.2, 15 September 2026.
 */
internal fun antigravitySessionArgv(request: AgentRequest, binary: String = "agy"): List<String> = buildList {
    antigravityGuard(request)
    add(binary); add("--print=")
    add("--input-format"); add("stream-json")
    addAll(antigravityFlags(request))
}

class AntigravityEventParser(
    private val agent: AgentId,
    private val effort: NativeEffort? = null,
    /** The turn budget, so a blank answer can name the one thing that usually explains it. */
    private val budgetMillis: Long? = null,
) : CliEventParser {
    private var session: ProviderSession? = null
    private var terminal: JsonObject? = null
    private val names = mutableMapOf<String, String>()
    /** Tool name to last status, in order, so a turn that ends silently can still be described. */
    private val performed = linkedMapOf<String, String>()
    private var model: String? = null
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    override fun onLine(line: String): List<AgentEvent> {
        val root = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return emptyList()
        if (root.text("event") == "init") {
            session = root.text("conversation_id")?.let { ProviderSession(RuntimeKind.Gemini, it) }
            model = (root["init"] as? JsonObject)?.text("model")
            return listOf(AgentEvent.Started(agent, session), AgentEvent.Activity(agent, AgentActivityItem("antigravity-init", ActivityKind.Control,
                "Gemini via Antigravity", output = root["init"]?.toString()?.take(12000))))
        }
        if (root.text("event") == "result") { terminal = root["result"] as? JsonObject; return emptyList() }
        val step = (root["step_update"] as? JsonObject)?.takeIf { root.text("event") == "step_update" } ?: return emptyList()
        if (step.text("conversation_id") != session?.sessionId) return emptyList()
        val id = step.text("step_index") ?: return emptyList()
        val status = when (step.text("state")) { "DONE" -> ActivityStatus.Completed; "ERROR" -> ActivityStatus.Failed; "ACTIVE" -> ActivityStatus.Started; else -> ActivityStatus.Recorded }
        return when (step.text("step_type")) {
            "agent_response" -> step.text("text_delta")?.let { listOf(AgentEvent.Delta(agent, it)) }.orEmpty()
            "tool" -> {
                val info = step["tool_info"] as? JsonObject ?: JsonObject(emptyMap())
                val name = step.text("tool_name") ?: info.text("name") ?: names[id] ?: "Antigravity tool"
                names[id] = name
                performed[id] = "$name:${status.name.lowercase()}"
                listOf(AgentEvent.Activity(agent, AgentActivityItem(id, ActivityKind.Tool, name, status,
                    input = info["parameters"]?.toString()?.take(12000), output = (info["error"] ?: info["output"])?.toString()?.take(12000))))
            }
            "checkpoint" -> listOf(AgentEvent.Activity(agent, AgentActivityItem(id, ActivityKind.Checkpoint, "Antigravity checkpoint", status)))
            else -> (step["subagent_info"] as? JsonObject)?.let { info ->
                val children = (info["subagents"] as? JsonArray).orEmpty().mapNotNull { child ->
                    val data = child as? JsonObject ?: return@mapNotNull null
                    NativeAgentState(data.text("conversation_id") ?: return@mapNotNull null, session?.sessionId,
                        data.text("role") ?: data.text("type_name"), step.text("state") ?: "unknown")
                }
                listOf(AgentEvent.Activity(agent, AgentActivityItem(id, ActivityKind.Agent, "Gemini subagents", status, nativeAgents = children)))
            }.orEmpty()
        }
    }
    /**
     * Names the command Antigravity refused, which its own refusal does not.
     *
     * `denied_actions` reports only `{"action":"command"}` — no command line — so an operator sees
     * that *something* was blocked and has to guess what to allow. That guessing cost four cycles on
     * a single unlisted `git add`. The command is in Antigravity's own transcript, and the brain
     * directory is named by the conversation id, so this is an exact lookup rather than a search for
     * the most recent file. Best-effort: a missing or unreadable transcript just leaves the original
     * message alone.
     */
    private fun deniedDetail(denied: String): String {
        val base = "Antigravity could not obtain required permissions: $denied"
        val conversation = session?.sessionId ?: return base
        val transcript = File(System.getProperty("user.home"),
            ".gemini/antigravity-cli/brain/$conversation/.system_generated/logs/transcript.jsonl")
        val commands = runCatching {
            if (!transcript.isFile) return@runCatching emptyList()
            // Only the tail: these grow to megabytes and the refusal is always near the end.
            val tail = java.io.RandomAccessFile(transcript, "r").use { file ->
                val from = maxOf(0L, file.length() - 512_000)
                file.seek(from)
                ByteArray((file.length() - from).toInt()).also { file.readFully(it) }.decodeToString()
            }
            Regex(""""CommandLine":\s*"((?:[^"\\]|\\.){0,400})"""").findAll(tail)
                .map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }
                .toList().takeLast(3)
        }.getOrDefault(emptyList())
        if (commands.isEmpty()) return base
        return base + ". The last commands it tried were: " + commands.joinToString(" | ") { it.take(200) } +
            ". Allow the one it needs in ~/.gemini/antigravity-cli/settings.json under permissions.allow, " +
            "as command(<binary>) — rules match by prefix, so command(./gradlew) covers every invocation."
    }

    /**
     * Why a turn came back with nothing.
     *
     * `--print-timeout` elapsing looks exactly like success here: Antigravity reports `SUCCESS` with
     * an empty response, and whatever the turn had done is simply gone. Calling that "no completed
     * answer" sends the reader hunting for a crash, so the budget is named instead.
     */
    /**
     * What the turn did, when it declined to say.
     *
     * The parser has already watched every tool call go past; throwing that away and reporting
     * "no answer" tells the planner nothing and invites it to re-run work that in fact happened.
     */
    private fun whatItDid(): String? = performed.values.toList().takeIf { it.isNotEmpty() }
        ?.takeLast(40)?.joinToString(", ")?.let { "Tools it ran before stopping: $it" }

    private fun blankAnswer(status: String?): String = when {
        status == "SUCCESS" && budgetMillis != null ->
            "Antigravity returned no answer. A turn that reaches its ${budgetMillis}ms budget ends exactly like this, " +
                "with the work it had done discarded. Raise the submission's timeoutMillis if the task has to build or test."
        status == "SUCCESS" -> "Antigravity reported success but returned no answer"
        else -> "Antigravity produced no completed answer (${status ?: "no result"})"
    }

    override fun finish(exitCode: Int, stderr: String): AgentOutcome {
        val result = terminal
        val usage = result?.get("usage") as? JsonObject
        val denied = (result?.get("denied_actions") as? JsonArray).orEmpty()
        val reconstructed = if (result?.text("response").isNullOrBlank()) whatItDid() else null
        val text = result?.text("response").orEmpty().ifBlank { reconstructed.orEmpty() }
        // A reconstruction is evidence of activity, never evidence of success.
        val ok = exitCode == 0 && result?.text("status") == "SUCCESS" && reconstructed == null &&
            text.isNotBlank() && denied.isEmpty()
        val input = usage?.get("input_tokens")?.jsonPrimitive?.longOrNull
        val cached = usage?.get("cache_read_tokens")?.jsonPrimitive?.longOrNull
        return AgentOutcome(agent, text, ok,
            failure = if (ok) null else result?.text("error") ?: if (denied.isNotEmpty()) deniedDetail(denied.toString()) else stderr.takeLast(2000).ifBlank { blankAnswer(result?.text("status")) },
            session = session, usage = usage?.let { AgentUsage(inputTokens = input,
                outputTokens = it["output_tokens"]?.jsonPrimitive?.longOrNull,
                cachedInputTokens = cached?.takeIf { value -> input != null && value <= input },
                reasoningOutputTokens = it["thinking_tokens"]?.jsonPrimitive?.longOrNull,
                scope = UsageScope.ProviderSessionTotal) },
            effort = EffortRecord(requested = effort, resolved = effort), interrupted = result?.text("status") in listOf("CANCELED", "INTERRUPTED"),
            attributes = buildMap { put("executorHarness", "Antigravity"); put("entitlement", "GoogleAgent"); model?.let { put("model", it) }
                if (cached != null && input != null && cached > input) put("usageNotice", "Native cache count exceeds reported input; inclusive cache accounting is unknown") })
    }
}
