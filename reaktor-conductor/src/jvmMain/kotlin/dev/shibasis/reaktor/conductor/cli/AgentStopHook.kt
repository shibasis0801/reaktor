package dev.shibasis.reaktor.conductor.cli

import dev.shibasis.reaktor.conductor.workspace.BoardRefs
import dev.shibasis.reaktor.conductor.workspace.KeepWorkingResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile

object AgentStopHook {
    suspend fun codex(event: JsonObject, principal: String?, home: File, invoke: suspend (String) -> KeepWorkingResult): JsonObject = try {
        val session = event.string("session_id") ?: event.string("thread_id")
        val reference = session?.let { BoardRefs.Codex + it }
        val authenticated = reference != null && BoardRefs.pattern.matches(reference) &&
            (principal == reference || principal?.startsWith(BoardRefs.Hangar) == true && BoardRefs.pattern.matches(principal))
        val stop = event.string("hook_event_name")?.let { it == "Stop" } ?: true
        val consistent = event.string("thread_id")?.let { it == session } ?: true
        val latest = if (authenticated && stop && consistent) latestOwner(event, home, requireNotNull(session)) else null
        if (latest == null) buildJsonObject {} else {
            val result = invoke(latest)
            buildJsonObject { if (result.block && result.reason.isNotBlank()) { put("decision", "block"); put("reason", result.reason) } }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { buildJsonObject {} }

    private fun latestOwner(event: JsonObject, home: File, session: String): String? {
        val sessions = home.resolve("sessions").toPath().toRealPath()
        val named = Regex("rollout-\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}-${Regex.escape(session)}\\.jsonl")
        val asked = event.string("transcript_path")?.let(::File)?.canonicalFile
        val file = asked ?: java.nio.file.Files.walk(sessions, 4).use { files ->
            files.limit(20000).filter { named.matches(it.fileName.toString()) }.map { it.toFile() }.toList().singleOrNull()
        } ?: return null
        if (!file.isFile || !file.toPath().toRealPath().startsWith(sessions) || !named.matches(file.name)) return null
        val text = RandomAccessFile(file, "r").use { input ->
            val start = maxOf(0L, input.length() - 4_000_000)
            input.seek(start)
            val bytes = ByteArray((input.length() - start).toInt())
            input.readFully(bytes)
            bytes.toString(Charsets.UTF_8).let { if (start > 0) it.substringAfter('\n', "") else it }
        }
        return text.lineSequence().toList().asReversed().firstNotNullOfOrNull { line ->
            val row = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@firstNotNullOfOrNull null
            val payload = row["payload"] as? JsonObject ?: return@firstNotNullOfOrNull null
            val message = when {
                row.string("type") == "event_msg" && payload.string("type") == "user_message" -> payload.string("message")
                row.string("type") == "response_item" && payload.string("type") == "message" && payload.string("role") == "user" ->
                    (payload["content"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.string("text") }.joinToString(" ")
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("[keep-working]") && !it.startsWith("<environment_context>") }
            message?.take(16000)
        }
    }

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
