package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.Serializable

@Serializable
enum class ClaimState { Unassigned, Claimed, Live, Stale, Completed, LastChangedBy, Unavailable;

    val active: Boolean get() = this == Claimed || this == Live || this == Stale || this == Unavailable
}

@Serializable
data class PlanClaim(
    val state: ClaimState,
    val holder: BoardActor? = null,
    val since: Long? = null,
    val lastActionAt: Long? = null,
    val profileRevision: Long? = null,
)

@Serializable
data class PlanClaims(val claims: Map<String, PlanClaim> = emptyMap(), val nextChangeAt: Long? = null) {
    fun of(item: PlanItem): PlanClaim = claims[item.id] ?: when (item.status) {
        PlanStatus.Done, PlanStatus.Dropped -> PlanClaim(ClaimState.LastChangedBy, item.by, item.changedAt, profileRevision = item.by?.profileRevision)
        else -> PlanClaim(ClaimState.Unassigned)
    }

    fun active(): PlanClaims = copy(claims = claims.filterValues { it.state.active })

    companion object {
        const val FreshMillis = AgentPresencePage.FreshMillis

        fun resolve(plan: WorkPlan, presence: AgentPresencePage?, now: Long, freshMillis: Long = FreshMillis): PlanClaims {
            val seen = presence?.agents.orEmpty().associateBy { it.actor.agent }
            val closers = closers(plan)
            val boundaries = mutableListOf<Long>()
            fun working(item: PlanItem): PlanClaim {
                val lease = item.lease
                val holder = lease?.by ?: item.by
                if (presence == null) return PlanClaim(ClaimState.Unavailable, holder, lease?.takenAt ?: item.changedAt)
                val agent = holder?.let(PlanIdentity::agent)?.let(seen::get)
                val until = lease?.until(agent?.lastSeen)
                if (until != null && until <= now) return PlanClaim(ClaimState.Stale, holder, until)
                until?.let(boundaries::add)
                if (agent?.live != true) return PlanClaim(ClaimState.Claimed, holder, lease?.takenAt ?: item.changedAt)
                if (!agent.connected) boundaries += agent.lastSeen + freshMillis
                return PlanClaim(ClaimState.Live, holder, lease?.takenAt ?: item.changedAt, agent.lastActionAt.takeIf { it > 0 })
            }
            val claims = plan.items.mapNotNull { item ->
                when (item.status) {
                    PlanStatus.Doing -> item.id to working(item)
                    PlanStatus.Done -> closers[item.id]?.let { item.id to PlanClaim(ClaimState.Completed, it.by, it.at, profileRevision = it.by.profileRevision) }
                    else -> null
                }
            }.toMap()
            return PlanClaims(claims, boundaries.filter { it > now }.minOrNull())
        }

        private fun closers(plan: WorkPlan): Map<String, PlanChange> {
            val marker = " → ${PlanStatus.Done.label}"
            val closed = HashMap<String, PlanChange>()
            plan.changes.sortedBy { it.revision }.forEach { change ->
                change.summary.filter { it.endsWith(marker) }.forEach { closed[it.removeSuffix(marker)] = change }
            }
            return closed
        }
    }
}
