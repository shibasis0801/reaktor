package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class AntigravityBrainRootTest {
    @Test fun bothBrainRootsReadTheirOwnConversationSteps() {
        val home = Files.createTempDirectory("native-brain-roots").toFile()
        try {
            for ((index, root) in listOf("antigravity", "antigravity-cli").withIndex()) {
                val id = "invented-$index"
                val file = home.resolve(".gemini/$root/brain/$id/.system_generated/logs/transcript.jsonl").apply { parentFile.mkdirs() }
                file.writeText(listOf(
                    """{"type":"USER_INPUT","step_index":0,"content":"Inspect the invented graph"}""",
                    """{"type":"PLANNER_RESPONSE","step_index":1,"thinking":"Read the declared ports","content":"Inspecting ports","tool_calls":[{"name":"view_file","args":{"AbsolutePath":"/fixture/Ports.kt"}}]}""",
                    """{"type":"VIEW_FILE","step_index":2,"status":"DONE","content":"Two declared ports"}""",
                    """{"type":"CHECKPOINT","step_index":3}""",
                    """{"type":"GENERIC","step_index":4,"content":"The invented graph is complete"}""",
                ).joinToString("\n", postfix = "\n"))
                assertEquals(file, WorkLogs.transcript(RuntimeKind.Gemini, id, home))
                val log = WorkLogs.read(RuntimeKind.Gemini, file, "/fixture")
                val turn = log.turns.single()
                assertEquals("Inspect the invented graph", turn.prompt)
                assertEquals(2, turn.steps.filterIsInstance<WorkStep.Message>().size)
                assertEquals(1, turn.steps.filterIsInstance<WorkStep.Reasoning>().size)
                assertEquals(1, turn.steps.filterIsInstance<WorkStep.Tool>().size)
                assertEquals(1, turn.steps.filterIsInstance<WorkStep.Note>().size)
                assertFalse(log.running)
            }
        } finally { home.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }
}
