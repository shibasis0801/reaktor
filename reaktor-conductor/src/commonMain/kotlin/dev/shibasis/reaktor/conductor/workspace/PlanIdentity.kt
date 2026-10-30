package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind

object PlanIdentity {
    fun actor(actor: BoardActor, run: AgentRunRecord? = null): BoardActor {
        if (actor.kind != BoardActorKind.Agent) return actor.copy(agent = null)
        val ref = actor.session ?: return actor.copy(agent = null)
        if (run != null) {
            val participant = run.participants.values.firstOrNull { participant ->
                participant.session?.let { BoardRefs.session(it.runtime, it.sessionId) } == ref
            }
            val vendor = vendor(participant?.provider ?: run.provider)
            return actor.copy(agent = "agent:$vendor:${run.threadId}")
        }
        val vendor = when (ref.substringBefore(':')) {
            "claude" -> "claude"
            "codex" -> "codex"
            "agy" -> "agy"
            else -> return actor.copy(agent = null)
        }
        return actor.copy(agent = ref.takeIf { BoardRefs.pattern.matches(it) }?.let { "agent:$vendor:${it.substringAfter(':')}" })
    }

    private fun vendor(runtime: RuntimeKind): String = when (runtime) {
        RuntimeKind.ClaudeCode -> "claude"
        RuntimeKind.Codex -> "codex"
        RuntimeKind.Gemini -> "agy"
        else -> runtime.name.lowercase()
    }
}
