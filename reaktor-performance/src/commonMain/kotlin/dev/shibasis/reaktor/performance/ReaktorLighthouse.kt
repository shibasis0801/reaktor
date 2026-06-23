package dev.shibasis.reaktor.performance

import kotlinx.serialization.Serializable

/**
 * The standard Lighthouse surface for reaktor-performance: the category taxonomy, the durable
 * metric-name identities budgets and tooling reference, the recommended "good" budget set, and a
 * pure builder that maps an extracted [ReaktorLighthouseSnapshot] into a [ReaktorPerformanceReport].
 *
 * The LHR JSON is parsed by the node runner (`tools/lighthouse-report.mjs`, via `ts/src/index.ts`);
 * this file is the cross-target contract that runner and Kotlin/JS hosts share, so a Lighthouse run
 * lands in the same report/budget model as every other Reaktor performance surface.
 */
@Serializable
enum class ReaktorLighthouseCategory {
    Performance,
    Accessibility,
    BestPractices,
    Seo,
    Pwa,
}

/**
 * Metric-name identities. Like reaktor-service operation names, these are the durable keys budgets,
 * telemetry, and generated clients match on — independent of how any runner labels its output.
 * Keep in sync with the `lighthouseMetrics` mirror in `ts/src/index.ts`.
 */
object ReaktorLighthouseMetrics {
    const val Performance = "lighthouse.performance"
    const val Accessibility = "lighthouse.accessibility"
    const val BestPractices = "lighthouse.best-practices"
    const val Seo = "lighthouse.seo"
    const val Pwa = "lighthouse.pwa"

    const val FirstContentfulPaint = "lighthouse.fcp"
    const val LargestContentfulPaint = "lighthouse.lcp"
    const val SpeedIndex = "lighthouse.speed-index"
    const val TotalBlockingTime = "lighthouse.tbt"
    const val TimeToInteractive = "lighthouse.tti"
    const val CumulativeLayoutShift = "lighthouse.cls"
    const val TimeToFirstByte = "lighthouse.ttfb"

    fun category(category: ReaktorLighthouseCategory): String =
        when (category) {
            ReaktorLighthouseCategory.Performance -> Performance
            ReaktorLighthouseCategory.Accessibility -> Accessibility
            ReaktorLighthouseCategory.BestPractices -> BestPractices
            ReaktorLighthouseCategory.Seo -> Seo
            ReaktorLighthouseCategory.Pwa -> Pwa
        }
}

/**
 * Standard budgets at the web.dev "good" thresholds. Web Vitals are device-agnostic targets, so this
 * one set is the baseline for any form factor — the runner's form factor changes how the page is
 * measured, not the target. Compose a custom set with [webVitals] + [categories], or override per host.
 */
object ReaktorLighthouseBudgets {
    const val DefaultMinScore = 90.0

    val DefaultCategories: List<ReaktorLighthouseCategory> = listOf(
        ReaktorLighthouseCategory.Performance,
        ReaktorLighthouseCategory.Accessibility,
        ReaktorLighthouseCategory.BestPractices,
        ReaktorLighthouseCategory.Seo,
    )

    /** Lower-is-better lab/Core-Web-Vitals caps (Max). */
    fun webVitals(): List<ReaktorPerformanceBudget> = listOf(
        cap(ReaktorLighthouseMetrics.LargestContentfulPaint, 2500.0, "ms"),
        cap(ReaktorLighthouseMetrics.TotalBlockingTime, 200.0, "ms"),
        cap(ReaktorLighthouseMetrics.CumulativeLayoutShift, 0.1, ""),
        cap(ReaktorLighthouseMetrics.SpeedIndex, 3400.0, "ms"),
        cap(ReaktorLighthouseMetrics.FirstContentfulPaint, 1800.0, "ms"),
    )

    /** Higher-is-better category-score floors (Min). */
    fun categories(
        min: Double = DefaultMinScore,
        categories: List<ReaktorLighthouseCategory> = DefaultCategories,
    ): List<ReaktorPerformanceBudget> = categories.map { category ->
        ReaktorPerformanceBudget(
            metric = ReaktorLighthouseMetrics.category(category),
            limit = min,
            unit = "score",
            direction = ReaktorPerformanceBudgetDirection.Min,
        )
    }

    fun recommended(): List<ReaktorPerformanceBudget> = webVitals() + categories()

