package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.json.*
import kotlin.test.*

class PlanNewsPagingTest {
    @Test fun newsFillsItsShareOldestFirstAndNamesTheUnreadRevision() {
        val owner = BoardActor(BoardActorKind.Person, "Owner")
        val reader = BoardActor(BoardActorKind.Agent, "Lane", "codex:lane")
        val plan = WorkPlan("hangar:news", "News", revision = 5, createdBy = owner, createdAt = 1, updatedAt = 5)
        fun changes(since: Int) = JsonArray((since + 1..5).map { revision -> buildJsonObject {
            put("revision", revision); put("at", revision); put("by", Json.encodeToJsonElement(BoardActor.serializer(), owner))
            put("summary", JsonArray(listOf(JsonPrimitive("Says: " + "x".repeat(1000)))))
        } })
        val page = PlanReadPage.answer(plan, reader, buildJsonObject { put("news", changes(0)) }, budgetBytes = 16_384)
        assertEquals(listOf(1L, 2L, 3L), page.getValue("news").jsonArray.map { it.jsonObject.getValue("revision").jsonPrimitive.long })
        assertTrue(page.getValue("newsTruncated").jsonPrimitive.boolean)
        assertEquals(3, page.getValue("newsSince").jsonPrimitive.int)
        val next = PlanReadPage.answer(plan, reader, buildJsonObject { put("news", changes(3)) }, budgetBytes = 16_384)
        assertEquals(2, next.getValue("news").jsonArray.size)
        assertFalse(next.getValue("newsTruncated").jsonPrimitive.boolean)
        assertTrue(PlanBrief.seat(plan, PlanSeat("seat", "lane", emptyList(), owner, 1), "lane", "").contains("newsSince"))
    }
}
