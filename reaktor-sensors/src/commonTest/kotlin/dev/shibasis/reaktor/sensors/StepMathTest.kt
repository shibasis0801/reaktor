package dev.shibasis.reaktor.sensors

import kotlin.test.Test
import kotlin.test.assertEquals

class StepMathTest {
    private val today = "2026-08-04"
    private val yesterday = "2026-08-03"

    @Test
    fun firstEverReadingStartsTheDayAtZero() {
        // The counter has been climbing since boot; none of that belongs to today.
        assertEquals(0, StepMath.stepsToday(counter = 8_432, stored = null, today = today))
        assertEquals(StepBaseline(today, 8_432), StepMath.rebase(8_432, null, today))
    }

    @Test
    fun stepsAreMeasuredFromTheDayStartBaseline() {
        val baseline = StepBaseline(today, 8_432)
        assertEquals(568, StepMath.stepsToday(counter = 9_000, stored = baseline, today = today))
    }

    @Test
    fun aNewDayResetsTheBaselineToTheCurrentCounter() {
        val yesterdaysBaseline = StepBaseline(yesterday, 1_000)

        assertEquals(StepBaseline(today, 9_000), StepMath.rebase(9_000, yesterdaysBaseline, today))
        assertEquals(0, StepMath.stepsToday(counter = 9_000, stored = yesterdaysBaseline, today = today))
    }

    @Test
    fun rebootRebasesToZeroInsteadOfGoingNegative() {
        // The hardware counter resets on reboot, so it now reads lower than the stored baseline.
        val baseline = StepBaseline(today, 9_000)

        assertEquals(StepBaseline(today, 0), StepMath.rebase(120, baseline, today))
        // Everything the counter reports post-reboot happened today.
        assertEquals(120, StepMath.stepsToday(counter = 120, stored = baseline, today = today))
    }

    @Test
    fun stepsNeverGoNegative() {
        val baseline = StepBaseline(today, 500)
        assertEquals(0, StepMath.stepsToday(counter = 500, stored = baseline, today = today))
    }

    @Test
    fun anUnchangedBaselineIsReturnedAsIsSoItIsNotRewritten() {
        val baseline = StepBaseline(today, 8_432)
        assertEquals(baseline, StepMath.rebase(8_500, baseline, today))
    }
}
