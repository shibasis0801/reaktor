package dev.shibasis.reaktor.performance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ReaktorLighthouseTest {
    private val snapshot = ReaktorLighthouseSnapshot(
        scores = mapOf(
            ReaktorLighthouseCategory.Performance to 85.0,
            ReaktorLighthouseCategory.Accessibility to 95.0,
            ReaktorLighthouseCategory.BestPractices to 100.0,
            ReaktorLighthouseCategory.Seo to 92.0,
        ),
        firstContentfulPaintMs = 1500.0,
        largestContentfulPaintMs = 3000.0,
        speedIndexMs = 3000.0,
        totalBlockingTimeMs = 100.0,
        cumulativeLayoutShift = 0.05,
        timeToFirstByteMs = 120.0,
    )

    @Test
    fun metricNamesAreStableAndCategoryMappingIsTotal() {
        assertEquals("lighthouse.performance", ReaktorLighthouseMetrics.Performance)
        assertEquals("lighthouse.lcp", ReaktorLighthouseMetrics.LargestContentfulPaint)
        assertEquals("lighthouse.cls", ReaktorLighthouseMetrics.CumulativeLayoutShift)
        ReaktorLighthouseCategory.entries.forEach { category ->
            assertTrue(ReaktorLighthouseMetrics.category(category).startsWith("lighthouse."))
        }
    }

    @Test
    fun recommendedBudgetsComposeWebVitalsAndCategoryFloors() {
        assertEquals(5, ReaktorLighthouseBudgets.webVitals().size)
        assertEquals(4, ReaktorLighthouseBudgets.categories().size)

        val recommended = ReaktorLighthouseBudgets.recommended()
        assertEquals(9, recommended.size)

        val lcp = recommended.single { it.metric == ReaktorLighthouseMetrics.LargestContentfulPaint }
        assertEquals(2500.0, lcp.limit)
        assertEquals(ReaktorPerformanceBudgetDirection.Max, lcp.direction)

        val performance = recommended.single { it.metric == ReaktorLighthouseMetrics.Performance }
        assertEquals(90.0, performance.limit)
        assertEquals(ReaktorPerformanceBudgetDirection.Min, performance.direction)
    }

    @Test
    fun lighthouseReportMapsSnapshotIntoTheStandardReport() {
        val report = lighthouseReport(
            target = "reaktor-web",
            snapshot = snapshot,
            generatedAt = "2026-06-05T00:00:00Z",
            url = "http://127.0.0.1:8788/",
            reportPath = "build/reports/performance/reaktor-web-lighthouse.json",
        )

        assertEquals("reaktor-web", report.target)
        assertEquals(3000.0, report.webVitals?.largestContentfulPaintMs)
        assertEquals(0.05, report.webVitals?.cumulativeLayoutShift)

        val perf = report.metrics.single { it.name == ReaktorLighthouseMetrics.Performance }
        assertEquals(85.0, perf.value)
        assertEquals(ReaktorPerformanceDomain.WebVitals, perf.domain)
        // Limits live only in report.budgets, never on the metric — so nothing is double-budgeted.
        assertNull(perf.budgetLimit)

        val toolRun = report.toolRuns.single()
        assertEquals(ReaktorPerformanceTool.Lighthouse, toolRun.tool)
        assertEquals("build/reports/performance/reaktor-web-lighthouse.json", toolRun.reportPath)
        assertEquals(9, report.budgets.size)
    }

    @Test
    fun budgetViolationsHonorMinAndMaxDirection() {
        val report = lighthouseReport(
            target = "reaktor-web",
            snapshot = snapshot,
            generatedAt = "2026-06-05T00:00:00Z",
        )

        val violations = report.budgetViolations()
        // Only the perf score floor (85 < 90, Min) and LCP cap (3000 > 2500, Max) are breached.
        assertEquals(2, violations.size)

        val score = violations.single { it.metric == ReaktorLighthouseMetrics.Performance }
        assertEquals(ReaktorPerformanceBudgetDirection.Min, score.direction)
        assertTrue(score.message.contains("fell below"), score.message)

        val lcp = violations.single { it.metric == ReaktorLighthouseMetrics.LargestContentfulPaint }
        assertEquals(ReaktorPerformanceBudgetDirection.Max, lcp.direction)
        assertTrue(lcp.message.contains("exceeded"), lcp.message)
    }

    @Test
    fun cleanRunProducesNoViolationsAndRoundTrips() {
        val clean = snapshot.copy(
            scores = ReaktorLighthouseBudgets.DefaultCategories.associateWith { 96.0 },
            largestContentfulPaintMs = 1800.0,
        )
        val report = lighthouseReport(
            target = "reaktor-web",
            snapshot = clean,
            generatedAt = "2026-06-05T00:00:00Z",
        )
        assertTrue(report.budgetViolations().isEmpty())

        val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
        val decoded = json.decodeFromString(
            ReaktorPerformanceReport.serializer(),
            json.encodeToString(ReaktorPerformanceReport.serializer(), report),
        )
        assertEquals(report, decoded)
    }
}
