package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path

class WorkPlanStore(private val directory: Path, inbox: Path? = null,
    private val identity: (BoardActor) -> BoardActor = { it }, private val clock: () -> Long = System::currentTimeMillis,
    private val activity: (String) -> Long? = { null }) {
    private val inbox = inbox?.let(::ThreadInbox)
    private val plans = java.util.concurrent.ConcurrentHashMap<String, WorkPlan>()
    private val changed = MutableStateFlow(0L)
    val changes: StateFlow<Long> = changed.asStateFlow()

    init {
        privateDirectory(directory)
        Files.list(directory).use { files -> files.filter { it.fileName.toString().endsWith(".json") }.toList() }.forEach { file ->
            runCatching { ConductorJson.decodeFromString(WorkPlan.serializer(), Files.readString(file)) }.getOrNull()?.let { plans[it.thread] = it }
        }
    }

    @Synchronized fun list(): List<PlanSummary> { expire(); return plans.values.sortedByDescending { it.updatedAt }.map(WorkPlan::summary) }

    @Synchronized fun plan(thread: String): WorkPlan? { expire(); return plans[thread]?.let(::identified) }

    fun candidate(reference: String, ready: Boolean = false): PlanCandidate? {
        require(reference.startsWith("plan:") && reference.count { it == '#' } == 1) { "Use plan:<thread>#<item> for planItem" }
        val thread = reference.removePrefix("plan:").substringBefore('#')
        require(BoardRefs.pattern.matches(thread)) { "planItem needs a canonical thread" }
        val plan = plans[thread] ?: error("$thread has no plan")
        val item = PlanSchedule.ranked(plan).firstOrNull { it.item.id == reference.substringAfter('#') }
        requireNotNull(item) { "$reference is not an open plan leaf" }
        return item.takeIf { !ready || PlanSchedule.unblocked(plan, it.item) }
    }

    private fun identified(plan: WorkPlan): WorkPlan {
        val actors = HashMap<BoardActor, BoardActor>()
        fun actor(value: BoardActor) = actors.getOrPut(value) { identity(value) }
        return plan.copy(
            items = plan.items.map { it.copy(by = it.by?.let(::actor), lease = it.lease?.let { lease -> lease.copy(by = actor(lease.by), expiresAt = lease.until(lease.by.agent?.let(activity))) }) },
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

    @Synchronized fun next(thread: String?, by: BoardActor, seat: String? = null, take: Boolean = false, profile: (String) -> AgentProfile): PlanNext {
        val actor = identity(by)
        expire()
        require(!take || actor.kind == BoardActorKind.Agent && actor.agent != null) { "Taking work requires a canonical agent identity" }
        val every = plans.keys.sorted().mapNotNull(::plan)
        val all = if (thread == null) every else listOf(read(thread).plan)
        if (take) {
            val held = every.asSequence().flatMap { PlanSchedule.ranked(it).asSequence() }
                .firstOrNull { it.item.status == PlanStatus.Doing && it.item.lease?.by?.same(actor) == true }
            if (held != null) return PlanNext(held, "This agent already holds this leased item")
        }
        fun footprint(plan: WorkPlan, item: PlanItem): List<String> =
            if (item.refs.any { it.startsWith("file:") }) item.refs
            else generateSequence(item) { it.parent?.let(plan::item) }.flatMap { it.refs }.distinct().toList()
        val busy = every.flatMap { plan -> plan.leaves().filter { it.status == PlanStatus.Doing && it.kind != PlanKind.Component && it.by?.same(actor) != true }.map { footprint(plan, it) } }
        val seated = all.any { plan -> plan.seats.any { it.heldBy(actor) } }
        val candidates = all.flatMap { plan ->
            val mine = plan.seats.filter { it.heldBy(actor) && (seat == null || it.id == seat) }
            if (seat != null && thread != null) require(mine.isNotEmpty()) { "$seat is not held by this agent" }
            if ((seated || seat != null) && mine.isEmpty()) return@flatMap emptyList()
            val covered = mine.flatMapTo(HashSet()) { it.items.flatMap(plan::under) }
            PlanSchedule.ready(plan).filter { candidate ->
                val item = candidate.item
                val owner = generateSequence(item) { it.parent?.let(plan::item) }.mapNotNull { it.seat?.let(plan::seat) }.firstOrNull()
                val chosen = (owner?.takeIf { it.heldBy(actor) }?.profile ?: actor.profile)?.let(profile)
                (actor.kind == BoardActorKind.Person || item.kind != PlanKind.Decision) &&
                    (owner == null || owner.heldBy(actor)) && (mine.isEmpty() || item.id in covered) &&
                      busy.none { PlanSchedule.conflicts(footprint(plan, item), it) } &&
                    (chosen == null || (chosen.planKinds.isEmpty() || item.kind in chosen.planKinds) &&
                        (chosen.planRefs.isEmpty() || chosen.planRefs.any { wanted -> item.refs.any { it == wanted || it.startsWith("$wanted/") } }))
            }
        }
        val candidate = candidates.minWithOrNull(compareByDescending<PlanCandidate> { it.priority }.thenByDescending { it.unblocks }.thenBy { it.age })
        if (!take || candidate == null) return PlanNext(candidate, if (candidate == null) "No ready item matches this agent, its seat, profile and free files" else "")
        val current = read(candidate.thread).plan
        val now = clock()
        val applied = PlanRules.apply(current, PlanPatch(base = current.revision, seat = seat,
            ops = listOf(PlanOp.Status(candidate.item.id, PlanStatus.Doing, reason = ""))), actor, now)
        require(applied.refused.isEmpty()) { applied.refused.joinToString { it.reason } }
        val leased = applied.plan.copy(items = applied.plan.items.map {
            if (it.id == candidate.item.id) it.copy(lease = PlanLease(actor, now, now + PlanLease.DurationMillis)) else it
        })
        save(leased, current, actor)
        return PlanNext(candidate.copy(revision = leased.revision, item = requireNotNull(leased.item(candidate.item.id))))
    }

    private fun expire() {
        val now = clock()
        val scheduler = BoardActor(BoardActorKind.Person, "Scheduler")
        plans.values.toList().forEach { initial ->
            val expired = initial.items.filter { it.status == PlanStatus.Doing && it.lease?.let { lease -> lease.until(lease.by.agent?.let(activity)) <= now } == true }
            var plan = initial
            expired.chunked(PlanRules.MaxOps).forEach { batch ->
                val note = ("Lease expired after quiet activity: " + batch.joinToString { it.id }).take(900)
                val applied = PlanRules.apply(plan, PlanPatch(ops = batch.map { PlanOp.Status(it.id, PlanStatus.Todo, reason = note) }, message = note), scheduler, now)
                save(applied.plan, plan, scheduler)
                plan = applied.plan
            }
        }
    }

    @Synchronized fun attach(thread: String, seat: (WorkPlan) -> PlanSeat): Pair<WorkPlan, PlanSeat> = attachAdmitted(thread, seat) { _, _ -> null }

    @Synchronized internal fun attachAdmitted(thread: String, seat: (WorkPlan) -> PlanSeat,
        admit: (WorkPlan, PlanSeat) -> String?): Pair<WorkPlan, PlanSeat> {
        val plan = plans[thread] ?: error("$thread has no plan to attach an agent to")
        val made = seat(plan)
        val proposed = PlanRules.attach(plan, made, clock())
        val admitted = made.copy(run = admit(proposed, made) ?: made.run)
        val next = proposed.copy(seats = proposed.seats.map { if (it.id == admitted.id) admitted else it })
        save(next, plan, admitted.by)
        return next to admitted
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
