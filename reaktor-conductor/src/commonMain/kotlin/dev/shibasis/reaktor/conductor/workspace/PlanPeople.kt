package dev.shibasis.reaktor.conductor.workspace

object PlanPeople {
    const val Person = "person:"
    const val Agent = "agent:"

    fun ref(actor: BoardActor): String = (if (actor.kind == BoardActorKind.Agent) Agent else Person) + actor.name.trim()

    fun actor(ref: String): BoardActor? = when {
        ref.startsWith(Person) -> ref.removePrefix(Person).trim().takeIf(String::isNotEmpty)?.let { BoardActor(BoardActorKind.Person, it) }
        ref.startsWith(Agent) -> ref.removePrefix(Agent).trim().takeIf(String::isNotEmpty)?.let { BoardActor(BoardActorKind.Agent, it) }
        else -> null
    }

    fun of(item: PlanItem): List<BoardActor> = item.refs.mapNotNull(::actor)

    fun with(item: PlanItem, actor: BoardActor): List<String> = item.refs.filterNot { it == ref(actor) } + ref(actor)

    fun without(item: PlanItem, actor: BoardActor): List<String> = item.refs.filterNot { it == ref(actor) }

    fun everyone(plan: WorkPlan): List<BoardActor> = plan.items.flatMap(::of).distinctBy(::ref)

    fun names(people: List<BoardActor>): String? = people.takeIf { it.isNotEmpty() }
        ?.joinToString(", ") { if (it.kind == BoardActorKind.Agent) "${it.name} (agent)" else it.name }
}
