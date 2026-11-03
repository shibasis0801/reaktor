package dev.shibasis.reaktor.conductor.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlanSignalsTest {
    private fun signals(vararg items: PlanItem) = PlanSignals.of(WorkPlan("hangar:test", "Signals", items = items.toList(),
        revision = 3, createdBy = BoardActor(BoardActorKind.Person, "owner"), createdAt = 1, updatedAt = 1))

    @Test fun scheduleBecomesReadyCriticalSlackAndBottleneckSignals() {
        val signals = signals(
            PlanItem("first", "First", status = PlanStatus.Done),
            PlanItem("second", "Second", after = listOf("first")),
            PlanItem("third", "Third", after = listOf("second")),
            PlanItem("fourth", "Fourth", after = listOf("third")),
            PlanItem("side", "Side", after = listOf("first")),
            PlanItem("join", "Join", after = listOf("fourth", "side", "second")),
        )
        assertEquals(mapOf(PlanSignals.Ready to "true", PlanSignals.Critical to "true", PlanSignals.Unblocks to "3", PlanSignals.Bottleneck to "true"), signals["second"])
        assertEquals(mapOf(PlanSignals.Ready to "true", PlanSignals.Slack to "2", PlanSignals.Unblocks to "1"), signals["side"])
        assertEquals("second", signals.getValue("third")[PlanSignals.Follows])
        assertEquals("fourth", signals.getValue("join")[PlanSignals.Follows])
        assertEquals("second", signals.getValue("join")[PlanSignals.AlsoAfter])
        assertNull(signals.getValue("first")[PlanSignals.Ready])
    }

    @Test fun waitingOnEachOtherIsACycle() {
        val signals = signals(PlanItem("a", "A", after = listOf("b")), PlanItem("b", "B", after = listOf("a")), PlanItem("c", "C"))
        assertEquals("true", signals.getValue("a")[PlanSignals.Cycle])
        assertEquals("true", signals.getValue("b")[PlanSignals.Cycle])
        assertNull(signals.getValue("c")[PlanSignals.Cycle])
    }
}
