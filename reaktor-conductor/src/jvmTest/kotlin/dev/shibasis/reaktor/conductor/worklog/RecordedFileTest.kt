package dev.shibasis.reaktor.conductor.worklog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class RecordedFileTest {
    @Test fun nativeCreationKeepsTheFullBodyBeyondTheDiffLineBudget() {
        val content = (1..1500).joinToString("\n") { "line $it" } + "\n"
        val encoded = Json.encodeToString(kotlinx.serialization.serializer<String>(), content)
        val codex = CodexLog(null)
        codex.line(Json.parseToJsonElement("""{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"FileChange","id":"write","changes":{"New.kt":{"type":"add","content":$encoded}}}}}""").jsonObject)
        val claude = ClaudeLog(null) { null }
        claude.line(Json.parseToJsonElement("""{"type":"assistant","message":{"id":"message","content":[{"type":"tool_use","id":"write","name":"Write","input":{"file_path":"New.kt","content":$encoded}}]}}""").jsonObject)
        // Full bodies are retained separately from the bounded log diff.
        val codexLog = codex.log(false)
        val claudeLog = claude.log(false)
        assertEquals(content, codexLog.edits.single().content)
        assertEquals(content, claudeLog.edits.single().content)
        assertTrue(codexLog.edits.single().cut)
    }
}
