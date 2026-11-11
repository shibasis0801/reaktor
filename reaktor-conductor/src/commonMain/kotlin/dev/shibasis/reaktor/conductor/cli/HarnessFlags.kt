package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.ToolPolicy

val ClaudeEditors = listOf("Write", "Edit", "NotebookEdit")

fun ToolPolicy.codexSandbox(): String = when {
    !allowWrites -> "read-only"
    sandboxed -> "workspace-write"
    else -> "danger-full-access"
}

fun ToolPolicy.claudePermissionMode(): String? = when {
    !allowWrites -> null
    sandboxed -> "acceptEdits"
    else -> "bypassPermissions"
}

fun ToolPolicy.antigravityMode(): String = if (allowWrites) "accept-edits" else "plan"
