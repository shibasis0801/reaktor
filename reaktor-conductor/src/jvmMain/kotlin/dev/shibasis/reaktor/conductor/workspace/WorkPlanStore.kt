package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path

class WorkPlanStore(private val directory: Path, inbox: Path? = null,
    private val identity: (BoardActor) -> BoardActor = { it }, private val clock: () -> Long = System::currentTimeMillis) {
    private val inbox = inbox?.let(::ThreadInbox)
    private val plans = HashMap<String, WorkPlan>()
    private val changed = MutableStateFlow(0L)
    val changes: StateFlow<Long> = changed.asStateFlow()

    init {
        privateDirectory(directory)
        Files.list(directory).use { files -> files.filter { it.fileName.toString().endsWith(".json") }.toList() }.forEach { file ->
            runCatching { ConductorJson.decodeFromString(WorkPlan.serializer(), Files.readString(file)) }.getOrNull()?.let { plans[it.thread] = it }
        }
    }

    @Synchronized fun list(): List<PlanSummary> = plans.values.sortedByDescending { it.updatedAt }.map(WorkPlan::summary)

    @Synchronized fun plan(thread: String): WorkPlan? = plans[thread]?.let(::identified)

    private fun identified(plan: WorkPlan): WorkPlan {
        val actors = HashMap<BoardActor, BoardActor>()
        fun actor(value: BoardActor) = actors.getOrPut(value) { identity(value) }
        return plan.copy(
            items = plan.items.map { it.copy(by = it.by?.let(::actor)) },
            seats = plan.seats.map { seat -> seat.copy(agent = (seat.session ?: seat.run?.let { "${BoardRefs.Hangar}$it" })?.let { actor(BoardActor(BoardActorKind.Agent, seat.profile, it)).agent }) },
            changes = plan.changes.map { it.copy(by = actor(it.by)) },
        )
    }

    @Synchronized fun read(thread: String, since: Long? = null, reader: BoardActor? = null): PlanRead {
        val plan = plan(thread) ?: error("$thread has no plan yet; its agent draws one with plan.write")
        return PlanRead(plan, since?.let { plan.news(it, reader?.let(identity)) }.orEmpty())
    }

    @Synchronized fun write(thread: String, patch: PlanPatch, by: BoardActor): PlanApplied {
        val now = clock()
        val current = plan(thread)
        val writer = identity(by)
        val applied = PlanRules.apply(current ?: PlanRules.start(thread, patch, writer, now), patch, writer, now)
        if (current == null && (applied.plan.items.isNotEmpty() || applied.refused.isEmpty()) || current != null && applied.plan != current) save(applied.plan, current, writer)
        return applied
    }

    @Synchronized fun next(thread: String?, by: BoardActor, seat: String? = null, profile: (String) -> AgentProfile): PlanNext {
        val actor = identity(by)
        val all = if (thread == null) plans.keys.sorted().mapNotNull(::plan) else listOf(read(thread).plan)
        val seated = all.any { plan -> plan.seats.any { it.heldBy(actor) } }
        val candidates = all.flatMap { plan ->
            val mine = plan.seats.filter { it.heldBy(actor) && (seat == null || it.id == seat) }
            if (seat != null && thread != null) require(mine.isNotEmpty()) { "$seat is not held by this agent" }
            if ((seated || seat != null) && mine.isEmpty()) return@flatMap emptyList()
            val covered = mine.flatMapTo(HashSet()) { it.items.flatMap(plan::under) }
            PlanSchedule.ready(plan).filter { candidate ->
                val item = candidate.item
                val owner = generateSequence(item) { it.parent?.let(plan::item) }.mapNotNull { it.seat?.let(plan::seat) }.firstOrNull()
                val chosen = owner?.takeIf { it.heldBy(actor) }?.let { profile(it.profile) }
                (actor.kind == BoardActorKind.Person || item.kind != PlanKind.Decision) &&
                    (owner == null || owner.heldBy(actor)) && (mine.isEmpty() || item.id in covered) &&
                    (chosen == null || (chosen.planKinds.isEmpty() || item.kind in chosen.planKinds) &&
                        (chosen.planRefs.isEmpty() || chosen.planRefs.any { wanted -> item.refs.any { it == wanted || it.startsWith("$wanted/") } }))
            }
        }
        val candidate = candidates.minWithOrNull(compareByDescending<PlanCandidate> { it.priority }.thenByDescending { it.unblocks }.thenBy { it.age })
        return PlanNext(candidate, if (candidate == null) "No ready item matches this agent, its seat and profile" else "")
    }

    @Synchronized fun attach(thread: String, seat: (WorkPlan) -> PlanSeat): Pair<WorkPlan, PlanSeat> {
        val plan = plans[thread] ?: error("$thread has no plan to attach an agent to")
        val made = seat(plan)
        val next = PlanRules.attach(plan, made, clock())
        save(next, plan, made.by)
        return next to made
    }

    @Synchronized fun bind(thread: String, seat: String, run: String): WorkPlan {
        val plan = plans[thread] ?: error("$thread has no plan")
        return plan.copy(seats = plan.seats.map { if (it.id == seat) it.copy(run = run) else it }).also { save(it, plan, null) }
    }

    private fun save(next: WorkPlan, previous: WorkPlan?, by: BoardActor?) {
        val text = ConductorJson.encodeToString(WorkPlan.serializer(), next)
        require(text.length <= MaxChars) { "A plan is at most ${MaxChars / 1024} KB; group finished parts under one Done item or start a new thread" }
        atomicWrite(file(next.thread), text)
        plans[next.thread] = next
        changed.value += 1
        if (by != null) notify(next, previous, by)
    }

    private fun notify(plan: WorkPlan, previous: WorkPlan?, by: BoardActor) {
        val inbox = inbox ?: return
        val fresh = plan.changes.filter { it.revision > (previous?.revision ?: 0) && it.by.same(by) }
        if (fresh.isEmpty()) return
        val writer = by.session ?: plan.thread.takeIf { by.kind == BoardActorKind.Agent }
        val readers = (listOf(plan.thread) + plan.seats.mapNotNull { it.session } + plan.changes.mapNotNull { it.by.session })
            .filter { (it.startsWith(BoardRefs.Claude) || it.startsWith(BoardRefs.Codex)) && it != writer }.distinct()
        val lines = fresh.joinToString("") { change -> "- r${change.revision} · ${change.by.name}: ${change.summary.joinToString("; ")}\n" }
        readers.forEach { reader ->
            inbox.write(reader, lines, opening = "Your Reaktor plan “${plan.title}” changed. Read {operation: \"plan.read\", arguments: {thread: \"${plan.thread}\", " +
                "since: ${(previous?.revision ?: 0)}}} and follow the changes before you go on:\n")
        }
    }

    private fun file(thread: String): Path = directory.resolve("${thread.replace(':', '-')}.json")

    companion object {
        const val MaxChars = 512 * 1024
    }
}
