package dev.shibasis.reaktor.telemetry

import dev.shibasis.reaktor.service.ServiceExecutionPhase
import dev.shibasis.reaktor.service.ServiceSpan
import dev.shibasis.reaktor.telemetry.execution.ExecutionSignals
import dev.shibasis.reaktor.telemetry.execution.SignalLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecutionSignalsTest {
    @Test fun failuresOutrankRefusalStormsWhichOutrankBrokenCallersHealthAndProbes() {
        val storm = (0 until 30).map { index ->
            server("storm-$index", "chat", "GET.fetch", at = if (index < 6) index * 1_000L else 50_000L + index * 1_000L,
                status = if (index % 10 == 0) 404 else 401)
        }
        val failure = server("apps", "social", "getApps", at = 20_000, status = 500, duration = 30_874.0).copy(node = "node:apps")
        val orphan = server("upload", "analytics", "POST.fetch", at = 30_000).copy(parentId = "phone-span")
        val healthy = (0 until 6).map { server("token-$it", "reaktor-server", "token", at = 10_000L * it, duration = 30.0 + it).copy(node = "node:token") }
        val probes = listOf(server("probe-a", "chat", "HEAD.fetch", at = 1_000, status = 404), server("probe-b", "social", "HEAD.fetch", at = 2_000, status = 204))
        val report = ExecutionSignals.of(storm + failure + orphan + healthy + probes)

        assertEquals(listOf("getApps", "GET.fetch", "POST.fetch", "token", "HEAD.fetch", "HEAD.fetch"), report.signals.map { it.key.operation })
        assertEquals(listOf(SignalLevel.Failing, SignalLevel.Degraded, SignalLevel.Watch, SignalLevel.Healthy, SignalLevel.Background, SignalLevel.Background),
            report.signals.map { it.level })
        assertEquals("1 of 1 call failed with HTTP 500 ×1; p95 31 s", report.signals[0].finding)
        assertEquals("30 of 30 calls refused with HTTP 401 ×27, HTTP 404 ×3; rising from 6 to 24 between the halves of the window", report.signals[1].finding)
        assertEquals("1 call names a caller whose span never arrived", report.signals[2].finding)
        assertEquals("6 calls, p50 32 ms", report.signals[3].finding)
        assertEquals("1 call probing the island; 1 refused", report.signals.first { it.key.island == "chat" && it.key.probe }.finding)
        assertEquals(3, report.notable.size)
        assertEquals(7, report.attributed)
        assertEquals(1, report.orphans)
        assertEquals(0, report.clientSpans)
        assertEquals(0, report.clientToServer)
    }

    @Test fun cancellationsNeitherFailNorSkewLatencyAndClientToServerTracesAreCounted() {
        val client = ServiceSpan("journey", "tap", "", ServiceExecutionPhase.CLIENT, "phone", "bestbuds.circles", "mine", 1_000, 900.0, 200, node = "node:circles")
        val server = server("serve", "social", "mine", at = 1_100, duration = 700.0).copy(traceId = "journey", parentId = "tap", node = "node:mine")
        val cancelled = server("gone", "social", "mine", at = 1_200, status = 499, duration = 60_000.0).copy(node = "node:mine")
        val report = ExecutionSignals.of(listOf(client, server, cancelled, server))
        val mine = report.signals.single { it.key.island == "social" }

        assertEquals(SignalLevel.Healthy, mine.level)
        assertEquals(700.0, mine.p95Millis)
        assertEquals(0, mine.failed + mine.refused)
        assertEquals(1, report.clientToServer)
        assertEquals(1, report.crossIsland)
        assertEquals(1, report.clientSpans)
        assertEquals(3, report.spans)
        assertTrue(report.signals.none { it.orphans > 0 })
    }

    @Test fun slowSteadyWorkDegradesWhileOneSlowCallIsWatched() {
        val steady = (0 until 4).map { server("slow-$it", "media", "upload", at = it * 1_000L, duration = 4_000.0 + it) }
        val once = server("once", "bot", "reply", at = 1_000, duration = 3_500.0)
        val report = ExecutionSignals.of(steady + once)
        assertEquals(SignalLevel.Degraded, report.signals.single { it.key.island == "media" }.level)
        assertEquals("p95 4 s", report.signals.single { it.key.island == "media" }.finding)
        assertEquals(SignalLevel.Watch, report.signals.single { it.key.island == "bot" }.level)
    }

    private fun server(id: String, island: String, operation: String, at: Long, status: Int = 200, duration: Double = 1.0) =
        ServiceSpan("trace-$id", id, "", ServiceExecutionPhase.SERVER, island, "bestbuds.worker", operation, at, duration, status)
}
