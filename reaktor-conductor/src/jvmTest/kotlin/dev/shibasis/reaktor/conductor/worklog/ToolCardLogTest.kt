package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.workspace.AgentWorkspaceJson
import kotlinx.serialization.json.*
import kotlin.test.*

class ToolCardLogTest {
    @Test fun codexExplorationKeepsItsCommandAndCombinedOutputOnce() {
        val parser = CodexLog(null)
        parser.line(Json.parseToJsonElement("""{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"CommandExecution","id":"fixture-read","command":["inspect-fixture"],"parsed_cmd":[{"type":"read","cmd":"read Fixture.kt","path":"Fixture.kt"},{"type":"search","cmd":"find fixture","query":"fixture"}],"aggregated_output":"Invented combined output","exit_code":0,"status":"completed"}}}""").jsonObject)
        val steps = parser.log(false).turns.single().steps.filterIsInstance<WorkStep.Tool>()
        assertEquals("read Fixture.kt", steps.first().input)
        assertEquals("Invented combined output", steps.first().output)
        assertEquals(null, steps.last().output)
    }

    @Test fun antigravityNativeReadKeepsItsArgumentsAndResult() {
        val parser = AntigravityLog(null)
        parser.line(Json.parseToJsonElement("""{"step_index":0,"type":"USER_INPUT","content":"Inspect an invented fixture"}""").jsonObject)
        parser.line(Json.parseToJsonElement("""{"step_index":1,"type":"PLANNER_RESPONSE","tool_calls":[{"name":"view_file","args":{"AbsolutePath":"Fixture.kt","StartLine":1,"EndLine":2}}]}""").jsonObject)
        parser.line(Json.parseToJsonElement("""{"step_index":2,"type":"VIEW_FILE","status":"DONE","content":"Invented source lines"}""").jsonObject)
        val step = parser.log(false).turns.single().steps.single() as WorkStep.Tool
        assertTrue(step.input.orEmpty().contains("AbsolutePath"))
        assertEquals("Invented source lines", step.output)
    }

    @Test fun nativeClaudeToolArgumentsAndResultsRemainAvailableToCards() {
        val parser = ClaudeLog(null) { null }
        parser.line(Json.parseToJsonElement("""{"type":"user","message":{"content":"Inspect the invented fixture"}}""").jsonObject)
        parser.line(Json.parseToJsonElement("""{"type":"assistant","message":{"id":"fixture-read-message","content":[{"type":"tool_use","id":"fixture-read","name":"Read","input":{"file_path":"Fixture.kt","offset":10,"limit":2}}]}}""").jsonObject)
        parser.line(Json.parseToJsonElement("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"fixture-read","content":"Invented source lines"}]}}""").jsonObject)
        val step = parser.log(false).turns.single().steps.single() as WorkStep.Tool
        assertTrue(step.input.orEmpty().contains("file_path"))
        assertEquals("Invented source lines", step.output)
    }

    @Test fun claudeQuestionsKeepTheirChoicesAndIds() {
        val parser = ClaudeLog(null) { null }
        parser.line(Json.parseToJsonElement("""{"type":"assistant","message":{"id":"fixture-message","content":[{"type":"tool_use","id":"fixture-question","name":"AskUserQuestion","input":{"questions":[{"question":"Which invented file?","header":"File","options":[{"label":"Alpha","description":"Inspect Alpha"},{"label":"Beta","description":"Inspect Beta"}]}]}}]}}""").jsonObject)
        val question = parser.log(false).turns.single().steps.single() as WorkStep.Question
        val fields = AgentWorkspaceJson.encodeToJsonElement(WorkStep.serializer(), question).jsonObject
        val questions = assertNotNull(fields["questions"]).jsonArray
        assertEquals(1, questions.size)
        assertEquals("q0", questions.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(listOf("Alpha", "Beta"), questions.single().jsonObject["options"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Which invented file?", questions.single().jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test fun codexQuestionsKeepTheirChoicesAndIds() {
        val parser = CodexLog(null)
        parser.line(Json.parseToJsonElement("""{"type":"event_msg","payload":{"type":"request_user_input","id":"fixture-question","questions":[{"id":"fixture-file","header":"File","question":"Which invented file?","options":[{"label":"Alpha","description":"Inspect Alpha"},{"label":"Beta","description":"Inspect Beta"}]}]}}""").jsonObject)
        val question = parser.log(false).turns.single().steps.single() as WorkStep.Question
        val fields = AgentWorkspaceJson.encodeToJsonElement(WorkStep.serializer(), question).jsonObject
        val questions = assertNotNull(fields["questions"]).jsonArray
        assertEquals("fixture-file", questions.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(listOf("Alpha", "Beta"), questions.single().jsonObject["options"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun codexCardsKeepTheStructuredReceiptAndTheText() {
        val parser = CodexLog(null)
        parser.line(Json.parseToJsonElement("""{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"McpToolCall","id":"fixture-check","tool":"check_run","arguments":{"tasks":["fixture:test"]},"result":{"content":[{"type":"text","text":"Invented check receipt"}],"structuredContent":{"phase":"test","cause":"assertion","counts":{"passed":3,"failed":1}}}}}}""").jsonObject)
        val tool = parser.log(false).turns.single().steps.single() as WorkStep.Tool
        val output = assertNotNull(tool.output)
        assertTrue(output.contains("Invented check receipt"))
        assertTrue(output.contains("phase"))
        assertTrue(output.contains("passed"))
    }

    @Test fun claudeDiffsComeFromTheRecordedStructuredPatch() {
        val parser = ClaudeLog(null) { null }
        parser.line(Json.parseToJsonElement("""{"type":"assistant","message":{"id":"fixture-message","content":[{"type":"tool_use","id":"fixture-edit","name":"Edit","input":{"file_path":"Fixture.kt","old_string":"wrong","new_string":"fallback"}}]}}""").jsonObject)
        parser.line(Json.parseToJsonElement("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"fixture-edit","content":"Edited"}]},"toolUseResult":{"structuredPatch":[{"oldStart":12,"newStart":12,"lines":["-recordedOld()","+recordedNew()"]}]}}""").jsonObject)
        val edit = parser.log(false).edits.single()
        assertEquals(listOf("-recordedOld()", "+recordedNew()"), edit.hunks.single().lines)
        assertEquals(12, edit.line)
    }

    @Test fun codexDiffsComeFromTheRecordedUnifiedDiff() {
        val parser = CodexLog(null)
        parser.line(Json.parseToJsonElement("""{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"FileChange","id":"fixture-edit","changes":{"Fixture.kt":{"type":"update","unified_diff":"@@ -12 +12 @@\n-recordedOld()\n+recordedNew()"}}}}}""").jsonObject)
        val edit = parser.log(false).edits.single()
        assertEquals(listOf("-recordedOld()", "+recordedNew()"), edit.hunks.single().lines)
        assertEquals(12, edit.line)
    }
}
