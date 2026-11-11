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
        val unenforced = "Reaktor access reaches this harness as an instruction; it has no per-tool switch".takeIf { context.reaktor.denied.isNotEmpty() }
        return listOf(
            HarnessEffect(RuntimeKind.ClaudeCode, "Claude Code", buildList {
                tools.claudePermissionMode()?.let { add("--permission-mode $it") } ?: add("--disallowedTools ${ClaudeEditors.joinToString(" ")}")
                if (tools.deny.isNotEmpty()) add("--disallowedTools ${tools.deny.joinToString(" ")}")
                claude.budget?.maxCostUsd?.let { add("--max-budget-usd $it") }
            }, listOf(prompt)),
            HarnessEffect(RuntimeKind.Codex, "Codex", listOf("--sandbox ${tools.codexSandbox()}"), listOfNotNull(prompt, unenforced)),
            HarnessEffect(RuntimeKind.Gemini, "Antigravity", listOf("--mode ${tools.antigravityMode()}", "--dangerously-skip-permissions"),
                listOfNotNull(prompt, unenforced)),
        )
    }
}
