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
            val seated = request?.resolvedSeat
            val name = AgentLabel.of(provider, seated?.seat?.effort ?: run.effort.resolved?.value, seated?.name, run.model.takeIf { provider == run.provider })
            return actor.copy(name = name, agent = "agent:$vendor:${run.threadId}",
                profile = request?.resolvedSeat?.seat?.profile, profileRevision = request?.resolvedSeat?.profileRevision)
        }
        if (nativeVendor(ref) == null) return actor.copy(agent = null)
        val named = if (profile != null && actor.profile == null) actor.copy(name = AgentLabel.of(profile), profile = profile.id, profileRevision = profile.revision) else actor
        return named.copy(agent = native(ref))
    }

    fun agent(actor: BoardActor): String? = actor.agent ?: actor.takeIf { it.kind == BoardActorKind.Agent }?.session?.let(::native)

    fun bare(actor: BoardActor): BoardActor {
        val session = actor.session ?: return actor
        val runtime = AgentLabel.runtime(session) ?: return actor
        val native = native(session) ?: return actor
        if (actor.kind != BoardActorKind.Agent || actor.agent != null && actor.agent != native) return actor
        return BoardActor(actor.kind, AgentLabel.of(runtime, null, null), session)
    }

    fun history(actor: BoardActor): BoardActor =
        if (actor.agent != null && actor.agent == actor.session?.let(::native)) actor.copy(agent = null) else actor

    private fun native(session: String): String? {
        val vendor = nativeVendor(session) ?: return null
        return session.takeIf { BoardRefs.pattern.matches(it) }?.let { "agent:$vendor:${it.substringAfter(':')}" }
    }

    private fun nativeVendor(session: String): String? = when (session.substringBefore(':')) {
        "claude" -> "claude"
        "codex" -> "codex"
        "agy" -> "agy"
        else -> null
    }

    private fun vendor(runtime: RuntimeKind): String = when (runtime) {
        RuntimeKind.ClaudeCode -> "claude"
        RuntimeKind.Codex -> "codex"
        RuntimeKind.Gemini -> "agy"
        else -> runtime.name.lowercase()
    }
}
