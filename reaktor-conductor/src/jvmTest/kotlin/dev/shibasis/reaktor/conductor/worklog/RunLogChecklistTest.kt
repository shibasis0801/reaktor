package dev.shibasis.reaktor.conductor.worklog

import dev.shibasis.reaktor.conductor.*
import kotlin.test.*

class RunLogChecklistTest {
    @Test fun aRecordedUpdatePlanKeepsTheTypedChecklist() {
        val activity = AgentActivityRecord(1, "fixture", "codex", 1, 100,
            AgentActivityItem("plan", ActivityKind.Plan, "Update plan", ActivityStatus.Recorded,
                input = """{"explanation":"Fixture checklist","plan":[{"step":"Read Tabs.kt","status":"completed"}]}"""))
        val step = RunLogs.of(emptyList(), listOf(activity), false).turns.single().steps.single()
        assertIs<WorkStep.Plan>(step)
        assertEquals(listOf(PlanItem("Read Tabs.kt", PlanState.Done)), step.items)
        assertEquals("Fixture checklist", step.note)
    }
}
