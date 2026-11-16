package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanScheduleDocumentCommonTest {
    private val owner = BoardActor(BoardActorKind.Person, "Fixture owner")
    private val source = WorkPlan("hangar:schedule-document", "Schedule", items = listOf(
        PlanItem("build", "Build"), PlanItem("check", "Check", after = listOf("build"))),
        createdBy = owner, createdAt = 1, updatedAt = 1)

    private fun patch(vararg ops: JsonObject, fields: JsonObject = JsonObject(emptyMap())): PlanPatch =
        ConductorJson.decodeFromJsonElement(PlanPatch.serializer(), buildJsonObject {
            put("base", source.revision)
            put("ops", JsonArray(ops.toList()))
            fields.forEach { (key, value) -> put(key, value) }
        })

    @Test fun effortAndCalendarDatesRoundTripInTheCommonPlan() {
        val change = patch(buildJsonObject {
            put("type", "update"); put("item", "build"); put("effort", 8.0)
            put("startDate", "2026-11-17"); put("dueDate", "2026-11-20")
        })
        val applied = PlanRules.apply(source, change, owner, 10)
        assertTrue(applied.refused.isEmpty())
        val item = ConductorJson.encodeToJsonElement(PlanItem.serializer(), applied.plan.item("build")!!).jsonObject
        assertEquals(JsonPrimitive(8.0), item["effort"])
        assertEquals(JsonPrimitive("2026-11-17"), item["startDate"])
        assertEquals(JsonPrimitive("2026-11-20"), item["dueDate"])
        assertEquals(applied.plan, ConductorJson.decodeFromString(WorkPlan.serializer(), ConductorJson.encodeToString(WorkPlan.serializer(), applied.plan)))
    }

    @Test fun chosenOrderRefusesAMoveAheadOfItsNamedPrerequisite() {
        val move = patch(buildJsonObject { put("type", "update"); put("item", "check"); put("orderBefore", "build") })
        val applied = PlanRules.apply(source, move, owner, 10)
        assertEquals(0, applied.refused.single().op)
        assertTrue(applied.refused.single().reason.contains("build"))
        val valid = PlanRules.apply(source, patch(buildJsonObject { put("type", "update"); put("item", "build"); put("orderBefore", "check") }), owner, 10)
        assertTrue(valid.refused.isEmpty())
        assertEquals(JsonArray(listOf(JsonPrimitive("build"), JsonPrimitive("check"))),
            ConductorJson.encodeToJsonElement(WorkPlan.serializer(), valid.plan).jsonObject["order"])
    }

    @Test fun documentTextAndApprovalAreOneRevisionCheckedCommonValue() {
        val change = patch(fields = buildJsonObject {
            put("documentText", "# Build {#build}\nShared plan")
            put("approval", "Approved")
        })
        val applied = PlanRules.apply(source, change, owner, 10)
        val encoded = ConductorJson.encodeToJsonElement(WorkPlan.serializer(), applied.plan).jsonObject
        assertEquals(JsonPrimitive("# Build {#build}\nShared plan"), encoded["documentText"])
        assertEquals(JsonPrimitive("Approved"), encoded["approval"])
        assertEquals(source.revision + 1, applied.plan.revision)
    }
    @Test fun scheduleDatesRejectInvalidCalendarsAndReversedRanges() {
        for (date in listOf("2026-02-29", "2026-13-01", "2026-11-31", "17/11/2026")) {
            val result = PlanRules.apply(source, PlanPatch(base = source.revision,
                ops = listOf(PlanOp.Update("build", startDate = date))), owner, 10)
            assertEquals(source, result.plan)
            assertTrue(result.refused.isNotEmpty())
        }
        val reversed = PlanRules.apply(source, PlanPatch(base = source.revision,
            ops = listOf(PlanOp.Update("build", startDate = "2026-11-20", dueDate = "2026-11-17"))), owner, 10)
        assertEquals(source, reversed.plan)
        assertTrue(reversed.refused.isNotEmpty())
    }

    @Test fun scheduleDocumentAndApprovalEditsRefuseAStaleRevision() {
        val moved = PlanRules.apply(source, PlanPatch(ops = listOf(PlanOp.Update("build", title = "Changed"))), owner, 10).plan
        val patches = listOf(
            PlanPatch(base = source.revision, ops = listOf(PlanOp.Update("build", effort = 3.0))),
            PlanPatch(base = source.revision, documentText = "# Build {#build}"),
            PlanPatch(base = source.revision, approval = PlanApproval.Approved))
        for (change in patches) {
            val refused = PlanRules.apply(moved, change, owner, 20)
            assertEquals(moved, refused.plan)
            assertTrue(refused.refused.any { it.reason == "The plan changed; your edit was not applied" })
        }
    }

    @Test fun anOldPlanKeepsItsDefaultsAndSchedulingValuesCanBeCleared() {
        val old = ConductorJson.decodeFromString(WorkPlan.serializer(), """{"thread":"hangar:old","title":"Old","createdBy":{"kind":"Person","name":"Fixture owner"},"createdAt":1,"updatedAt":1} """)
        assertEquals(PlanApproval.Draft, old.approval)
        assertEquals(emptyList(), old.order)
        assertEquals(null, old.documentText)
        val scheduled = PlanRules.apply(source, PlanPatch(base = source.revision, ops = listOf(
            PlanOp.Update("build", effort = 3.0, startDate = "2026-11-17", dueDate = "2026-11-20"))), owner, 10).plan
        val cleared = PlanRules.apply(scheduled, PlanPatch(base = scheduled.revision, ops = listOf(
            PlanOp.Update("build", clearEffort = true, clearStartDate = true, clearDueDate = true))), owner, 20)
        assertTrue(cleared.refused.isEmpty())
        assertEquals(null, cleared.plan.item("build")!!.effort)
        assertEquals(null, cleared.plan.item("build")!!.startDate)
        assertEquals(null, cleared.plan.item("build")!!.dueDate)
    }
}
