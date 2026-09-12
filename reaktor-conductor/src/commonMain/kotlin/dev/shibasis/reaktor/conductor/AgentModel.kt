package dev.shibasis.reaktor.conductor

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
value class AgentId(val value: String)

/**
 * Which harness executes an agent.
 *
 * An agent's identity is its [AgentSpec], not its runtime. Moving `architect` from Codex to Claude
 * Code changes one field and leaves the thread, its history, and its role intact.
 */
@Serializable
enum class RuntimeKind {
    ClaudeCode,
    Codex,

    /** Deterministic, no-subprocess runtime. Used by tests and by offline dry runs. */
    Echo,
}

/**
 * What a harness is allowed to touch.
 *
 * Writes are opt-in. A default agent runs read-only, which is what lets a council operate long
 * before any mutation gate exists: it can read the repository and argue about it, and it cannot
 * change anything.
 */
@Serializable
data class ToolPolicy(
    val allowWrites: Boolean = false,
    val allow: List<String> = emptyList(),
    val deny: List<String> = emptyList(),
    val additionalDirectories: List<String> = emptyList(),
    val mcpConfig: String? = null,
    val strictMcpConfig: Boolean = false,
    /**
     * Run the harness without the operator's own configuration.
     *
     * Off by default, because the point of driving a real harness is that it behaves the way the
     * operator's own harness behaves, with their model, profile, MCP servers, skills and hooks.
     * Turn it on for a reproducible run that depends on nothing outside the [AgentSpec].
     */
    val isolateOperatorConfig: Boolean = false,
)

/**
 * Explicit termination. A protocol without a budget is a protocol that can run forever, which is
 * the most common way a multi-agent system burns money without producing anything.
 */
@Serializable
data class AgentBudget(
    val timeoutMillis: Long = 10 * 60 * 1000L,
    val maxCostUsd: Double? = null,
)

@Serializable
enum class WorkspaceMode {
    /** Every agent reads one checkout. Correct for thinking, unsafe for writing. */
    SharedReadOnly,

    /** The caller supplies a private directory per agent: a git worktree, a copy, or a sandbox. */
    Isolated,
}

/**
 * A durable, user-defined participant. Everything that makes an agent itself lives here, so a
 * roster is data the user can write, version, and hand to someone else.
 */
@Serializable
data class AgentSpec(
    val id: AgentId,
    val name: String,
    val runtime: RuntimeKind,
    val instructions: String,
    val model: String? = null,
    val tools: ToolPolicy = ToolPolicy(),
    val budget: AgentBudget = AgentBudget(),
    val workspace: WorkspaceMode = WorkspaceMode.SharedReadOnly,
    /**
     * Extra flags appended verbatim to the harness command.
     *
     * The escape hatch that keeps this layer from capping what a harness can do: anything the
     * underlying CLI supports and this model has not modelled is still reachable, without waiting
     * for `reaktor-conductor` to grow a field for it.
     */
    val harnessArgs: List<String> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
)
