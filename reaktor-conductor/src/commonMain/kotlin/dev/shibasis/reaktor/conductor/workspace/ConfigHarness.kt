package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.cli.ClaudeEditors
import dev.shibasis.reaktor.conductor.cli.antigravityMode
import dev.shibasis.reaktor.conductor.cli.claudePermissionMode
import dev.shibasis.reaktor.conductor.cli.codexSandbox

data class HarnessEffect(val runtime: RuntimeKind, val harness: String, val flags: List<String>, val notes: List<String>)

object ConfigHarness {
    fun effects(context: SystemContext): List<HarnessEffect> {
        val claude = context.composeProfile("preview", context.name, RuntimeKind.ClaudeCode, null, null)
        val tools = claude.tools
        val words = claude.instructions.split(Regex("\\s+")).count { it.isNotBlank() }
        val prompt = "Instructions: $words words appended to the harness prompt"
        val unenforced = listOfNotNull("Reaktor access".takeIf { context.reaktor.denied.isNotEmpty() }, "blocked tools".takeIf { context.tools.deny.isNotEmpty() })
            .takeIf { it.isNotEmpty() }?.let { "${it.joinToString(" and ").replaceFirstChar(Char::uppercase)} reach this harness as an instruction; it has no per-tool switch" }
        val folders = tools.additionalDirectories.map { "--add-dir $it" }
        val tokens = context.budget?.maxTokens?.let { "Stops once the harness reports $it tokens" }
        return listOf(
            HarnessEffect(RuntimeKind.ClaudeCode, "Claude Code", buildList {
                tools.claudePermissionMode()?.let { add("--permission-mode $it") } ?: add("--disallowedTools ${ClaudeEditors.joinToString(" ")}")
                if (tools.deny.isNotEmpty()) add("--disallowedTools ${tools.deny.joinToString(" ")}")
                claude.budget?.maxCostUsd?.let { add("--max-budget-usd $it") }
                addAll(folders)
            }, listOfNotNull(prompt, tokens)),
            HarnessEffect(RuntimeKind.Codex, "Codex", listOf("--sandbox ${tools.codexSandbox()}") + folders, listOfNotNull(prompt, unenforced, tokens)),
            HarnessEffect(RuntimeKind.Gemini, "Antigravity", listOf("--mode ${tools.antigravityMode()}", "--dangerously-skip-permissions") + folders,
                listOfNotNull(prompt, unenforced, tokens)),
        )
    }
}
