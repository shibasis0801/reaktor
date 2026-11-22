package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.graph.core.Digraph

object PlanSignals {
    const val Ready = "ready"
    const val Critical = "critical"
    const val Follows = "follows"
    const val Slack = "slack"
    const val Unblocks = "unblocks"
    const val Bottleneck = "bottleneck"
    const val Cycle = "cycle"
    const val AlsoAfter = "also after"

    fun order(plan: WorkPlan): Digraph =
        Digraph(plan.items.map { it.id }, plan.items.flatMap { item -> item.after.filter { plan.item(it) != null }.map { it to item.id } })

    fun of(plan: WorkPlan): Map<String, Map<String, String>> {
        val report = PlanAnalysis.report(plan)
        val ready = report.ready.mapTo(HashSet()) { it.item.id }
        val ranked = PlanSchedule.ranked(plan).associateBy { it.item.id }
        val timings = report.timings.associateBy { it.item }
        val follows = report.criticalPath.zipWithNext().associate { (before, after) -> after to before }
        val bottleneck = ranked.values.filter { it.unblocks >= BottleneckFloor }.maxByOrNull { it.unblocks }?.item?.id
        val order = order(plan)
        val implied = order.implied.groupBy({ it.second }, { it.first })
        return plan.items.associate { item ->
            item.id to buildMap {
                if (item.id in ready) put(Ready, "true")
                val timing = timings[item.id]
                if (timing?.critical == true) put(Critical, "true")
                follows[item.id]?.let { put(Follows, it) }
                timing?.slack?.takeIf { it > 0 }?.let { put(Slack, it.toString()) }
                ranked[item.id]?.unblocks?.takeIf { it > 0 }?.let { put(Unblocks, it.toString()) }
                if (item.id == bottleneck) put(Bottleneck, "true")
                if (order.inCycle(item.id)) put(Cycle, "true")
                implied[item.id]?.let { put(AlsoAfter, it.joinToString(",")) }
            }
        }
    }

    private const val BottleneckFloor = 3
}
