package dev.shibasis.reaktor.work.store

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.db.DatabaseEvent
import dev.shibasis.reaktor.db.ObjectChange
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.io.serialization.TextSerializer
import dev.shibasis.reaktor.work.run.RunRecord
import dev.shibasis.reaktor.work.run.WorkScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

class ObjectWorkStore(
    private val database: ObjectDatabase,
    private val retention: Duration = 7.days,
    private val clock: Clock = Clock.System,
) : WorkStore {
    private val codec = database.objectSerializer as? TextSerializer
        ?: throw IllegalArgumentException("ObjectWorkStore needs a text database with conditional writes")

    init { require(!retention.isNegative()) { "Journal retention cannot be negative" } }

    override suspend fun admit(record: RunRecord): Admission {
        if (database.compareAndSet(runStore(record.scope), record.id, null, record, RunRecord.serializer())) {
            return Admission.Accepted(record, existing = false)
        }
        val existing = get(record.scope, record.id) ?: error("Admitted run '${record.id}' disappeared")
        return if (existing.sameWork(record)) Admission.Accepted(existing, existing = true) else Admission.Conflict(record.id)
    }

    override suspend fun get(scope: WorkScope, id: String): RunRecord? =
        database.get(runStore(scope), id, RunRecord::class, RunRecord.serializer())?.value?.also {
            check(it.scope == scope && it.id == id) { "Run '$id' is stored under another scope or key" }
        }

    override suspend fun replace(before: RunRecord, after: RunRecord): Boolean {
        require(after.scope == before.scope && after.id == before.id && after.revision == before.revision + 1) {
            "A replacement must keep the run's scope and id and advance its revision by one"
        }
        return database.compareAndSet(runStore(before.scope), before.id, before, after, RunRecord.serializer())
    }

    override suspend fun due(scope: WorkScope, nowMillis: Long, limit: Int): List<RunRecord> {
        require(limit in 1..256)
        project(scope)
        val due = mutableListOf<RunRecord>()
        for (hint in hints(scope).filter { it.atMillis <= nowMillis }.sortedWith(compareBy({ it.atMillis }, { it.id }))) {
            if (due.size == limit) break
            val record = readable { get(scope, hint.id) }
            val wake = record?.nextWakeAtMillis
            when {
                record == null || !record.open || wake == null -> retire(scope, hint.id)
                wake <= nowMillis -> due += record
                else -> offer(scope, record)
            }
        }
        return due
    }

    override suspend fun nextWake(scope: WorkScope): Long? {
        project(scope)
        return hints(scope).minOfOrNull { it.atMillis }
    }

    override suspend fun page(scope: WorkScope, after: String?, limit: Int, include: (RunRecord) -> Boolean): RunPage {
        require(limit in 1..256)
        val unreadable = mutableListOf<String>()
        val runs = database.exportRaw(runStore(scope)).mapNotNull { row ->
            readable { decode(row.payload).also { check(it.scope == scope && it.id == row.key) } }.also { if (it == null) unreadable += row.key }
        }.filter { (after == null || it.id > after) && include(it) }.sortedBy { it.id }
        val page = runs.take(limit)
        return RunPage(page, unreadable.sorted(), page.lastOrNull()?.id?.takeIf { runs.size > limit })
    }

    override fun observe(scope: WorkScope, id: String): Flow<RunRecord?> = touches(runStore(scope), id).map { get(scope, id) }

    override fun changes(scope: WorkScope): Flow<Unit> = touches(runStore(scope), null)

    private fun touches(store: String, key: String?): Flow<Unit> = database.events
        .onSubscription { emit(DatabaseEvent.Invalidated(store, key.orEmpty())) }
        .filter { event ->
            when (event) {
                is DatabaseEvent.Invalidated -> event.storeName == store && (key == null || event.key == key)
                is DatabaseEvent.Put<*> -> event.storeName == store && (key == null || event.key == key)
                is DatabaseEvent.Delete -> event.storeName == store && (key == null || event.key == key)
                is DatabaseEvent.Clear -> event.storeName == store
                is DatabaseEvent.ClearAll -> true
                else -> false
            }
        }
        .map { }

    override suspend fun history(scope: WorkScope, afterSequence: Long, limit: Int): List<JournalEvent> =
        journal(scope, afterSequence, limit).map { change ->
            val record = change.payload?.let { payload -> readable { decode(payload).takeIf { it.scope == scope && it.id == change.key } } }
            JournalEvent(change.sequence, change.key, record, change.atMillis, unreadable = change.payload != null && record == null)
        }

    override suspend fun events(scope: WorkScope, consumer: String, limit: Int): JournalBatch {
        require(consumer.isNotBlank() && consumer.length <= 256 && !consumer.startsWith(ReservedConsumers) && limit in 1..256)
        val cursor = cursor(scope, consumer)
        return JournalBatch(this, scope, consumer, cursor, history(scope, cursor ?: 0, limit))
    }

    override suspend fun acknowledge(batch: JournalBatch): Boolean {
        require(batch.owner === this) { "This journal batch belongs to another store" }
        val sequence = batch.events.lastOrNull()?.sequence ?: return true
        return database.compareAndSet(cursorStore(batch.scope), batch.consumer, batch.previous?.let(::JournalCursor),
            JournalCursor(sequence), JournalCursor.serializer()).also { moved -> if (moved) prune(batch.scope) }
    }

    private suspend fun project(scope: WorkScope) {
        var advanced = false
        while (true) {
            val cursor = cursor(scope, DueConsumer)
            val changes = journal(scope, cursor ?: 0, ProjectionPage)
            if (changes.isEmpty()) break
            changes.associateBy { it.key }.values.forEach { change ->
                val record = change.payload?.let { payload -> readable { decode(payload) } }
                if (record != null && record.open && record.nextWakeAtMillis != null) offer(scope, record) else retire(scope, change.key)
            }
            advanced = database.compareAndSet(cursorStore(scope), DueConsumer, cursor?.let(::JournalCursor),
                JournalCursor(changes.last().sequence), JournalCursor.serializer()) || advanced
            if (changes.size < ProjectionPage) break
        }
        if (advanced) prune(scope)
    }

    private suspend fun prune(scope: WorkScope) {
        if (retention.isInfinite()) return
        try {
            val slowest = database.getAll(cursorStore(scope), JournalCursor::class, JournalCursor.serializer()).minOfOrNull { it.value.sequence } ?: return
            database.pruneChanges(runStore(scope), slowest, clock.now().toEpochMilliseconds() - retention.inWholeMilliseconds)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Logger.w("ReaktorWork") { "The journal kept its old changes; pruning failed: ${failure.message}" }
        }
    }

    private suspend fun offer(scope: WorkScope, record: RunRecord) {
        val at = record.nextWakeAtMillis ?: return retire(scope, record.id)
        val hint = DueHint(record.id, record.revision, at)
        repeat(MaxConflicts) {
            val existing = database.get(hintStore(scope), record.id, DueHint::class, DueHint.serializer())?.value
            if (existing != null && existing.revision >= record.revision) return
            if (database.compareAndSet(hintStore(scope), record.id, existing, hint, DueHint.serializer())) return
        }
    }

    private suspend fun retire(scope: WorkScope, id: String) {
        database.delete(hintStore(scope), id)
        val current = readable { get(scope, id) }
        if (current != null && current.open && current.nextWakeAtMillis != null) offer(scope, current)
    }

    private suspend fun hints(scope: WorkScope): List<DueHint> =
        database.getAll(hintStore(scope), DueHint::class, DueHint.serializer()).map { it.value }

    private suspend fun cursor(scope: WorkScope, consumer: String): Long? =
        database.get(cursorStore(scope), consumer, JournalCursor::class, JournalCursor.serializer())?.value?.sequence

    private suspend fun journal(scope: WorkScope, after: Long, limit: Int): List<ObjectChange> = try {
        database.readChanges(runStore(scope), after, limit)
    } catch (unsupported: UnsupportedOperationException) {
        throw IllegalStateException(JournalRequired, unsupported)
    } catch (unjournaled: IllegalArgumentException) {
        throw IllegalStateException(JournalRequired, unjournaled)
    }

    private fun decode(payload: String): RunRecord = codec.deserialize(RunRecord.serializer(), payload)

    private inline fun <T : Any> readable(read: () -> T?): T? = try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unreadable: Exception) {
        null
    }

    private fun runStore(scope: WorkScope) = "${JOURNAL_PREFIX}runs:${scope.key}"
    private fun hintStore(scope: WorkScope) = "__reaktor_work_due:${scope.key}"
    private fun cursorStore(scope: WorkScope) = "__reaktor_work_cursors:${scope.key}"

    companion object {
        const val JOURNAL_PREFIX: String = "__reaktor_work:"
    }
}

@Serializable
private data class DueHint(val id: String, val revision: Long, val atMillis: Long)

@Serializable
private data class JournalCursor(val sequence: Long)

private const val DueConsumer = "reaktor.work.due"
private const val ReservedConsumers = "reaktor.work."
private const val ProjectionPage = 256
private const val MaxConflicts = 16
private const val JournalRequired = "ObjectWorkStore needs a database that journals the stores starting with '__reaktor_work:'"
