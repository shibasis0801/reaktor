package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlinx.serialization.json.*

/**
 * The ChatGPT half of the composite seat, driven as an agent rather than by a person.
 *
 * The planner runs read-only by construction: it decides and reviews, Gemini is the only half that
 * touches the repository. That split is what makes an unattended loop safe to leave running — the
 * side that can be wrong about the codebase cannot also change it.
 *
 * Bookkeeping fields in the model's JSON are ignored and replaced with the handoff's own. A planner
 * that miscopies a revision is stating an opinion about content, not about identity, and failing
 * the cycle over a transcription slip would end the loop for the one reason that carries no
 * information.
 */
internal class PlannerUnavailable(message: String) : Exception(message)

internal class HybridPlanner(private val runtime: AgentRuntime) {

    /** Runs one planning or reviewing turn. Returns the reply and the thread to resume next time. */
    suspend fun ask(
        handoff: HybridHandoff,
        request: AgentRequest,
        onEvent: suspend (AgentEvent) -> Unit = {},
    ): Pair<HybridReply, ProviderSession?> {
        val outcome = runtime.await(plannerRequest(handoff, request), onEvent)
        // Quota, a dead binary, a refusal, an answer with no decision in it: all of them mean this
        // half cannot answer right now, and none of them mean the task is wrong. Losing the run over
        // any of them would throw away the cycles already paid for, so they degrade to the relay.
        if (!outcome.ok && outcome.text.isBlank()) {
            throw PlannerUnavailable("ChatGPT planning turn failed: ${outcome.failure ?: "no answer"}")
        }
        val parsed = extractReply(outcome.text)
            ?: throw PlannerUnavailable("ChatGPT did not return a decision. It answered:\n${outcome.text.take(2000)}")
        return parsed.copy(
            handoffId = handoff.id,
            revision = handoff.revision,
            phase = handoff.phase,
        ) to outcome.session
    }

    private fun plannerRequest(handoff: HybridHandoff, request: AgentRequest) = request.copy(
        agent = request.agent.copy(
            runtime = runtime.kind,
            // The planner reads and decides. Only Gemini writes.
            tools = request.agent.tools.copy(allowWrites = false),
        ),
        prompt = handoff.packet(),
        resume = handoff.plannerSession?.takeIf { it.runtime == runtime.kind },
        persistSession = true,
    )
}

/**
 * Pulls the reply out of whatever the planner wrapped it in.
 *
 * Fields are read one at a time instead of decoded as a whole, because a strict decode fails on the
 * parts that do not matter. A planner that writes `"Finish"` for `"finish"`, or omits the revision
 * it was told to echo, has still answered the question; only `text` and `next` carry its decision,
 * and [HybridPlanner.ask] replaces the identity fields with the handoff's own regardless.
 */
internal fun extractReply(text: String): HybridReply? {
    val candidates = buildList {
        Regex("```(?:json)?\\s*(\\{[\\s\\S]*?})\\s*```").findAll(text).forEach { add(it.groupValues[1]) }
        addAll(balancedObjects(text))
    }
    return candidates.reversed().firstNotNullOfOrNull { candidate ->
        runCatching { Json.parseToJsonElement(candidate).jsonObject }.getOrNull()?.let(::readReply)
    }
}

private fun readReply(fields: JsonObject): HybridReply? {
    fun string(key: String) = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val body = string("text")?.takeIf { it.isNotBlank() } ?: return null
    return HybridReply(
        handoffId = string("handoffId").orEmpty(),
        revision = (fields["revision"] as? JsonPrimitive)?.longOrNull ?: 0,
        phase = HybridPhase.entries.firstOrNull { it.name.equals(string("phase"), ignoreCase = true) } ?: HybridPhase.Planning,
        text = body,
        acceptanceCriteria = (fields["acceptanceCriteria"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content },
        next = HybridNext.entries.firstOrNull { it.name.equals(string("next"), ignoreCase = true) },
    )
}

/** Every balanced `{...}` run in [text], ignoring braces inside strings. */
private fun balancedObjects(text: String): List<String> = buildList {
    var start = -1
    var depth = 0
    var inString = false
    var escaped = false
    text.forEachIndexed { index, character ->
        when {
            escaped -> escaped = false
            inString && character == '\\' -> escaped = true
            character == '"' -> inString = !inString
            inString -> Unit
            character == '{' -> { if (depth == 0) start = index; depth++ }
            character == '}' -> {
                if (depth > 0) depth--
                if (depth == 0 && start >= 0) { add(text.substring(start, index + 1)); start = -1 }
            }
        }
    }
}
