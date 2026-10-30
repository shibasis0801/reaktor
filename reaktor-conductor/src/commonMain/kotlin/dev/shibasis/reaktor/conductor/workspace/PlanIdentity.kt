package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind

object PlanIdentity {
    fun actor(actor: BoardActor, run: AgentRunRecord? = null, request: AgentSubmission? = null, profile: AgentProfile? = null): BoardActor {
        if (actor.kind != BoardActorKind.Agent) return actor.copy(agent = null)
        val ref = actor.session ?: return actor.copy(agent = null)
        if (run != null) {
            val participant = run.participants.values.firstOrNull { participant ->
                participant.session?.let { BoardRefs.session(it.runtime, it.sessionId) } == ref
            }
            val provider = participant?.provider ?: run.provider
            val vendor = vendor(provider)
            val profile = request?.resolvedSeat?.name?.takeIf(String::isNotBlank)
            val seat = request?.requestId?.takeIf { it.matches(Regex("plan:[a-f0-9]{16}:s[1-9][0-9]*")) }?.substringAfterLast(':')
            val name = profile?.let { if (seat == null) it else "$it · $seat" } ?: when (provider) {
                RuntimeKind.ClaudeCode -> "Claude"
                RuntimeKind.Codex -> "Codex"
                RuntimeKind.Gemini -> "Gemini"
                else -> provider.name
            }
            return actor.copy(name = name, agent = "agent:$vendor:${run.threadId}",
                profile = request?.resolvedSeat?.seat?.profile, profileRevision = request?.resolvedSeat?.profileRevision)
        }
        val vendor = when (ref.substringBefore(':')) {
            "claude" -> "claude"
            "codex" -> "codex"
            "agy" -> "agy"
            else -> return actor.copy(agent = null)
        }
        val named = if (profile != null && actor.profile == null) actor.copy(name = profile.name, profile = profile.id, profileRevision = profile.revision) else actor
        return named.copy(agent = ref.takeIf { BoardRefs.pattern.matches(it) }?.let { "agent:$vendor:${it.substringAfter(':')}" })
    }

    private fun vendor(runtime: RuntimeKind): String = when (runtime) {
        RuntimeKind.ClaudeCode -> "claude"
        RuntimeKind.Codex -> "codex"
        RuntimeKind.Gemini -> "agy"
        else -> runtime.name.lowercase()
    }
}
