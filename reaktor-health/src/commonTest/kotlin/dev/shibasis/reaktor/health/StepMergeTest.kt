package dev.shibasis.reaktor.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The merge rule is the whole reason two step sources can coexist, so it is worth pinning down.
 * Every case here is one a user with both a watch and a phone hits within a day of installing.
 */
class StepMergeTest {

    private fun day(date: String, steps: Int) = DailySteps(date, steps)

    @Test
    fun theStoreWinsWhereItHasData() {
        // The watch counted 9,000; the phone sat on a desk and saw 1,200. Neither adding them nor
        // believing the phone is right.
        val merged = StepMerge.merge(
            store = listOf(day("2026-08-20", 9000)),
            device = listOf(day("2026-08-20", 1200)),
        )

        assertEquals(listOf(day("2026-08-20", 9000)), merged)
    }

    @Test
    fun theDeviceFillsDaysTheStoreNeverHeardOf() {
        // Before the watch was paired, the phone is all there is.
        val merged = StepMerge.merge(
            store = listOf(day("2026-08-20", 9000)),
            device = listOf(day("2026-08-19", 1200), day("2026-08-20", 800)),
        )

        assertEquals(listOf(day("2026-08-19", 1200), day("2026-08-20", 9000)), merged)
    }

    @Test
    fun aZeroFromTheStoreIsTreatedAsMissing() {
        // Mid-morning the watch has not synced yet and reports zero. The phone's 1,200 is a worse
        // number than the watch's eventual one, and a far better number than zero.
        val merged = StepMerge.merge(
            store = listOf(day("2026-08-20", 0)),
            device = listOf(day("2026-08-20", 1200)),
        )

        assertEquals(listOf(day("2026-08-20", 1200)), merged)
    }

    @Test
    fun aZeroFromTheStoreSurvivesWhenNothingElseHasThatDay() {
        // Nothing to fall back to, so the honest zero stands rather than the day vanishing.
        val merged = StepMerge.merge(store = listOf(day("2026-08-20", 0)), device = emptyList())

        assertEquals(listOf(day("2026-08-20", 0)), merged)
    }

    @Test
    fun neitherSourceHavingADayLeavesItOut() {
        val merged = StepMerge.merge(store = emptyList(), device = emptyList())

        assertEquals(emptyList(), merged)
        assertNull(StepMerge.on(merged, "2026-08-20"))
    }

    @Test
    fun theSeriesComesBackInDateOrder() {
        // Callers chart this, and neither store guarantees an order.
        val merged = StepMerge.merge(
            store = listOf(day("2026-08-20", 9000), day("2026-08-18", 4000)),
            device = listOf(day("2026-08-19", 1200)),
        )

        assertEquals(listOf("2026-08-18", "2026-08-19", "2026-08-20"), merged.map { it.date })
    }

    @Test
    fun oneDayCanBePickedOutOfTheSeries() {
        val merged = StepMerge.merge(
            store = listOf(day("2026-08-20", 9000)),
            device = listOf(day("2026-08-19", 1200)),
        )

        assertEquals(9000, StepMerge.on(merged, "2026-08-20"))
        assertEquals(1200, StepMerge.on(merged, "2026-08-19"))
        assertNull(StepMerge.on(merged, "2026-08-21"))
    }
}
