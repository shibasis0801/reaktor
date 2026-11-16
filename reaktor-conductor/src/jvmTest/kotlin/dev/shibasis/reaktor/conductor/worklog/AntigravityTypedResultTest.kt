package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.ConductorJson
import kotlinx.serialization.json.jsonObject
import kotlin.test.*

class AntigravityTypedResultTest {
    @Test fun anErrorAnswersTheOldestCallAndKeepsItsNotice() {
        val parser = AntigravityLog("/fixture")
        for (line in listOf(
            """{"type":"USER_INPUT","content":"Inspect invented files","step_index":0}""",
            """{"type":"PLANNER_RESPONSE","step_index":1,"tool_calls":[{"name":"run_command"},{"name":"view_file"}]}""",
            """{"type":"ERROR_MESSAGE","step_index":2,"content":"Invented command failure"}""",
            """{"type":"VIEW_FILE","step_index":3,"status":"DONE","content":"Invented file result"}""",
            """{"type":"PLANNER_RESPONSE","step_index":4,"content":"Fixture finished"}""",
        )) parser.line(ConductorJson.parseToJsonElement(line).jsonObject)
        val log = parser.log(false)
        val turn = log.turns.single()
        assertEquals(StepStatus.Failed, turn.steps.filterIsInstance<WorkStep.Command>().single().status)
        assertEquals(StepStatus.Done, turn.steps.filterIsInstance<WorkStep.Tool>().single().status)
        assertEquals("Invented command failure", turn.steps.filterIsInstance<WorkStep.Note>().single().text)
        assertFalse(log.running)
    }

    @Test fun oldTypedResultsAnswerTheOldestWaitingCallsAndEndTheTurn() {
        val types = listOf("LIST_DIRECTORY", "VIEW_FILE", "GREP_SEARCH", "RUN_COMMAND", "CODE_ACTION", "INVOKE_SUBAGENT", "FUTURE_TOOL")
        val parser = AntigravityLog("/fixture")
        parser.line(ConductorJson.parseToJsonElement("""{"type":"USER_INPUT","content":"Inspect invented files","step_index":0}""").jsonObject)
        parser.line(ConductorJson.parseToJsonElement("""{"type":"PLANNER_RESPONSE","step_index":1,"tool_calls":[{"name":"list_dir"},{"name":"view_file"},{"name":"grep_search"},{"name":"run_command"},{"name":"code_action"},{"name":"invoke_subagent"},{"name":"future_tool"}]}""").jsonObject)
        for (type in listOf("SYSTEM_MESSAGE", "CONVERSATION_HISTORY", "CHECKPOINT")) {
            parser.line(ConductorJson.parseToJsonElement("""{"type":"$type","content":"Invented metadata"}""").jsonObject)
            assertEquals(7, parser.log(false).turns.single().steps.count { it.status == StepStatus.Running })
        }
        types.forEachIndexed { index, type ->
            parser.line(ConductorJson.parseToJsonElement("""{"type":"$type","step_index":${index + 2},"status":"DONE","content":"Invented result $index"}""").jsonObject)
            val steps = parser.log(false).turns.single().steps
            assertEquals(index + 1, steps.count { it.status == StepStatus.Done && it !is WorkStep.Note })
        }
        assertFalse(parser.log(false).running)
    }
}
