package dev.shibasis.reaktor.performance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ReaktorPerformanceCollectorTest {
    @Test
    fun recordsMarksSamplesFlamegraphsAndSerializableReport() {
        val collector = ReaktorPerformanceCollector("reaktor-test")

        collector.mark("entry")
        val sample = collector.sample(
            name = "graph.build",
            iterations = 3,
            medianMs = 12.34,
            bestMs = 9.96,
            worstMs = 25.05,
            budgetMs = 20.04,
        )
        val frame = collector.flameFrame("graph.build", startMs = 1.04, durationMs = 12.06)
        val report = collector.report(
            webVitals = ReaktorWebVitalsSnapshot(
                firstContentfulPaintMs = 100.04,
                largestContentfulPaintMs = 220.05,
                cumulativeLayoutShift = 0.02,
            ),
            appVitals = ReaktorAppVitalsSnapshot(
                appStartMs = 12.34,
                firstInteractiveMs = 25.05,
            ),
        )

        assertEquals("reaktor-test", report.target)
        assertEquals(1, report.marks.size)
        assertEquals(sample, report.samples.single())
        assertEquals(frame, report.flamegraph.single())
        assertEquals(12.3, report.samples.single().medianMs)
        assertEquals(10.0, report.samples.single().bestMs)
        assertEquals(25.1, report.samples.single().worstMs)
        assertEquals(20.0, report.samples.single().budgetMs)

        val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
        val encoded = json.encodeToString(
            ReaktorPerformanceReport.serializer(),
            report,
        )
        val decoded = json.decodeFromString(
            ReaktorPerformanceReport.serializer(),
            encoded,
        )
        assertEquals(report, decoded)
    }

    @Test
    fun benchmarkRecordsIterationsAndBudget() {
        val collector = ReaktorPerformanceCollector("reaktor-test")

        collector.benchmark(
            name = "work",
            warmups = 0,
            iterations = 2,
            budget = 5.milliseconds,
        ) {
            "done"
        }

        val sample = collector.recordedSamples().single()
        assertEquals("work", sample.name)
        assertEquals(2, sample.iterations)
        assertEquals(5.0, sample.budgetMs)
        assertTrue(sample.bestMs <= sample.worstMs)
    }

    @Test
    fun recordsAppVitalsAcrossStartupFramesLongTasksAndMemory() {
        val collector = ReaktorPerformanceCollector("reaktor-test")

        collector.mark(ReaktorAppVitalMarks.ProcessStart)
        collector.traceAppPhase("graph.build") {
            collector.mark(ReaktorAppVitalMarks.GraphReady)
        }
        collector.mark(ReaktorAppVitalMarks.AppStarted)
        collector.mark(ReaktorAppVitalMarks.FirstFrame)
        collector.mark(ReaktorAppVitalMarks.FirstInteractive)
        collector.recordFrame(8.0)
        collector.recordFrame(24.0)
        collector.recordFrame(704.0)
        collector.recordLongTask(72.4)
        collector.recordMemory(usedBytes = 12_000, totalBytes = 40_000, maxBytes = 64_000)
        collector.appMetric("visibleNodes", 42.0, "count")

        val vitals = collector.recordedAppVitals(firstUsefulContentMs = 88.0)

        assertEquals(1, vitals.startupPhases.size)
        assertEquals("graph.build", vitals.startupPhases.single().name)
        assertEquals(3, vitals.frames.frameCount)
        assertEquals(2, vitals.droppedFrames)
        assertEquals(1, vitals.frozenFrames)
        assertEquals(1, vitals.longTasks.count)
        assertEquals(12_000, vitals.memory?.usedBytes)
        assertEquals("visibleNodes", vitals.metrics.single().name)
        assertEquals(88.0, vitals.firstUsefulContentMs)
    }

    @Test
    fun reportsBudgetViolations() {
        val report = ReaktorPerformanceReport(
            target = "reaktor-test",
            generatedAt = "now",
            samples = listOf(
                ReaktorPerformanceSample(
                    name = "inside-budget",
                    iterations = 1,
                    medianMs = 8.0,
                    bestMs = 8.0,
                    worstMs = 8.0,
                    budgetMs = 10.0,
                ),
                ReaktorPerformanceSample(
                    name = "outside-budget",
                    iterations = 1,
                    medianMs = 12.0,
                    bestMs = 12.0,
                    worstMs = 12.0,
                    budgetMs = 10.0,
                ),
            ),
        )

        val violation = report.budgetViolations().single()
        assertEquals("outside-budget", violation.sampleName)
        assertEquals(12.0, violation.medianMs)
        assertEquals(10.0, violation.budgetMs)
        assertTrue(violation.message.contains("outside-budget"))
    }

    @Test
    fun recordsBuildServerProfileMetricsAndToolRuns() {
        val collector = ReaktorPerformanceCollector("reaktor-test")
        val scope = ReaktorPerformanceScope(
            graphId = "root",
            nodeId = "ChatService",
            route = "/chats",
            service = "MessagingService",
            module = "modules/app",
        )

        val artifact = collector.buildArtifact(
            name = "app-release.apk",
            type = ReaktorBuildArtifactType.AndroidApk,
            bytes = 15_200_000,
            compressedBytes = 14_900_000,
            budgetBytes = 15_000_000,
            path = "build/outputs/app-release.apk",
            scope = scope,
        )
        val timing = collector.buildTime(
            name = "ksp.incremental",
            durationMs = 6_200.04,
            budgetMs = 5_000.0,
            scope = scope,
        )
        val metric = collector.metric(
            name = "service.messaging.p95",
            value = 248.44,
            unit = "ms",
            domain = ReaktorPerformanceDomain.ServerVitals,
            scope = scope,
            budgetLimit = 200.0,
        )
        val serverVitals = collector.serverVitals(
            startupMs = 1_280.04,
            readyMs = 1_480.05,
            p95LatencyMs = 44.24,
            throughputPerSecond = 520.05,
            memory = ReaktorMemoryVitals(usedBytes = 42_000_000),
            metrics = listOf(metric),
        )
        val hotFrame = collector.flameFrame("Repository.fetch", startMs = 4.04, durationMs = 32.04)
        val profile = collector.profileCapture(
            name = "messaging-cpu",
            profiler = ReaktorProfilerKind.AsyncProfiler,
            platform = "jvm",
            startedAt = "2026-05-21T00:00:00Z",
            durationMs = 30_000.04,
            outputPath = "build/reports/perf/messaging.html",
            sampleCount = 1200,
            topFrames = listOf(hotFrame),
            scope = scope,
        )
        val run = collector.toolRun(
            name = "chat-scroll",
            tool = ReaktorPerformanceTool.Maestro,
            status = ReaktorPerformanceRunStatus.Passed,
            startedAt = "2026-05-21T00:00:01Z",
            durationMs = 920.04,
            reportPath = "build/reports/perf/chat-scroll.json",
            metrics = listOf(metric),
            scope = scope,
        )

        val report = collector.report()

        assertEquals(artifact, report.buildArtifacts.single())
        assertEquals(timing, report.buildTimings.single())
        assertEquals(metric, report.metrics.single())
        assertEquals(serverVitals, report.serverVitals)
        assertEquals(profile, report.profiles.single())
        assertEquals(run, report.toolRuns.single())
        assertEquals("build/outputs/app-release.apk", report.buildArtifacts.single().scope.artifactPath)
        assertEquals(1280.0, report.serverVitals?.startupMs)
        assertEquals(30000.0, report.profiles.single().durationMs)

        val violations = report.budgetViolations()
        assertTrue(violations.any { it.metric == "app-release.apk" && it.domain == ReaktorPerformanceDomain.Build })
        assertTrue(violations.any { it.metric == "ksp.incremental" && it.domain == ReaktorPerformanceDomain.Build })
        assertTrue(violations.any { it.metric == "service.messaging.p95" && it.domain == ReaktorPerformanceDomain.ServerVitals })
    }

    @Test
    fun harnessRecordsFailedToolRunsAndEnforcesBudgets() {
        val harness = ReaktorPerformanceHarness("reaktor-test")

        assertFailsWith<IllegalStateException> {
            harness.run(
                name = "web-journey",
                tool = ReaktorPerformanceTool.Playwright,
            ) {
                error("navigation timed out")
            }
        }

        val failed = harness.collector.recordedToolRuns().single()
        assertEquals(ReaktorPerformanceRunStatus.Failed, failed.status)
        assertEquals("navigation timed out", failed.errorMessage)

        harness.collector.sample(
            name = "graph.autoWire",
            iterations = 1,
            medianMs = 14.0,
            bestMs = 14.0,
            worstMs = 14.0,
            budgetMs = 10.0,
        )

        val failure = assertFailsWith<IllegalStateException> {
            harness.requireWithinBudgets()
        }
        assertTrue(failure.message?.contains("graph.autoWire") == true)
    }

    @Test
    fun traceFrameRecordsSuccessfulAndFailingBlocks() {
        val collector = ReaktorPerformanceCollector("reaktor-test")

        val result = collector.traceFrame("ok") {
            "done"
        }
        assertEquals("done", result)

        assertFailsWith<IllegalStateException> {
            collector.traceFrame("failed") {
                error("boom")
            }
        }

        val frames = collector.recordedFlamegraph()
        assertEquals(listOf("ok", "failed"), frames.map { it.name })
        assertTrue(frames.all { it.durationMs >= 0.0 })
    }

    @Test
    fun rejectsInvalidSampleCounts() {
        val collector = ReaktorPerformanceCollector("reaktor-test")

        assertFailsWith<IllegalArgumentException> {
            collector.sample(
                name = "bad",
                iterations = 0,
                medianMs = 0.0,
                bestMs = 0.0,
                worstMs = 0.0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            collector.benchmark(name = "bad", iterations = 0) {
                "done"
            }
        }
        assertFailsWith<IllegalArgumentException> {
            collector.benchmark(name = "bad", warmups = -1) {
                "done"
            }
        }
    }
}
