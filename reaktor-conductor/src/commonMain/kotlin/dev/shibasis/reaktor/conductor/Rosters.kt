package dev.shibasis.reaktor.conductor

/**
 * A starting roster. Agents are data, so this is a default a user edits or replaces, not a fixed
 * set of roles baked into the framework.
 *
 * The split is deliberate: the two proposing agents run on different harnesses, because a council
 * whose members share a model mostly agrees with itself.
 */
object Rosters {
    val architect = AgentSpec(
        id = AgentId("architect"),
        name = "Architect",
        runtime = RuntimeKind.Codex,
        instructions = "Find the cleanest implementable design. Prefer few primitives that " +
            "compose over many features. State the tradeoff you are making and what it costs.",
    )

    val skeptic = AgentSpec(
        id = AgentId("skeptic"),
        name = "Skeptic",
        runtime = RuntimeKind.ClaudeCode,
        instructions = "Attack the design. Name concrete failure modes, wrong assumptions, and " +
            "edge cases. Prefer one decisive objection over five vague ones. If it is sound, say so.",
    )

    val implementer = AgentSpec(
        id = AgentId("implementer"),
        name = "Implementer",
        runtime = RuntimeKind.Codex,
        instructions = "Make it work. Address each objection concretely, or say why it does not " +
            "apply. Keep the change as small as the problem allows.",
    )

    val reviewer = AgentSpec(
        id = AgentId("reviewer"),
        name = "Reviewer",
        runtime = RuntimeKind.ClaudeCode,
        instructions = "Review for elegance, concision, and performance. Point at the specific " +
            "line or structure you would change and say what you would replace it with.",
    )

    val default: List<AgentSpec> = listOf(architect, skeptic, implementer, reviewer)

    /** Design, attack the design, fix, then review the result. */
    val designPipeline: Protocol.Pipeline = Protocol.Pipeline(
        listOf(
            Stage(
                agent = architect.id,
                instruction = "Design an approach for the task stated above.",
                kind = EventKind.Proposal,
                seesPeers = false,
            ),
            Stage(
                agent = skeptic.id,
                instruction = "Attack the design above.",
                kind = EventKind.Critique,
            ),
            Stage(
                agent = implementer.id,
                instruction = "Revise the design to answer the critique above.",
                kind = EventKind.Revision,
            ),
            Stage(
                agent = reviewer.id,
                instruction = "Review the revision above for elegance, concision, and performance.",
                kind = EventKind.Critique,
            ),
        ),
    )
}
