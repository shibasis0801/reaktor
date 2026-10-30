package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.workspace.BoardRefs
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

object AgentContextHook {
    suspend fun read(harness: AgentPluginBundle.Harness, event: JsonObject, principal: String?,
        inbox: suspend (String) -> String?, recall: suspend (String) -> String?): JsonObject {
        val prefix = when (harness) {
            AgentPluginBundle.Harness.Claude -> BoardRefs.Claude
            AgentPluginBundle.Harness.Codex -> BoardRefs.Codex
            AgentPluginBundle.Harness.Antigravity -> return buildJsonObject {}
        }
        val session = event.string("session_id") ?: return buildJsonObject {}
        val reference = prefix + session
        val name = event.string("hook_event_name") ?: return buildJsonObject {}
        if (!BoardRefs.pattern.matches(reference) || principal == null || !BoardRefs.pattern.matches(principal) ||
            principal != reference && !principal.startsWith(BoardRefs.Hangar) ||
            event.string("thread_id")?.let { it != session } == true ||
            name !in setOf("SessionStart", "UserPromptSubmit", "PostToolUse")) return buildJsonObject {}
        val remembered = if (name == "SessionStart") attempt { recall(principal) } else null
        val delivered = attempt { inbox(principal) }
        val context = listOfNotNull(delivered?.take(8000), remembered?.take(8000)).filter(String::isNotBlank).joinToString("\n\n")
        return buildJsonObject {
            if (context.isNotEmpty()) putJsonObject("hookSpecificOutput") {
                put("hookEventName", name)
                put("additionalContext", context)
            }
        }
    }

    private suspend fun attempt(block: suspend () -> String?): String? = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
