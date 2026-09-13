package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.EffortSupport
import dev.shibasis.reaktor.conductor.NativeEffort
import dev.shibasis.reaktor.conductor.ProviderCapability
import dev.shibasis.reaktor.conductor.Qualification
import dev.shibasis.reaktor.conductor.ReasoningFidelity
import dev.shibasis.reaktor.conductor.RuntimeKind
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
        val lines = help.lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("--effort") }
        if (start < 0) return null
        // Continuation lines are indented further than the flag and start no new flag of their own.
        val joined = buildString {
            append(lines[start])
            for (index in start + 1 until lines.size) {
                val line = lines[index]
                if (line.isBlank() || line.trimStart().startsWith("-")) break
                append(' ').append(line.trim())
            }
        }
        val inside = Regex("""\(([^)]*)\)""").find(joined)?.groupValues?.get(1) ?: return emptyList()
        return inside.split(',')
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() && it.all { char -> char.isLetterOrDigit() || char == '-' } }
            .map(::NativeEffort)
    }

    /**
     * Claude Code, as driven through `-p --output-format stream-json`.
     *
     * Thinking blocks arrive verbatim on this transport, so the reasoning fidelity is Thinking and
     * not Summary; see the parser in [ClaudeCodeEventParser].
     */
    fun claude(version: String?, help: String?): ProviderCapability {
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
    fun codex(version: String?): ProviderCapability = ProviderCapability(
        runtime = RuntimeKind.Codex,
        executable = "codex",
        version = version,
        effort = EffortSupport(supported = null, source = "-c model_reasoning_effort"),
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
    )

    /**
     * Asks the installed binaries directly. A missing or unresponsive binary yields a capability
     * with no version and nothing enabled, which is the correct answer rather than an error.
     */
    fun probe(runtime: RuntimeKind, binary: String = defaultBinary(runtime)): ProviderCapability = when (runtime) {
        RuntimeKind.ClaudeCode -> claude(parseVersion(capture(binary, "--version").orEmpty()), capture(binary, "--help"))
        RuntimeKind.Codex -> codex(parseVersion(capture(binary, "--version").orEmpty()))
        RuntimeKind.Echo -> ProviderCapability(runtime = RuntimeKind.Echo, executable = null, version = "in-process")
    }

    private fun defaultBinary(runtime: RuntimeKind) = when (runtime) {
        RuntimeKind.ClaudeCode -> "claude"
        RuntimeKind.Codex -> "codex"
        RuntimeKind.Echo -> "echo"
    }

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
