package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.logging.Level
import java.util.logging.Logger
import java.util.concurrent.locks.ReentrantLock

class WorkPlanStore(private val directory: Path, inbox: Path? = null,
    private val identity: (BoardActor) -> BoardActor = { it }, private val clock: () -> Long = System::currentTimeMillis,
    private val activity: (String) -> Long? = { null },
    private val resolveRefs: suspend (List<String>, Long) -> Map<String, List<String>> = { refs, _ -> refs.associateWith(AgentSourceScope::local) },
    private val cachedRefs: (List<String>) -> Map<String, List<String>> = { emptyMap() },
    private val messages: ((WorkPlan, WorkPlan?, BoardActor) -> Unit)? = null) : AutoCloseable {
    private val inbox = inbox?.let(::ThreadInbox)
    private val plans = ConcurrentHashMap<String, WorkPlan>()
    private val changed = MutableStateFlow(0L)
    val changes: StateFlow<Long> = changed.asStateFlow()

    private class StoreCall(val waiting: Boolean) {
        var notifications = 0
        val failures = ConcurrentLinkedQueue<Throwable>()
    }
    private data class Notification(val plan: WorkPlan, val previous: WorkPlan?, val by: BoardActor, val call: StoreCall)
    private val notifications = ArrayDeque<Notification>()
    private val caller = ThreadLocal<StoreCall>()
    private val delivery = ReentrantLock(true)
    private val delivering = ThreadLocal.withInitial { false }
    private val lifecycle = Any()
    private val wake = Semaphore(0)
    @Volatile private var closed = false
    private var readDelivery: Thread? = null

    private fun wakeDelivery() = synchronized(lifecycle) {
        check(!closed) { "Plan store is closed" }
        if (readDelivery == null) readDelivery = Thread({
            do {
                wake.acquire()
                wake.drainPermits()
                try { deliver() }
                catch (error: Throwable) {
                    logFailure(error)
                    if (pendingNotifications > 0) wake.release()
                }
            } while (!closed || pendingNotifications > 0)
        }, "WorkPlanStore-notifications").apply { isDaemon = true; start() }
        wake.release()
    }

    private fun logFailure(error: Throwable) {
        runCatching { Logger.getLogger(WorkPlanStore::class.java.name).log(Level.SEVERE, "Plan notification failed", error) }
    }

    override fun close() {
        val thread = synchronized(lifecycle) { closed = true; wake.release(); readDelivery }
        if (thread != Thread.currentThread() && !delivering.get()) thread?.join()
    }

    internal val pendingNotifications: Int get() = synchronized(this) { notifications.size }
    internal val deliveringNotification: Boolean get() = delivering.get()

    private fun <T> access(wait: Boolean = false, body: () -> T): T = call(wait) { synchronized(this) { body() } }

    private fun <T> call(wait: Boolean = false, body: () -> T): T {
        if (caller.get() != null) return body()
        val call = StoreCall(wait && !delivering.get())
        caller.set(call)
        var failure: Throwable? = null
        try {
            return body()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            caller.remove()
            if (!delivering.get()) {
                if (call.notifications > 0) {
                    if (call.waiting) deliver() else wakeDelivery()
                }
                call.failures.poll()?.let { error ->
                    call.failures.forEach(error::addSuppressed)
                    if (failure == null) throw error else failure.addSuppressed(error)
                }
            }
        }
    }

    private fun deliver() {
        check(!Thread.holdsLock(this))
        do {
            delivery.lock()
            try {
                delivering.set(true)
                while (true) {
                    val notification = synchronized(this) { notifications.removeFirstOrNull() } ?: break
                    try {
                        notify(notification.plan, notification.previous, notification.by)
                    } catch (error: Throwable) {
                        if (notification.call.waiting) notification.call.failures.add(error)
                        else logFailure(error)
                    }
                }
            } finally {
                delivering.remove()
                delivery.unlock()
            }
        } while (synchronized(this) { notifications.isNotEmpty() })
    }

    init {
        privateDirectory(directory)
        Files.list(directory).use { files -> files.filter { it.fileName.toString().endsWith(".json") }.toList() }.forEach { file ->
            runCatching { ConductorJson.decodeFromString(WorkPlan.serializer(), Files.readString(file)) }.getOrNull()?.let { plans[it.thread] = it }
        }
    }

    fun list(): List<PlanSummary> = access { expire(); plans.values.sortedByDescending { it.updatedAt }.map(WorkPlan::summary) }

    fun plan(thread: String): WorkPlan? = access { expire(); plans[thread]?.let(::identified) }

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

    fun read(thread: String, since: Long? = null, reader: BoardActor? = null): PlanRead = access {
        val plan = plan(thread) ?: error("$thread has no plan yet; its agent draws one with plan.write")
        PlanRead(plan, since?.let { plan.news(it, reader?.let(identity)) }.orEmpty())
    }

    fun write(thread: String, patch: PlanPatch, by: BoardActor): PlanApplied = access(true) {
        val now = clock()
        val current = plan(thread)
        val writer = identity(by)
        val applied = PlanRules.apply(current ?: PlanRules.start(thread, patch, writer, now), patch, writer, now)
        if (current == null && (applied.plan.items.isNotEmpty() || applied.refused.isEmpty()) || current != null && applied.plan != current) {
            return@access applied.copy(plan = identified(save(applied.plan, current, writer)))
        }
        applied
    }

    fun next(thread: String?, by: BoardActor, seat: String? = null, take: Boolean = false,
        item: String? = null, profile: (String) -> AgentProfile): PlanNext = call(take) {
        val deadlineNanos = System.nanoTime() + PlanRefResolver.WaitNanos
        require(item == null || thread != null && PlanRules.Key.matches(item)) { "Choosing an item requires a plan thread and a valid item key" }
        val actor = identity(by)
        require(!take || actor.kind == BoardActorKind.Agent && actor.agent != null) { "Taking work requires a canonical agent identity" }
        val resolved = HashMap<String, List<String>>()
        fun resolveMissing() {
            val missing = access { scopeRefs(thread, by, seat, item, profile).filterNot(resolved::containsKey) }
            if (missing.isNotEmpty()) {
                val found = if (deliveringNotification) cachedRefs(missing) else runBlocking { resolveRefs(missing, deadlineNanos) }
                missing.forEach { resolved[it] = found[it] ?: AgentSourceScope.local(it) }
            }
        }
        repeat(2) {
            resolveMissing()
            val answer = access(take) {
                if (scopeRefs(thread, by, seat, item, profile).any { it !in resolved }) null
                else nextResolved(thread, by, seat, take, item, profile, resolved)
            }
            if (answer != null) return@call answer
        }
        resolveMissing()
        nextResolved(thread, by, seat, take, item, profile, resolved)
    }

    private fun scopeRefs(thread: String?, by: BoardActor, seat: String?, item: String?, profile: (String) -> AgentProfile): List<String> {
        expire()
        val every = plans.values.sortedBy { it.thread }.map(::identified)
        val all = if (thread == null) every else listOf(requireNotNull(every.find { it.thread == thread }) { "$thread has no plan yet; its agent draws one with plan.write" })
        val busy = every.flatMap { plan -> plan.leaves().filter { it.status == PlanStatus.Doing && it.kind != PlanKind.Component }.map { plan to it } }
        val ready = eligible(all, identity(by), seat, item, profile).map { candidate -> all.single { it.thread == candidate.thread } to candidate.item }
        return (busy + ready).flatMap { (plan, item) -> AgentSourceScope.refs(plan, item) }.distinct()
    }

    private fun nextResolved(thread: String?, by: BoardActor, seat: String?, take: Boolean,
        item: String?, profile: (String) -> AgentProfile, resolved: Map<String, List<String>>): PlanNext = access(take) {
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
                    if (!sameItem || !insideSeat) return@access PlanNext(reason = reason)
                    if (!PlanSchedule.unblocked(all.single(), held.item)) return@access PlanNext(reason = "$item is leased to you but its dependencies are not complete")
                }
                return@access PlanNext(held, reason)
            }
        }
        fun footprint(plan: WorkPlan, item: PlanItem): List<String> = AgentSourceScope.footprint(plan, item, resolved)
        val busy = every.flatMap { plan -> plan.leaves().filter { it.status == PlanStatus.Doing && it.kind != PlanKind.Component && it.holder?.same(actor) != true }.map { footprint(plan, it) } }
        val candidates = eligible(all, actor, seat, item, profile).filter { candidate ->
            val plan = all.single { it.thread == candidate.thread }
            busy.none { AgentSourceScope.conflicts(footprint(plan, candidate.item), it) }
        }
        val candidate = candidates.minWithOrNull(compareByDescending<PlanCandidate> { it.priority }.thenByDescending { it.unblocks }.thenBy { it.age })
        if (!take || candidate == null) return@access PlanNext(candidate, if (candidate == null) "No ready item matches this agent, its seat, profile and free files" else "")
        val current = read(candidate.thread).plan
        val now = clock()
        val applied = PlanRules.apply(current, PlanPatch(base = current.revision, seat = seat,
            ops = listOf(PlanOp.Status(candidate.item.id, PlanStatus.Doing, reason = ""))), actor, now)
        require(applied.refused.isEmpty()) { applied.refused.joinToString { it.reason } }
        val leased = applied.plan.copy(items = applied.plan.items.map {
            if (it.id == candidate.item.id) it.copy(lease = PlanLease(actor, now, now + PlanLease.DurationMillis)) else it
        })
        val saved = identified(save(leased, current, actor))
        PlanNext(candidate.copy(revision = saved.revision, item = requireNotNull(saved.item(candidate.item.id))))
    }

    private fun eligible(all: List<WorkPlan>, actor: BoardActor, seat: String?, item: String?, profile: (String) -> AgentProfile): List<PlanCandidate> {
        val seated = all.any { plan -> plan.seats.any { it.heldBy(actor) } }
        return all.flatMap { plan ->
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
                    (chosen == null || (chosen.planKinds.isEmpty() || item.kind in chosen.planKinds) &&
                        (chosen.planRefs.isEmpty() || chosen.planRefs.any { wanted -> item.refs.any { it == wanted || it.startsWith("$wanted/") } }))
            }
        }
    }

    private fun expire() {
        val now = clock()
        requeue("Lease expired after quiet activity") { item -> item.lease?.let { it.until(PlanIdentity.agent(it.by)?.let(activity)) <= now } == true }
    }

    fun release(agent: String, note: String): List<String> = access(true) { requeue(note) { it.holder?.let(PlanIdentity::agent) == agent } }

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

    fun attach(thread: String, seat: (WorkPlan) -> PlanSeat): Pair<WorkPlan, PlanSeat> = attachAdmitted(thread, seat) { _, _ -> null }

    internal fun attachAdmitted(thread: String, seat: (WorkPlan) -> PlanSeat,
        admit: (WorkPlan, PlanSeat) -> String?): Pair<WorkPlan, PlanSeat> = access(true) {
        val plan = plans[thread] ?: error("$thread has no plan to attach an agent to.")
        val made = seat(plan)
        val proposed = PlanRules.attach(plan, made, clock())
        val admitted = made.copy(run = admit(proposed, made) ?: made.run)
        val next = proposed.copy(seats = proposed.seats.map { if (it.id == admitted.id) admitted else it })
        save(next, plan, admitted.by)
        next to admitted
    }

    fun bind(thread: String, seat: String, run: String): WorkPlan = access(true) {
        val plan = plans[thread] ?: error("$thread has no plan")
        requireNotNull(plan.seat(seat)) { "$seat is not an active seat on this plan" }
        plan.copy(seats = plan.seats.map { if (it.id == seat) it.copy(run = run) else it }).also { save(it, plan, null) }
    }

    fun archive(thread: String, by: BoardActor): List<String> = access(true) {
        val writer = identity(by)
        val current = plan(thread) ?: error("$thread has no plan")
        require(writer.kind == BoardActorKind.Person || writer.session == thread || thread.startsWith(BoardRefs.Hangar)) {
            "Only the thread's agent or a person archives closed items on $thread"
        }
        val parents = current.items.mapNotNullTo(HashSet()) { it.parent }
        val closed = setOf(PlanStatus.Done, PlanStatus.Dropped)
        val archivable = current.items.filter { item ->
            item.id !in parents && item.kind != PlanKind.Component && item.status in closed &&
                current.items.none { other -> item.id in other.after && other.status !in closed }
        }
        if (archivable.isEmpty()) return@access emptyList()
        val archiveDir = directory.resolve("archive").also(::privateDirectory)
        val archiveFile = archiveDir.resolve("${thread.replace(':', '-')}.json")
        val existing = if (Files.isRegularFile(archiveFile))
            runCatching { ConductorJson.decodeFromString(WorkPlan.serializer(), Files.readString(archiveFile)) }.getOrNull()
        else null
        val archivedIds = archivable.mapTo(HashSet()) { it.id }
        val now = clock()
        val mergedArchive = (existing ?: current.copy(items = emptyList(), changes = emptyList())).let { base ->
            val kept = base.items.filterNot { it.id in archivedIds } + archivable
            base.copy(items = kept, revision = current.revision + 1, updatedAt = now)
        }
        atomicWrite(archiveFile, ConductorJson.encodeToString(WorkPlan.serializer(), mergedArchive))
        val revision = current.revision + 1
        val summary = "Archived ${archivable.size} closed ${if (archivable.size == 1) "item" else "items"}: ${archivable.take(10).joinToString { it.id }}"
        val next = current.copy(
            items = current.items.filterNot { it.id in archivedIds }.map { item ->
                item.copy(after = item.after - archivedIds, relations = item.relations.filterNot { it.to in archivedIds })
            },
            revision = revision,
            updatedAt = now,
            changes = (current.changes + PlanChange(revision, now, writer, listOf(summary))).takeLast(PlanRules.MaxChanges),
        )
        save(next, current, writer)
        archivable.map { it.id }
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
        require(text.length <= MaxChars) { "This plan has reached its ${MaxChars / (1024 * 1024)} MB limit" }
        atomicWrite(file(next.thread), text)
        plans[next.thread] = stored
        changed.value += 1
        if (by != null) {
            val call = requireNotNull(caller.get())
            call.notifications++
            notifications.addLast(Notification(stored, previous, by, call))
        }
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
        const val MaxChars = 8 * 1024 * 1024
    }

    private fun agentSession(agent: String?): String? {
        val session = agent?.removePrefix("agent:") ?: return null
        return session.takeIf { "agent:$it" == agent && BoardRefs.pattern.matches(it) }
    }
}
