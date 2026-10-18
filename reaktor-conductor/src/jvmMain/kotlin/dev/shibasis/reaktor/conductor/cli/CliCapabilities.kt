package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.EffortSupport
import dev.shibasis.reaktor.conductor.ModelChoice
import dev.shibasis.reaktor.conductor.NativeEffort
import dev.shibasis.reaktor.conductor.ProviderCapability
import dev.shibasis.reaktor.conductor.Qualification
import dev.shibasis.reaktor.conductor.ReasoningFidelity
import dev.shibasis.reaktor.conductor.RuntimeKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What the installed harnesses say about themselves.
 *
 * Parsing is kept free of process handling for the same reason [CliEventParser] is: these are other
 * people's CLIs, their help text changes between releases, and the only way to know a parser still
 * matches is to run it against captured output without spawning anything.
 */
object CliCapabilities {
    /** `claude --version` prints `2.1.270 (Claude Code)`; `codex --version` prints `codex-cli 0.154.0`. */
    fun parseVersion(output: String?): String? =
        output?.let { Regex("""\d+\.\d+\.\d+""").find(it)?.value }

    /**
     * Reads the effort levels out of `claude --help`.
     *
     * The flag and its levels are wrapped across lines by the help formatter, so the description is
     * rejoined before matching:
     *
     * ```
     *   --effort <level>     Effort level for the current session
     *                        (low, medium, high, xhigh, max)
     * ```
     *
     * Returns null when the flag is absent, which is how an older CLI reports that it has no effort
     * control at all — distinct from advertising an empty set.
     */
    fun parseClaudeEfforts(help: String): List<NativeEffort>? {
        val joined = flagText(help, "--effort") ?: return null
        val inside = Regex("""\(([^)]*)\)""").find(joined)?.groupValues?.get(1) ?: return emptyList()
        return inside.split(',')
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() && it.all { char -> char.isLetterOrDigit() || char == '-' } }
            .map(::NativeEffort)
    }

    fun parseClaudeModels(help: String): List<ModelChoice> =
        Regex("""'([a-z]+)'""").findAll(flagText(help, "--model").orEmpty())
            .map { it.groupValues[1] }.distinct()
            .map { alias -> ModelChoice(alias, alias.replaceFirstChar(Char::uppercase)) }
            .toList()

    fun parseCodexModels(cache: String): List<ModelChoice> = runCatching {
        (Json.parseToJsonElement(cache) as? JsonObject)?.get("models").let { it as? JsonArray }.orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.string("visibility") == "list" }
            .sortedBy { it.long("priority") ?: Long.MAX_VALUE }
            .mapNotNull { model ->
                val slug = model.string("slug")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val levels = (model["supported_reasoning_levels"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonObject)?.string("effort")?.effortOrNull() }
                ModelChoice(slug, model.string("display_name")?.takeIf(String::isNotBlank) ?: slug,
                    EffortSupport(levels.ifEmpty { null }, model.string("default_reasoning_level")?.effortOrNull(), "models_cache.json"))
            }
    }.getOrDefault(emptyList())

    fun parseTomlString(toml: String, key: String): String? = toml.lineSequence()
        .takeWhile { !it.trimStart().startsWith("[") }
        .firstNotNullOfOrNull { Regex("""^\s*${Regex.escape(key)}\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }

    fun parseSettingsModel(settings: String): String? = runCatching {
        (Json.parseToJsonElement(settings) as? JsonObject)?.string("model")?.takeIf(String::isNotBlank)
    }.getOrNull()

    private fun String.effortOrNull(): NativeEffort? = takeIf { it.isNotBlank() && it.length <= 32 }?.let(::NativeEffort)

    private fun flagText(help: String, flag: String): String? {
        val lines = help.lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith(flag) }
        if (start < 0) return null
        // Continuation lines are indented further than the flag and start no new flag of their own.
        return buildString {
            append(lines[start])
            for (index in start + 1 until lines.size) {
                val line = lines[index]
                if (line.isBlank() || line.trimStart().startsWith("-")) break
                append(' ').append(line.trim())
            }
        }
    }

    /**
     * Claude Code, as driven through `-p --output-format stream-json`.
     *
     * Thinking blocks arrive verbatim on this transport, so the reasoning fidelity is Thinking and
     * not Summary; see the parser in [ClaudeCodeEventParser].
     */
    fun claude(version: String?, help: String?, defaultModel: String? = null): ProviderCapability {
        val efforts = help?.let(::parseClaudeEfforts)
        val advertised = efforts != null
        return ProviderCapability(
            runtime = RuntimeKind.ClaudeCode,
            executable = "claude",
            version = version,
            effort = efforts?.let {
                EffortSupport(supported = it.ifEmpty { null }, source = "claude --help")
            },
            effortControl = Qualification(
                advertised = advertised,
                configured = advertised,
                implemented = true,
                qualifiedBy = if (advertised) "ClaudeCodeRuntime.argv passes --effort" else null,
            ),
            reasoning = ReasoningFidelity.Thinking,
            reasoningControl = Qualification(
                advertised = true,
                configured = true,
                implemented = true,
                qualifiedBy = "HarnessParserTest thinking block",
            ),
            notes = buildList {
                if (!advertised) add("This CLI advertises no --effort flag; effort selection is unavailable.")
            },
            models = help?.let(::parseClaudeModels).orEmpty(),
            defaultModel = defaultModel,
        )
    }

    /**
     * Codex, as driven through `exec --json`.
     *
     * Two honest gaps, both verified against 0.154.0. `exec` has no effort flag, so effort travels
     * as the `-c model_reasoning_effort` config override and the supported set cannot be enumerated
     * without a terminal — hence a null [EffortSupport.supported] rather than a guessed list. And
     * this transport emits no reasoning items of any kind, so its fidelity is Unavailable; the
     * summaries live on the App Server transport instead.
     */
    fun codex(version: String?, models: List<ModelChoice> = emptyList(), defaultModel: String? = null, defaultEffort: NativeEffort? = null): ProviderCapability = ProviderCapability(
        runtime = RuntimeKind.Codex,
        executable = "codex",
        version = version,
        effort = EffortSupport(supported = null, default = defaultEffort, source = "-c model_reasoning_effort"),
        effortControl = Qualification(
            advertised = version != null,
            configured = version != null,
            implemented = true,
            qualifiedBy = if (version != null) "CodexRuntime.argv passes -c model_reasoning_effort" else null,
        ),
        reasoning = ReasoningFidelity.Unavailable,
        reasoningControl = Qualification.unavailable,
        notes = listOf(
            "exec --json reports no effective effort, so the granted value stays unverified.",
            "exec --json carries no reasoning items; summaries need the App Server transport.",
        ),
        models = models,
        defaultModel = defaultModel,
    )

    /**
     * Asks the installed binaries directly. A missing or unresponsive binary yields a capability
     * with no version and nothing enabled, which is the correct answer rather than an error.
     */
    fun probe(
        runtime: RuntimeKind,
        binary: String = defaultBinary(runtime),
        home: File = File(System.getProperty("user.home")),
        environment: Map<String, String> = System.getenv(),
    ): ProviderCapability = when (runtime) {
        RuntimeKind.ClaudeCode -> claude(parseVersion(capture(binary, "--version").orEmpty()), capture(binary, "--help"),
            small(configHome(environment["CLAUDE_CONFIG_DIR"], home, ".claude").resolve("settings.json"))?.let(::parseSettingsModel))
        RuntimeKind.Codex -> configHome(environment["CODEX_HOME"], home, ".codex").let { codexHome ->
            val config = small(codexHome.resolve("config.toml"))
            codex(parseVersion(capture(binary, "--version").orEmpty()), small(codexHome.resolve("models_cache.json"))?.let(::parseCodexModels).orEmpty(),
                config?.let { parseTomlString(it, "model") }, config?.let { parseTomlString(it, "model_reasoning_effort") }?.effortOrNull())
        }
        RuntimeKind.Gemini, RuntimeKind.ChatGptGemini -> ProviderCapability(runtime, "agy", parseVersion(capture(binary, "--version")),
            effort = EffortSupport(listOf("low", "medium", "high").map(::NativeEffort), source = "agy --help"),
            effortControl = Qualification(true, true, true),
            notes = listOf("Gemini through Antigravity headless. Native permissions apply; missing permissions fail the turn. Plan mode requests inspection; it is not an OS sandbox. No live steering or permission responses on this transport.",
                if (runtime == RuntimeKind.ChatGptGemini) "One composite seat: ChatGPT plans and reviews each cycle around Gemini execution, looping until it says the work is done or the cycle cap stops it. The programmatic planner is Codex on its ChatGPT account and spends that pool; pausing it falls back to operator-transferred planning rather than to another provider." else "Antigravity uses the saved Google account and native credit policies; remaining capacity is unknown."),
            models = if (runtime == RuntimeKind.Gemini) antigravityModelNames.map { (id, name) -> ModelChoice(id, name) } else emptyList(),
            defaultModel = if (runtime == RuntimeKind.Gemini) defaultAntigravityModels.first() else null)
        RuntimeKind.Gateway -> ProviderCapability(RuntimeKind.Gateway, executable = null, version = "Cloudflare AI REST",
            effort = EffortSupport(listOf("low", "medium", "high").map(::NativeEffort), source = "reasoning_effort on models that list it"),
            effortControl = Qualification(true, true, true),
            reasoning = ReasoningFidelity.Thinking,
            notes = listOf("An open model on the workspace's Cloudflare account, reached through the REST inference API and an AI Gateway when one is named. It reads the workspace through confined read, search and list tools and never edits files."))
        RuntimeKind.Echo -> ProviderCapability(runtime = RuntimeKind.Echo, executable = null, version = "in-process")
    }

    private fun defaultBinary(runtime: RuntimeKind) = when (runtime) {
        RuntimeKind.ClaudeCode -> "claude"
        RuntimeKind.Codex -> "codex"
        RuntimeKind.Gemini, RuntimeKind.ChatGptGemini -> "agy"
        RuntimeKind.Gateway, RuntimeKind.Echo -> "echo"
    }

    private fun configHome(override: String?, home: File, name: String): File = override?.takeIf(String::isNotBlank)?.let(::File) ?: home.resolve(name)

    private fun small(file: File): String? = file.takeIf { it.isFile && it.length() <= ConfigLimit }?.let { runCatching { it.readText() }.getOrNull() }

    private const val ConfigLimit = 4L * 1024 * 1024

    private fun capture(binary: String, flag: String): String? = runCatching {
        val process = ProcessBuilder(binary, flag)
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val text = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        text.takeIf { process.exitValue() == 0 }
    }.getOrNull()
}
