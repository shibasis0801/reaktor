package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanEvolutionTest {
    private val person = BoardActor(BoardActorKind.Person, "Shibasis", "hangar:test")
    private val agent = BoardActor(BoardActorKind.Agent, "codex", "codex:test")
    private fun draw(vararg ops: PlanOp): WorkPlan {
        val patch = PlanPatch(title = "Plan evolution", ops = ops.toList())
        val applied = PlanRules.apply(PlanRules.start("hangar:test", patch, person, 1), patch, person, 2)
        assertTrue(applied.refused.isEmpty(), applied.refused.toString())
        return applied.plan
    }

    @Test fun priorityDefaultsForOldPlansAndSurvivesAddAndUpdate() {
        assertEquals(PlanPriority.Normal, ConductorJson.decodeFromString(PlanItem.serializer(), "{\"id\":\"old\",\"title\":\"Old item\"}").priority)
        val plan = draw(PlanOp.Add("Build", key = "build", priority = PlanPriority.High))
        assertEquals(PlanPriority.High, plan.item("build")?.priority)
        val updated = PlanRules.apply(plan, PlanPatch(ops = listOf(PlanOp.Update("build", priority = PlanPriority.Urgent))), person, 3).plan
        val restored = ConductorJson.decodeFromString(WorkPlan.serializer(), ConductorJson.encodeToString(WorkPlan.serializer(), updated))
        assertEquals(PlanPriority.Urgent, restored.item("build")?.priority)
        assertEquals("Urgent", PlanGraph.shape(listOf(restored)).nodes.first { it.id.endsWith("#build") }.attributes["priority"])
    }

    @Test fun readyTasksRespectDependenciesBeforePriorityAndKeepEqualPrioritiesStable() {
        val plan = draw(PlanOp.Add("Low", key = "low", priority = PlanPriority.Low),
            PlanOp.Add("Normal", key = "normal"), PlanOp.Add("High", key = "high", priority = PlanPriority.High),
            PlanOp.Add("Urgent", key = "urgent", priority = PlanPriority.Urgent),
            PlanOp.Add("Blocked urgent", key = "blocked", after = listOf("low"), priority = PlanPriority.Urgent),
            PlanOp.Add("Another high", key = "high-again", priority = PlanPriority.High),
            PlanOp.Add("Finished", key = "done", status = PlanStatus.Done))
        assertEquals(listOf("urgent", "high", "high-again", "normal", "low"), plan.ready().map { it.id })
        val finished = PlanRules.apply(plan, PlanPatch(ops = listOf(PlanOp.Status("low", PlanStatus.Done))), person, 3).plan
        assertEquals(listOf("urgent", "blocked"), finished.ready().take(2).map { it.id })
    }

    @Test fun droppingRequiresAReasonAndAValidReplacement() {
        val plan = draw(PlanOp.Add("Old route", key = "old"), PlanOp.Add("Better route", key = "new"))
        listOf(PlanOp.Status("old", PlanStatus.Dropped), PlanOp.Status("old", PlanStatus.Dropped, "  "),
            PlanOp.Status("old", PlanStatus.Dropped, "Simpler", "old"), PlanOp.Status("old", PlanStatus.Dropped, "Simpler", "missing"),
            PlanOp.Add("Already closed", key = "closed", status = PlanStatus.Dropped)).forEach { op ->
            val refused = PlanRules.apply(plan, PlanPatch(ops = listOf(op)), person, 3)
            assertEquals(1, refused.refused.size)
            assertEquals(plan, refused.plan)
        }
        val closed = PlanRules.apply(plan, PlanPatch(ops = listOf(PlanOp.Status("old", PlanStatus.Dropped, "  Uses the existing path  ", "new"))), agent, 3).plan
        val restored = ConductorJson.decodeFromString(WorkPlan.serializer(), ConductorJson.encodeToString(WorkPlan.serializer(), closed))
        assertEquals(2, restored.items.size)
        assertEquals("Uses the existing path", restored.item("old")?.reason)
        assertEquals("new", restored.item("old")?.replacement)
        assertEquals(PlanStatus.Dropped, restored.item("old")?.status)
        val node = PlanGraph.shape(listOf(restored)).nodes.first { it.id.endsWith("#old") }
        assertEquals("Uses the existing path", node.attributes["reason"])
        assertEquals("new", node.attributes["replacement"])
        assertEquals(1, PlanRules.apply(restored, PlanPatch(ops = listOf(PlanOp.Update("old", reason = ""))), person, 4).refused.size)
    }

    @Test fun agentsKeepPlannedHistoryAndLegacyClosedItemsStillRead() {
        val old = ConductorJson.decodeFromString(PlanItem.serializer(), "{\"id\":\"old\",\"title\":\"Old item\",\"status\":\"Dropped\"}")
        assertEquals("", old.reason)
        val plan = draw(PlanOp.Add("Keep history", key = "history"))
        val refused = PlanRules.apply(plan, PlanPatch(ops = listOf(PlanOp.Remove("history"))), agent, 3)
        assertEquals(plan.items, refused.plan.items)
        assertTrue(refused.refused.single().reason.contains("reason"))
    }
}