    private fun cap(metric: String, limit: Double, unit: String) =
        ReaktorPerformanceBudget(metric = metric, limit = limit, unit = unit)
}

/**
 * Extracted Lighthouse result: 0..100 category scores plus the lab metrics. The node runner (or any
 * other LHR source) fills this; [lighthouseReport] turns it into a [ReaktorPerformanceReport].
 */
@Serializable
data class ReaktorLighthouseSnapshot(
    val scores: Map<ReaktorLighthouseCategory, Double> = emptyMap(),
    val firstContentfulPaintMs: Double? = null,
    val largestContentfulPaintMs: Double? = null,
    val speedIndexMs: Double? = null,
    val totalBlockingTimeMs: Double? = null,
    val timeToInteractiveMs: Double? = null,
    val cumulativeLayoutShift: Double? = null,
    val timeToFirstByteMs: Double? = null,
)

/**
 * Assemble a [ReaktorPerformanceReport] from a [ReaktorLighthouseSnapshot]. Category scores and lab
 * metrics become [ReaktorPerformanceDomain.WebVitals] metrics under the [ReaktorLighthouseMetrics]
 * names; limits live only in [budgets] (matched by name in [budgetViolations]) so a metric is never
 * double-budgeted. The field web vitals also populate [ReaktorWebVitalsSnapshot] for parity with the
 * in-browser collector. Pure (no clock/IO): [generatedAt] is supplied so callers and tests stay
 * deterministic.
 */
fun lighthouseReport(
    target: String,
    snapshot: ReaktorLighthouseSnapshot,
    generatedAt: String,
    url: String? = null,
    reportPath: String? = null,
    budgets: List<ReaktorPerformanceBudget> = ReaktorLighthouseBudgets.recommended(),
    startedAt: String = generatedAt,
    durationMs: Double = 0.0,
): ReaktorPerformanceReport {
    val scope = ReaktorPerformanceScope(route = url)
    val metrics = buildList {
        snapshot.scores.forEach { (category, score) ->
            add(metric(ReaktorLighthouseMetrics.category(category), score, "score", scope))
        }
        snapshot.firstContentfulPaintMs?.let { add(metric(ReaktorLighthouseMetrics.FirstContentfulPaint, it, "ms", scope)) }
        snapshot.largestContentfulPaintMs?.let { add(metric(ReaktorLighthouseMetrics.LargestContentfulPaint, it, "ms", scope)) }
        snapshot.speedIndexMs?.let { add(metric(ReaktorLighthouseMetrics.SpeedIndex, it, "ms", scope)) }
        snapshot.totalBlockingTimeMs?.let { add(metric(ReaktorLighthouseMetrics.TotalBlockingTime, it, "ms", scope)) }
        snapshot.timeToInteractiveMs?.let { add(metric(ReaktorLighthouseMetrics.TimeToInteractive, it, "ms", scope)) }
        snapshot.cumulativeLayoutShift?.let { add(metric(ReaktorLighthouseMetrics.CumulativeLayoutShift, it, "", scope)) }
        snapshot.timeToFirstByteMs?.let { add(metric(ReaktorLighthouseMetrics.TimeToFirstByte, it, "ms", scope)) }
    }
    val toolRun = ReaktorPerformanceToolRun(
        name = url ?: target,
        tool = ReaktorPerformanceTool.Lighthouse,
        status = ReaktorPerformanceRunStatus.Passed,
        startedAt = startedAt,
        durationMs = durationMs,
        reportPath = reportPath,
        scope = scope,
    )
    return ReaktorPerformanceReport(
        target = target,
        generatedAt = generatedAt,
        webVitals = ReaktorWebVitalsSnapshot(
            firstContentfulPaintMs = snapshot.firstContentfulPaintMs,
            largestContentfulPaintMs = snapshot.largestContentfulPaintMs,
            cumulativeLayoutShift = snapshot.cumulativeLayoutShift ?: 0.0,
            timeToFirstByteMs = snapshot.timeToFirstByteMs,
        ),
        metrics = metrics,
        toolRuns = listOf(toolRun),
        budgets = budgets,
    )
}

private fun metric(name: String, value: Double, unit: String, scope: ReaktorPerformanceScope) =
    ReaktorPerformanceMetric(
        name = name,
        value = value,
        unit = unit,
        domain = ReaktorPerformanceDomain.WebVitals,
        scope = scope,
    )
