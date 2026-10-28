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
        val option = Regex("""^\s*(?:-\w,\s*)?--[\w-]+(?:[ ,=<\[]|$)""")
        val wanted = Regex("""(?:^|[ ,])${Regex.escape(flag)}(?:[ ,=<\[]|$)""")
        val start = lines.indexOfFirst { option.containsMatchIn(it) && wanted.containsMatchIn(it.trim()) }
        if (start < 0) return null
        return lines.drop(start).take(1).plus(lines.drop(start + 1).takeWhile { !option.containsMatchIn(it) }).joinToString("\n")
    }

    fun parseFlagChoices(help: String, flag: String): List<String>? {
        val text = flagText(help, flag) ?: return null
        val bullets = Regex("""(?m)^\s+- ([\w-]+):""").findAll(text).map { it.groupValues[1] }.toList()
        if (bullets.isNotEmpty()) return bullets.distinct()
        val choices = Regex("""(?:choices|possible values):\s*([^\]\)]+)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
            ?: Regex("""\(([^)]*)\)""").find(text)?.groupValues?.get(1).orEmpty()
        return choices.split(',', '|').map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() && it.all { char -> char.isLetterOrDigit() || char == '-' } }.distinct()
    }

    fun parseCodexFeature(output: String?, feature: String): Qualification {
        val match = output?.lineSequence()?.map(String::trim)?.firstOrNull { it.substringBefore(' ') == feature }
            ?.let { Regex("""^\S+\s+(.+?)\s+(true|false)$""").matchEntire(it) }
        val advertised = match != null && match.groupValues[1] !in setOf("removed", "deprecated")
        return Qualification(advertised, advertised && match.groupValues[2] == "true")
    }

    private fun nativeControls(runtime: RuntimeKind, help: String?, features: String? = null): Map<String, Qualification> {
        fun flag(name: String) = help?.let { flagText(it, name) } != null
        fun command(name: String) = help?.lineSequence()?.any { Regex("""^\s*${Regex.escape(name)}(?:\s|$)""").containsMatchIn(it) } == true
        return mapOf(
            "steer" to Qualification(advertised = if (runtime == RuntimeKind.Codex) command("app-server")
                else flagText(help.orEmpty(), "--input-format")?.contains("stream-json") == true),
            "queue" to Qualification(advertised = command("queue")),
            "subagents" to if (runtime == RuntimeKind.Codex) parseCodexFeature(features, "multi_agent")
                else Qualification(advertised = runtime == RuntimeKind.ClaudeCode && flag("--agents")),
            "worktrees" to if (runtime == RuntimeKind.Codex && features != null) parseCodexFeature(features, "worktrees")
                else Qualification(advertised = flag("--worktree")),
        )
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
                add("Hangar passes bypassPermissions for writing turns; inspection disables built-in editors. These permissions are not an OS sandbox.")
            },
            models = help?.let(::parseClaudeModels).orEmpty(),
            defaultModel = defaultModel,
            permissionModes = help?.let { parseFlagChoices(it, "--permission-mode") }.orEmpty(),
            controls = nativeControls(RuntimeKind.ClaudeCode, help),
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
    fun codex(version: String?, models: List<ModelChoice> = emptyList(), defaultModel: String? = null, defaultEffort: NativeEffort? = null, help: String? = null, features: String? = null): ProviderCapability = ProviderCapability(
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
            "Hangar selects read-only, workspace-write or danger-full-access from the task tool policy. Approval responses require the interactive adapter.",
        ),
        models = models,
        defaultModel = defaultModel,
        permissionModes = help?.let { parseFlagChoices(it, "--ask-for-approval") }.orEmpty(),
        sandboxModes = help?.let { parseFlagChoices(it, "--sandbox") }.orEmpty(),
        controls = nativeControls(RuntimeKind.Codex, help, features),
    )

    fun antigravity(runtime: RuntimeKind, version: String?, help: String?): ProviderCapability {
        val efforts = help?.let { parseFlagChoices(it, "--effort") }
        return ProviderCapability(runtime, "agy", version,
            effort = efforts?.let { EffortSupport(it.map(::NativeEffort).ifEmpty { null }, source = "agy --help") },
            effortControl = Qualification(efforts != null, version != null && efforts != null, true),
            permissionModes = help?.let { parseFlagChoices(it, "--mode") }.orEmpty(),
            sandboxModes = if (help?.let { flagText(it, "--sandbox") } != null) listOf("--sandbox") else emptyList(),
            controls = nativeControls(runtime, help),
            notes = listOf(
                "Hangar uses accept-edits for writing and plan for inspection, with dangerously-skip-permissions. Plan mode is not an OS sandbox; the adapter does not enable --sandbox.",
                "Models shown are Hangar's configured ladder. The live agy models catalog requires a signed-in account and is not qualified by this probe.",
                if (runtime == RuntimeKind.ChatGptGemini) "One composite seat: ChatGPT plans and reviews each cycle around Gemini execution. The Codex planner spends the ChatGPT pool."
                    else "Antigravity uses the saved Google account and native credit policies; remaining capacity is unknown."),
            models = if (runtime == RuntimeKind.Gemini) antigravityModelNames.map { (id, name) ->
                val level = NativeEffort(id.substringAfterLast('-'))
                ModelChoice(id, name, EffortSupport(listOf(level), default = level, source = "configured agy model variant"))
            } else emptyList(),
            defaultModel = if (runtime == RuntimeKind.Gemini) defaultAntigravityModels.first() else null)
    }

    fun probe(
        runtime: RuntimeKind,
        binary: String = defaultBinary(runtime),
        home: File = File(System.getProperty("user.home")),
        environment: Map<String, String> = System.getenv(),
    ): ProviderCapability {
        val version = if (runtime in listOf(RuntimeKind.ClaudeCode, RuntimeKind.Codex, RuntimeKind.Gemini, RuntimeKind.ChatGptGemini))
            parseVersion(capture(binary, "--version", environment = environment)) else null
        val help = version?.let { capture(binary, "--help", environment = environment) }
        val capability = when (runtime) {
            RuntimeKind.ClaudeCode -> claude(version, help,
                small(configHome(environment["CLAUDE_CONFIG_DIR"], home, ".claude").resolve("settings.json"))?.let(::parseSettingsModel))
            RuntimeKind.Codex -> configHome(environment["CODEX_HOME"], home, ".codex").let { codexHome ->
                val config = small(codexHome.resolve("config.toml"))
                codex(version, small(codexHome.resolve("models_cache.json"))?.let(::parseCodexModels).orEmpty(),
                    config?.let { parseTomlString(it, "model") }, config?.let { parseTomlString(it, "model_reasoning_effort") }?.effortOrNull(),
                    help, version?.let { capture(binary, "features", "list", environment = environment) })
            }
            RuntimeKind.Gemini, RuntimeKind.ChatGptGemini -> antigravity(runtime, version, help)
            RuntimeKind.Gateway -> ProviderCapability(RuntimeKind.Gateway, executable = null, version = "Cloudflare AI REST",
                effort = EffortSupport(listOf("low", "medium", "high").map(::NativeEffort), source = "reasoning_effort on models that list it"),
                effortControl = Qualification(true, true, true),
                reasoning = ReasoningFidelity.Thinking,
                notes = listOf("An open model on the workspace's Cloudflare account, reached through the REST inference API and an AI Gateway when one is named. It reads the workspace through confined read, search and list tools and never edits files."))
            RuntimeKind.Echo -> ProviderCapability(runtime = RuntimeKind.Echo, executable = null, version = "in-process")
        }
        return capability.copy(probedAt = System.currentTimeMillis(),
            executable = if (runtime in listOf(RuntimeKind.Gateway, RuntimeKind.Echo)) capability.executable else binary)
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

    private fun capture(binary: String, vararg arguments: String, environment: Map<String, String> = System.getenv()): String? = runCatching {
        val output = File.createTempFile("reaktor-capability-", ".log")
        try {
            val process = ProcessBuilder(listOf(binary) + arguments)
                .apply { environment().putAll(environment) }
                .redirectErrorStream(true)
                .redirectOutput(output)
                .start()
            process.outputStream.close()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            output.takeIf { process.exitValue() == 0 }?.let(::small)
        } finally {
            output.delete()
        }
    }.getOrNull()
}
