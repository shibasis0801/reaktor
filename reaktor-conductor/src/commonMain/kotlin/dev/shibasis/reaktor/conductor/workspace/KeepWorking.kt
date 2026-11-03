package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.Serializable

@Serializable
data class KeepWorkingSeat(
    val thread: String,
    val plan: String,
    val paused: Boolean = false,
    val revision: Long = 1,
    val updatedAt: Long = 0,
    val fingerprint: String = "",
    val nudges: Int = 0,
    val lastNudge: String? = null,
    val lastNudgeAt: Long? = null,
)

@Serializable
data class KeepWorkingStatus(val seat: KeepWorkingSeat? = null)

@Serializable
data class KeepWorkingResult(val block: Boolean = false, val reason: String, val seat: KeepWorkingSeat? = null)

object KeepWorkingPolicy {
    private val stop = Regex("^\\s*(stop|pause|halt|hold|enough|that's enough|thats enough)\\b", RegexOption.IGNORE_CASE)

    fun ownerStopped(input: String): Boolean = stop.containsMatchIn(input)

    fun progress(plan: WorkPlan): String = plan.items.sortedBy { it.id }.joinToString("\n") {
        "${it.id}\t${plan.state(it.id)}\t${it.by?.agent.orEmpty()}\t${it.by?.session.orEmpty()}"
    }

    fun work(plan: WorkPlan, actor: BoardActor, next: PlanNext): List<PlanItem> {
        if (actor.kind != BoardActorKind.Agent || actor.agent == null || actor.session == null) return emptyList()
        val held = PlanSchedule.ranked(plan).filter {
            it.item.status == PlanStatus.Doing && it.item.kind != PlanKind.Decision &&
                it.item.holder?.same(actor) == true &&
                PlanSchedule.unblocked(plan, it.item)
        }.map { it.item }
        val candidate = next.candidate?.takeIf {
            it.thread == plan.thread && it.revision == plan.revision && it.item.status == PlanStatus.Todo &&
                it.item.kind != PlanKind.Decision && plan.item(it.item.id) == it.item && PlanSchedule.unblocked(plan, it.item)
        }?.item
        return (held + listOfNotNull(candidate)).distinctBy { it.id }
    }

    fun reason(plan: String, work: List<PlanItem>): String {
        val names = work.take(4).joinToString("; ") { "${it.id} “${it.title.take(100)}”" }
        return "[keep-working] Plan $plan still has work you can do. Next: $names. " +
            "Continue in this turn. Finish the items you hold, then claim the next ready item through plan.write before editing and carry it through its checks. " +
            "A queued or running check is not completion: wait for it or work another available item. " +
            "Keep the shared plan current. End when the owner stops you or no eligible work remains; explain any owner decision in a plan message."
    }
}
