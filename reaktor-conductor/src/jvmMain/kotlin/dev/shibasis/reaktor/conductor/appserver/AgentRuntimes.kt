package dev.shibasis.reaktor.conductor.appserver

import dev.shibasis.reaktor.conductor.AgentRuntime
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.cli.ClaudeCodeRuntime
import dev.shibasis.reaktor.conductor.cli.CodexRuntime
import dev.shibasis.reaktor.conductor.cli.AntigravityRuntime
import dev.shibasis.reaktor.tooling.SupervisedProcessExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The two transports, named rather than chosen by a hidden flag.
 *
 * They are not ranked. [interactive] can do things [batch] cannot — stream tokens, report the
 * effort a thread is really running at, surface approvals, steer and interrupt — but it holds a
 * process open per run, and the Compare/Council path is qualified on [batch]. So the caller picks,
 * and the capability record says what each one supports, instead of a default quietly changing
 * what a qualified path runs on.
 */
object AgentRuntimes {
    /** One turn per subprocess. What the shared workspace has always used. */
    fun batch(executor: SupervisedProcessExecutor): Map<RuntimeKind, AgentRuntime> = mapOf(
        RuntimeKind.Codex to CodexRuntime(executor),
        RuntimeKind.ClaudeCode to ClaudeCodeRuntime(executor),
        RuntimeKind.Gemini to AntigravityRuntime(executor),
    )

    /** Interactive turns with native continuation: Codex over its App Server, Claude over stream-json stdin. */
    fun interactive(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ): Map<RuntimeKind, AgentRuntime> = mapOf(
        RuntimeKind.Codex to CodexAppServerRuntime(scope = scope),
        RuntimeKind.ClaudeCode to ClaudeCodeSessionRuntime(scope = scope),
        RuntimeKind.Gemini to AntigravityRuntime(SupervisedProcessExecutor(scope = scope)),
    )
}
