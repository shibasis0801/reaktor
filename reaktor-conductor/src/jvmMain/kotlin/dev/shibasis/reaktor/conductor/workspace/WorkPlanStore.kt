package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path

class WorkPlanStore(private val directory: Path, inbox: Path? = null,
    private val identity: (BoardActor) -> BoardActor = { it }, private val clock: () -> Long = System::currentTimeMillis,
    private val activity: (String) -> Long? = { null },
    private val messages: ((WorkPlan, WorkPlan?, BoardActor) -> Unit)? = null) {
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
            items = logged(plan, plan).items.map { it.copy(by = it.by?.let(::actor), lease = it.lease?.let { lease -> lease.copy(by = actor(lease.by), expiresAt = lease.until(PlanIdentity.agent(lease.by)?.let(activity))) }) },
            seats = plan.seats.map { seat ->
                val agent = (seat.session ?: seat.run?.let { "${BoardRefs.Hangar}$it" })?.let { actor(BoardActor(BoardActorKind.Agent, seat.profile, it)).agent }
                seat.copy(agent = agent ?: seat.agent)
            },
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
        if (current == null && (applied.plan.items.isNotEmpty() || applied.refused.isEmpty()) || current != null && applied.plan != current) {
            return applied.copy(plan = identified(save(applied.plan, current, writer)))
        }
        return applied
    }

    @Synchronized fun next(thread: String?, by: BoardActor, seat: String? = null, take: Boolean = false,
        item: String? = null, profile: (String) -> AgentProfile): PlanNext {
        val actor = identity(by)
        require(item == null || thread != null && PlanRules.Key.matches(item)) { "Choosing an item requires a plan thread and a valid item key" }
        expire()
        require(!take || actor.kind == BoardActorKind.Agent && actor.agent != null) { "Taking work requires a canonical agent identity" }
        val every = plans.keys.sorted().mapNotNull(::plan)
        val all = if (thread == null) every else listOf(read(thread).plan)
        if (item != null) require(all.single().item(item) != null) { "$item is not on $thread" }
        val requestedSeat = if (seat != null && thread != null) {
            requireNotNull(all.single().seat(seat)?.takeIf { it.heldBy(actor) }) { "$seat is not held by this agent" }
        } else null
        if (take) {
            val held = every.asSequence().flatMap { PlanSchedule.ranked(it).asSequence() }
                .firstOrNull { it.item.status == PlanStatus.Doing && it.item.lease?.by?.same(actor) == true }
            if (held != null) {
                val reason = "This agent already holds ${held.thread}#${held.item.id}"
                if (item != null) {
                    val sameItem = held.thread == thread && held.item.id == item
                    val insideSeat = requestedSeat == null || requestedSeat.items.any { item in all.single().under(it) }
                    if (!sameItem || !insideSeat) return PlanNext(reason = reason)
                    if (!PlanSchedule.unblocked(all.single(), held.item)) return PlanNext(reason = "$item is leased to you but its dependencies are not complete")
                }
                return PlanNext(held, reason)
            }
        }
        fun footprint(plan: WorkPlan, item: PlanItem): List<String> =
            if (item.refs.any { it.startsWith("file:") }) item.refs
            else generateSequence(item) { it.parent?.let(plan::item) }.flatMap { it.refs }.distinct().toList()
        val busy = every.flatMap { plan -> plan.leaves().filter { it.status == PlanStatus.Doing && it.kind != PlanKind.Component && it.holder?.same(actor) != true }.map { footprint(plan, it) } }
        val seated = all.any { plan -> plan.seats.any { it.heldBy(actor) } }
        val candidates = all.flatMap { plan ->
            val mine = plan.seats.filter { it.heldBy(actor) && (seat == null || it.id == seat) }
            if ((seated || seat != null) && mine.isEmpty()) return@flatMap emptyList()
            val covered = mine.flatMapTo(HashSet()) { it.items.flatMap(plan::under) }
            PlanSchedule.ready(plan).filter { candidate ->
                if (item != null && candidate.item.id != item) return@filter false
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
        val saved = identified(save(leased, current, actor))
        return PlanNext(candidate.copy(revision = saved.revision, item = requireNotNull(saved.item(candidate.item.id))))
    }

    private fun expire() {
        val now = clock()
        requeue("Lease expired after quiet activity") { item -> item.lease?.let { it.until(PlanIdentity.agent(it.by)?.let(activity)) <= now } == true }
    }

    @Synchronized fun release(agent: String, note: String): List<String> = requeue(note) { it.holder?.let(PlanIdentity::agent) == agent }

    private fun requeue(note: String, pick: (PlanItem) -> Boolean): List<String> {
        val now = clock()
        val scheduler = BoardActor(BoardActorKind.Person, "Scheduler")
        return plans.values.toList().flatMap { initial ->
            var plan = initial
            initial.items.filter { it.status == PlanStatus.Doing && pick(it) }.chunked(PlanRules.MaxOps).flatMap { batch ->
                val reason = ("$note: " + batch.joinToString { it.id }).take(900)
                val applied = PlanRules.apply(plan, PlanPatch(ops = batch.map { PlanOp.Status(it.id, PlanStatus.Todo, reason = reason) }, message = reason), scheduler, now)
                plan = save(applied.plan, plan, scheduler)
                batch.map { "${initial.thread}#${it.id}" }
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
        requireNotNull(plan.seat(seat)) { "$seat is not an active seat on this plan" }
        return plan.copy(seats = plan.seats.map { if (it.id == seat) it.copy(run = run) else it }).also { save(it, plan, null) }
    }

    private fun logged(next: WorkPlan, previous: WorkPlan?): WorkPlan {
        val now = clock()
        val before = previous?.items.orEmpty().associateBy { it.id }
        return next.copy(items = next.items.map { item ->
            val old = before[item.id]
            val logs = old?.workLogs.orEmpty().toMutableList()
            fun record(lease: PlanLease, end: Long) {
                val session = lease.by.session ?: return
                val span = PlanWorkLog(session, lease.takenAt, end)
                logs.removeAll { it.session == session && it.startedAt == lease.takenAt }
                if (end > lease.takenAt) logs += span
            }
            fun until(lease: PlanLease) = lease.until(PlanIdentity.agent(lease.by)?.let(activity))
            old?.lease?.let { lease ->
                val same = item.lease?.let { it.takenAt == lease.takenAt && it.by.session == lease.by.session } == true
                record(lease, if (same) until(lease) else minOf(now, until(lease)))
            }
            item.lease?.let { record(it, until(it)) }
            item.copy(workLogs = logs.sortedBy { it.startedAt }.takeLast(32))
        })
    }

    private fun save(next: WorkPlan, previous: WorkPlan?, by: BoardActor?): WorkPlan {
        val stored = stored(logged(next, previous))
        val text = ConductorJson.encodeToString(WorkPlan.serializer(), stored)
        require(text.length <= MaxChars) { "A plan is at most ${MaxChars / 1024} KB; group finished parts under one Done item or start a new thread" }
        atomicWrite(file(next.thread), text)
        plans[next.thread] = stored
        changed.value += 1
        if (by != null) notify(stored, previous, by)
        return stored
    }

    private fun stored(plan: WorkPlan): WorkPlan {
        val compact = HashMap<BoardActor, BoardActor>()
        fun bare(actor: BoardActor): BoardActor = compact.getOrPut(actor) {
            PlanIdentity.bare(actor).takeIf { it != actor && identity(it) == identity(actor) } ?: actor
        }
        val written = plans[plan.thread]?.changes.orEmpty().associateBy { it.revision to it.summary }
        return plan.copy(
            items = plan.items.map { item -> item.copy(by = item.by?.let(::bare), lease = item.lease?.let { it.copy(by = bare(it.by)) }) },
            changes = plan.changes.map { change -> written[change.revision to change.summary] ?: change.copy(by = PlanIdentity.history(change.by)) },
        )
    }

    private fun notify(plan: WorkPlan, previous: WorkPlan?, by: BoardActor) {
        messages?.let { it(plan, previous, by); return }
        val inbox = inbox ?: return
        val fresh = plan.changes.filter { it.revision > (previous?.revision ?: 0) && it.by.same(by) }
        if (fresh.isEmpty()) return
        val writers = listOfNotNull(by.session, agentSession(by.agent), plan.thread.takeIf { by.kind == BoardActorKind.Agent })
        val readers = (listOf(plan.thread) + plan.seats.flatMap { listOfNotNull(it.session, agentSession(it.agent)) } +
            plan.changes.flatMap { listOfNotNull(it.by.session, agentSession(it.by.agent)) })
            .filter { it !in writers &&
                (it.startsWith(BoardRefs.Hangar) || it.startsWith(BoardRefs.Claude) || it.startsWith(BoardRefs.Codex) || it.startsWith(BoardRefs.Antigravity)) }
            .distinct()
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

    private fun agentSession(agent: String?): String? {
        val session = agent?.removePrefix("agent:") ?: return null
        return session.takeIf { "agent:$it" == agent && BoardRefs.pattern.matches(it) }
    }
}
