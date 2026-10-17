package dev.shibasis.reaktor.surface

import kotlin.test.Test
import kotlin.test.assertEquals

class ManyOfKernelTest {
    private val full = ManyOfProperties(selected = setOf("a", "b"), capacity = 2)

    @Test
    fun pickingPastTheCapacityIsRefusedWithAWarning() {
        val refused = ManyOfKernel.reduce(full, ManyOfState(), Choose("c", 1))
        assertEquals(listOf<ManyOfEvent>(ManyOfEvent.Refused("c")), refused.events)
        assertEquals(listOf(FeedbackCue.Warning), refused.cues)
    }

    @Test
    fun pickingAndUnpickingTickAsSelections() {
        val unpicked = ManyOfKernel.reduce(full, ManyOfState(), Choose("a", 1))
        assertEquals(listOf<ManyOfEvent>(ManyOfEvent.Change(setOf("b"))), unpicked.events)
        assertEquals(listOf(FeedbackCue.Selection), unpicked.cues)
        val picked = ManyOfKernel.reduce(ManyOfProperties(setOf("a"), capacity = 2), ManyOfState(), Choose("b", 1))
        assertEquals(listOf(FeedbackCue.Selection), picked.cues)
    }
}
