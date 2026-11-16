package dev.shibasis.reaktor.conductor.workspace

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlanAnalysisRevisionTest {
    private val owner = BoardActor(BoardActorKind.Person, "Fixture owner")
    private fun plan(items: List<PlanItem>, revision: Long = 1) = WorkPlan("hangar:analysis-revision", "Analysis", items = items,
        revision = revision, createdBy = owner, createdAt = 1, updatedAt = 1)

    @Test fun repeatedAndConcurrentReadersShareOneAnalysisForARevision() {
        val plan = plan(List(40) { PlanItem("p$it", "Item $it", after = if (it == 0) emptyList() else listOf("p${it - 1}")) })
        val barrier = CyclicBarrier(8)
        val workers = Executors.newFixedThreadPool(8)
        try {
            val reports = List(8) { workers.submit<PlanScheduleReport> { barrier.await(5, TimeUnit.SECONDS); PlanAnalysis.report(plan) } }
                .map { it.get(10, TimeUnit.SECONDS) }
            reports.forEach { assertSame(reports.first(), it) }
            assertSame(reports.first(), PlanAnalysis.report(plan))
            assertNotSame(reports.first(), PlanAnalysis.report(plan.copy(revision = 2)))
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun aSemanticChangeCannotReuseAnAnalysisAndHydratedCandidatesStayFresh() {
        val first = plan(listOf(PlanItem("a", "Old title"), PlanItem("b", "Second", after = listOf("a"))))
        val report = PlanAnalysis.report(first)
        val renamed = first.copy(items = first.items.map { if (it.id == "a") it.copy(title = "New title", at = PlanPoint(2.0, 3.0)) else it })
        assertEquals("New title", PlanAnalysis.report(renamed).ready.single().item.title)
        assertEquals(PlanPoint(2.0, 3.0), PlanAnalysis.report(renamed).ready.single().item.at)
        val changed = first.copy(items = first.items.map { if (it.id == "a") it.copy(status = PlanStatus.Dropped, reason = "Retired") else it })
        assertNotSame(report, PlanAnalysis.report(changed))
        assertTrue(PlanAnalysis.report(changed).ready.isEmpty())
        assertEquals("a", PlanAnalysis.report(changed).review.single().dependency)
    }

    @Test fun indexedWidthEqualsEveryAntichainOnEveryForwardDagThroughSixVertices() {
        for (size in 0..6) {
            val edges = (0 until size).flatMap { to -> (0 until to).map { from -> from to to } }
            for (mask in 0 until (1 shl edges.size)) {
                val reach = IntArray(size)
                edges.forEachIndexed { bit, (from, to) -> if (mask and (1 shl bit) != 0) reach[to] = reach[to] or (1 shl from) }
                for (to in 0 until size) for (from in 0 until to) if (reach[to] and (1 shl from) != 0) reach[to] = reach[to] or reach[from]
                var width = 0
                for (chosen in 0 until (1 shl size)) {
                    if (Integer.bitCount(chosen) <= width) continue
                    if ((0 until size).all { chosen and (1 shl it) == 0 || reach[it] and chosen == 0 }) width = Integer.bitCount(chosen)
                }
                val items = List(size) { to -> PlanItem("p$to", "Item $to", after = edges.mapIndexedNotNull { bit, (from, end) ->
                    "p$from".takeIf { end == to && mask and (1 shl bit) != 0 }
                }) }
                assertEquals(width, PlanAnalysis.report(plan(items)).width, "vertices=$size edges=$mask")
            }
        }
    }
}
