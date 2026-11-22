package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.gateway.GatewayRuntime
import dev.shibasis.reaktor.conductor.gateway.LocalModelRuntime
import dev.shibasis.reaktor.conductor.AgentRuntime
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.cli.ClaudeCodeRuntime
import dev.shibasis.reaktor.conductor.cli.CodexRuntime
import dev.shibasis.reaktor.conductor.cli.AntigravityRuntime
import dev.shibasis.reaktor.tooling.SupervisedProcessExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object AgentRuntimes {
    val Apps = setOf(RuntimeKind.Codex, RuntimeKind.ClaudeCode, RuntimeKind.Gemini)

    fun batch(executor: SupervisedProcessExecutor): Map<RuntimeKind, AgentRuntime> = mapOf(
        RuntimeKind.Codex to CodexRuntime(executor),
        RuntimeKind.ClaudeCode to ClaudeCodeRuntime(executor),
        RuntimeKind.Gemini to AntigravityRuntime(executor),
        RuntimeKind.Gateway to GatewayRuntime(),
        RuntimeKind.Local to LocalModelRuntime(),
    )

    fun interactive(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ): Map<RuntimeKind, AgentRuntime> = mapOf(
        RuntimeKind.Codex to CodexAppServerRuntime(scope = scope),
        RuntimeKind.ClaudeCode to ClaudeAppRuntime(scope = scope),
        RuntimeKind.Gemini to AntigravityAppRuntime(scope = scope),
        RuntimeKind.Gateway to GatewayRuntime(),
        RuntimeKind.Local to LocalModelRuntime(),
    )
}
