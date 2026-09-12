package dev.shibasis.reaktor.tooling.database

import kotlin.test.*

class QueryPlotTest {
    @Test fun exactNumbersRemainDistinctAndNullsBreakSeries() {
        assertNull(QueryPlot.number("1e2147483647"))
        assertNull(QueryPlot.number("1e-2147483647"))
        val rows = listOf(listOf("9007199254740993", "1"), listOf("9007199254740994", null), listOf("9007199254740995", "3"))
        val series = QueryPlot.series(rows, 0, listOf(1), QueryPlotAxis.Number).single()
        assertEquals(1, series.missing)
        assertNull(series.points[1].y)
        assertEquals(.5, QueryPlot.fraction(series.points[1].x, series.points[0].x, series.points[2].x))
        assertEquals(listOf(0, 1, 2), series.points.map { it.row })
    }
    @Test fun timesRespectOffsetsAndRejectAmbiguousLocalTimestamps() {
        val rows = listOf(listOf("2026-09-13T01:30:00+05:30", "1"), listOf("2026-09-12T20:00:00Z", "2"),
            listOf("2026-09-13T01:30:00", "3"), listOf("2026-09-12", "4"))
        val series = QueryPlot.series(rows, 0, listOf(1), QueryPlotAxis.Time).single()
        assertEquals(1, series.missing)
        assertEquals(series.points[1].x, series.points[2].x)
        assertEquals(3, series.points.first().row)
    }
}
