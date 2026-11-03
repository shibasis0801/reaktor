package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.Serializable

@Serializable
enum class AssignmentSource { Seat, Claim }

@Serializable
data class Assignment(val thread: String, val item: String, val profile: String?, val agent: String?, val source: AssignmentSource)

fun interface AssignmentPolicy {
    fun assign(team: AgentTeam, plans: Collection<WorkPlan>, claims: Map<String, PlanClaims>): List<Assignment>

    companion object {
        val Explicit = AssignmentPolicy { _, plans, claims ->
            plans.flatMap { plan ->
                val held = claims[plan.thread] ?: PlanClaims()
                val covering = plan.seats.filter { it.retired == null }
                    .flatMap { seat -> seat.items.flatMap(plan::under).map { it to seat } }
                    .groupBy({ it.first }, { it.second })
                plan.items.filter { it.status == PlanStatus.Todo || it.status == PlanStatus.Doing }.mapNotNull { item ->
                    val seat = item.seat?.let(plan::seat) ?: covering[item.id]?.maxByOrNull { it.at }
                    val claim = held.of(item).takeIf { it.state.active }
                    when {
                        seat != null -> Assignment(plan.thread, item.id, seat.profile, seat.agent, AssignmentSource.Seat)
                        claim?.holder != null -> Assignment(plan.thread, item.id, claim.holder.profile, PlanIdentity.agent(claim.holder), AssignmentSource.Claim)
                        else -> null
                    }
                }
            }
        }
    }
}
