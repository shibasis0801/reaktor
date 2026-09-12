package dev.shibasis.reaktor.conductor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One stage of a [Protocol.Pipeline]: which agent runs, what it is told to do, what the result
 * counts as, and whether it may read what came before it.
 */
@Serializable
data class Stage(
    val agent: AgentId,
    val instruction: String,
    val kind: EventKind = EventKind.Proposal,
    val seesPeers: Boolean = true,
)

/**
 * How a turn is orchestrated. A protocol decides who runs, in what order, and who sees what.
 *
 * Roles live on agents and topology lives here, so an agent is not inherently a debate participant
 * or a pipeline stage. The same roster runs under any protocol, and a protocol is serializable
 * data, so an operator can write one, save it, and hand it to someone else.
 */
@Serializable
sealed interface Protocol {
    /** One agent answers, seeing the whole conversation. */
    @Serializable
    @SerialName("ask")
    data class Ask(val agent: AgentId) : Protocol

    /** Everyone answers the same question at once. Empty [agents] means every participant. */
    @Serializable
    @SerialName("all")
    data class All(
        val agents: List<AgentId> = emptyList(),
        val blind: Boolean = false,
    ) : Protocol

    /**
     * Blind independent proposals, then cross-critique, then synthesis.
     *
     * Round one runs in parallel with peers hidden, which is the point: agents that can see each
     * other's answers converge on the first one, and three models that agree with each other cost
     * three times as much as one.
     */
    @Serializable
    @SerialName("council")
    data class Council(
        val agents: List<AgentId> = emptyList(),
        val critique: Boolean = true,
        val synthesizer: AgentId? = null,
    ) : Protocol

    /**
     * An explicit relay of named stages. Each stage names its own agent, so one agent can appear
     * more than once and any ordered topology is expressible without adding a protocol kind.
     */
    @Serializable
    @SerialName("pipeline")
    data class Pipeline(val stages: List<Stage>) : Protocol

    /**
     * Let an agent design the team and the topology, then run what it designed.
     *
     * The planner is asked for a [TeamPlan]: any agents it wants that do not exist yet, and the
     * protocol to run with them. This covers the case where the operator does not want to choose a
     * shape at all. A plan may not nest another [Planned], so planning always terminates.
     */
    @Serializable
    @SerialName("planned")
    data class Planned(val planner: AgentId) : Protocol
}

/** What a planner returns: agents to add, and the protocol to run with them. */
@Serializable
data class TeamPlan(
    val protocol: Protocol,
    val agents: List<AgentSpec> = emptyList(),
    val rationale: String? = null,
)

/** The default synthesis instruction. Consensus that hides disagreement is worse than none. */
const val SYNTHESIS_INSTRUCTION: String =
    "Reconcile the proposals and critiques above into one answer for the original task. " +
        "Report it under four headings: Consensus, Best proposal, Remaining disagreement, and " +
        "Uncertain assumptions. Do not collapse a real disagreement into false consensus, and do " +
        "not invent agreement that the participants did not reach."

/** The default critique instruction used by [Protocol.Council]. */
const val CRITIQUE_INSTRUCTION: String =
    "Read the other participants' proposals above and critique them. Name concrete failure modes, " +
        "wrong assumptions, and anything they missed. Say plainly where another proposal is better " +
        "than your own."

/** Asks a planner for a [TeamPlan] as JSON, in terms of the roster it may build on. */
fun planningInstruction(available: List<AgentSpec>): String = buildString {
    appendLine("Design the team and the collaboration topology for the task stated above.")
    appendLine()
    appendLine("Reply with one JSON object and no other text:")
    appendLine("  {\"rationale\":\"one sentence\",\"agents\":[],\"protocol\":{ ... }}")
    appendLine()
    appendLine("protocol is one of:")
    appendLine("  {\"type\":\"ask\",\"agent\":\"<id>\"}")
    appendLine("  {\"type\":\"all\",\"agents\":[\"<id>\"],\"blind\":true}")
    appendLine("  {\"type\":\"council\",\"agents\":[\"<id>\"],\"critique\":true,\"synthesizer\":\"<id>\"}")
    appendLine("  {\"type\":\"pipeline\",\"stages\":[{\"agent\":\"<id>\",\"instruction\":\"...\",")
    appendLine("     \"kind\":\"Proposal\",\"seesPeers\":true}]}")
    appendLine()
    appendLine("stage kind is one of: Proposal, Critique, Revision, Synthesis, Note.")
    appendLine()
    appendLine("These agents already exist and may be used by id:")
    available.forEach { agent ->
        appendLine("  " + agent.id.value + " · runtime " + agent.runtime.name + " · " + agent.instructions.take(90))
    }
    appendLine()
    appendLine("To add an agent, put a full spec in \"agents\":")
    appendLine("  {\"id\":\"perf\",\"name\":\"Perf\",\"runtime\":\"Codex\",\"instructions\":\"...\"}")
    appendLine("runtime is one of: ClaudeCode, Codex. Keep the team as small as the task allows.")
}

/** Extracts the first balanced JSON object from prose, since planners rarely reply with only JSON. */
fun extractJsonObject(text: String): String? {
    val start = text.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until text.length) {
        val character = text[index]
        when {
            escaped -> escaped = false
            character == '\\' && inString -> escaped = true
            character == '"' -> inString = !inString
            inString -> Unit
            character == '{' -> depth++
            character == '}' -> {
                depth--
                if (depth == 0) return text.substring(start, index + 1)
            }
        }
    }
    return null
}
