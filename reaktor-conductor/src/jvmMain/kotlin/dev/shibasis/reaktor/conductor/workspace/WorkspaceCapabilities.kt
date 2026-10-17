package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*

internal fun workspaceCapabilities(runtimes: Map<RuntimeKind, AgentRuntime>, discover: (RuntimeKind) -> ProviderCapability): List<ProviderCapability> =
    runtimes.map { (kind, runtime) ->
        // Session support is a property of the runtime that is actually wired here, not of the
        // installed CLI, so it is read from the runtime rather than probed.
        val discovered = discover(kind)
        val session = (runtime as? InteractiveAgentRuntime)?.interactive ?: Qualification.unavailable
        val testedVersion = when (kind) { RuntimeKind.Codex -> "0.154.0"; RuntimeKind.ClaudeCode -> "2.1.270"; else -> null }
        val sameVersion = testedVersion != null && discovered.version?.contains(testedVersion) == true
        val controls = if (runtime !is InteractiveAgentRuntime || !session.implemented) emptyMap() else {
            val implemented = if (kind == RuntimeKind.Codex) listOf("start", "steer", "interrupt", "commandApproval", "fileApproval", "questions", "permissions", "elicitation")
                else if (kind in listOf(RuntimeKind.Gemini, RuntimeKind.ChatGptGemini)) listOf("start", "interrupt", "permissions")
                else listOf("start", "steer", "interrupt", "questions", "permissions")
            implemented.associateWith { name ->
                val qualification = when {
                    name == "start" -> session.qualifiedBy
                    kind == RuntimeKind.Codex && name == "commandApproval" -> "NativeControlsLiveTest: command denial and stale replay"
                    kind == RuntimeKind.ClaudeCode && name in listOf("questions", "permissions") -> "NativeControlsLiveTest: question answer and permission denial"
                    else -> null
                }
                Qualification(true, true, true, qualification?.takeIf { sameVersion })
            }
        }
        discovered.copy(session = session.copy(qualifiedBy = session.qualifiedBy?.takeIf { sameVersion }), controls = controls)
    }
