package dev.shibasis.reaktor.conductor

import kotlinx.serialization.Serializable

@Serializable
enum class Entitlement { ChatGptChat, Codex, ClaudeSubscription, GoogleAgent, GeminiCli, GeminiApp, OpenAiApi, AnthropicApi, GoogleApi }

@Serializable
data class EntitlementAdmission(val entitlement: Entitlement, val paused: Boolean = false, val reason: String = "",
    val revision: Long = 0, val updatedAt: Long? = null, val provenance: String = "Operator scheduling policy; remaining quota is unknown")

@Serializable
data class AgentEntitlement(val entitlement: Entitlement, val role: String, val transport: String,
    val provenance: String, val remainingPercent: Double? = null, val resetsAt: Long? = null)

fun RuntimeKind.entitlements(): List<AgentEntitlement> = when (this) {
    RuntimeKind.Codex -> listOf(AgentEntitlement(Entitlement.Codex, "Agent", "Native CLI", "Requested subscription; native authentication determines billing. Remaining quota unknown."))
    RuntimeKind.ClaudeCode -> listOf(AgentEntitlement(Entitlement.ClaudeSubscription, "Agent", "Native CLI", "Requested subscription; native authentication determines billing. Remaining quota unknown."))
    RuntimeKind.Gemini -> listOf(AgentEntitlement(Entitlement.GoogleAgent, "Executor", "Antigravity CLI · Gemini", "Saved Google account; native credit policies apply. No API fallback configured by Reaktor. Remaining quota unknown."))
    RuntimeKind.ChatGptGemini -> listOf(AgentEntitlement(Entitlement.ChatGptChat, "Planner and reviewer", "Human handoff", "Operator-supplied ChatGPT response; entitlement and usage cannot be verified.")) + RuntimeKind.Gemini.entitlements()
    RuntimeKind.Echo -> emptyList()
}
