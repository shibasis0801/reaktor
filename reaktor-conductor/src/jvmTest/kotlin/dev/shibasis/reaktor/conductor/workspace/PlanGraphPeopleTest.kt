package dev.shibasis.reaktor.conductor.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlanGraphPeopleTest {
    private val owner = BoardActor(BoardActorKind.Person, "ovd")
    private val plan = WorkPlan("claude:t", "Plan", items = listOf(
        PlanItem("build", "Build it", refs = listOf("person:Manab", "agent:Codex", "file:Pane.kt")),
        PlanItem("check", "Check it", after = listOf("build")),
    ), createdBy = owner, createdAt = 1, updatedAt = 1)

    @Test fun aCardNamesThePeopleAndAgentsTaggedOnIt() {
        val shape = PlanGraph.shape(listOf(plan))
        assertEquals("Manab, Codex (agent)", shape.nodes.single { it.id == "claude:t#build" }.attributes["people"])
        assertNull(shape.nodes.single { it.id == "claude:t#check" }.attributes["people"])
    }

    @Test fun tagsNeverBecomeCodeLinks() {
        val shape = PlanGraph.shape(listOf(plan))
        assertEquals(listOf("file:Pane.kt"), shape.nodes.mapNotNull { it.attributes["ref"] })
    }

    @Test fun namesMarkAgents() {
        assertEquals("Shibasis, Claude (agent)", PlanPeople.names(listOf(BoardActor(BoardActorKind.Person, "Shibasis"), BoardActor(BoardActorKind.Agent, "Claude"))))
        assertNull(PlanPeople.names(emptyList()))
    }
}
