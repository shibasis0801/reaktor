package dev.shibasis.reaktor.work

import dev.shibasis.reaktor.db.ObjectDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import dev.shibasis.reaktor.db.DatabaseEvent
import dev.shibasis.reaktor.io.serialization.TextSerializer

interface WorkStore {
    suspend fun admit(intent: WorkIntent, nowMillis: Long, dueAtMillis: Long = nowMillis): WorkAdmission
    suspend fun get(scope: WorkScope, id: String): WorkRecord?
    suspend fun list(scope: WorkScope): List<WorkRecord>
    suspend fun inspect(scope: WorkScope): WorkInspection
    suspend fun claimDue(scope: WorkScope, nowMillis: Long, owner: String, leaseMillis: Long, limit: Int): List<ClaimedWork>
    suspend fun renew(token: ClaimToken, nowMillis: Long, leaseMillis: Long): Boolean
    suspend fun checkpoint(token: ClaimToken, nowMillis: Long, value: String): Boolean
    suspend fun commit(token: ClaimToken, nowMillis: Long, result: WorkResult): Boolean
    suspend fun reconcileHandoff(scope: WorkScope, id: String, handoff: WorkHandoff, nowMillis: Long, result: WorkResult): Boolean
    suspend fun cancel(scope: WorkScope, id: String, nowMillis: Long): Boolean
    suspend fun control(scope: WorkScope, id: String, expectedRevision: Long, nowMillis: Long, action: WorkControlAction,
        result: WorkResult? = null): WorkMetadata? = throw UnsupportedOperationException("Conditional operator commands unavailable")
    fun observe(scope: WorkScope, id: String): Flow<WorkRecord?>
    suspend fun readEvents(scope: WorkScope, consumerId: String, limit: Int = 64): WorkEventBatch =
        throw UnsupportedOperationException("This WorkStore does not expose a retained event journal")
    suspend fun acknowledgeEvents(batch: WorkEventBatch): Boolean =
        throw UnsupportedOperationException("This WorkStore does not expose durable event cursors")
    suspend fun inspectHistory(scope: WorkScope, afterSequence: Long, limit: Int): List<WorkHistoryEntry> =
        throw UnsupportedOperationException("Retained inspection history unavailable")
}

data class WorkInspection(val records: List<WorkRecord>, val unreadableIds: List<String>)

class ObjectWorkStore(private val database: ObjectDatabase) : WorkStore {
    init { require(database.objectSerializer is TextSerializer) { "ObjectWorkStore requires a text-backed conditional-write database" } }
    override suspend fun get(scope: WorkScope, id: String): WorkRecord? =
        database.get(scope.storeName, id, WorkRecord::class, WorkRecord.serializer())?.value

    override suspend fun list(scope: WorkScope): List<WorkRecord> =
        database.getAll(scope.storeName, WorkRecord::class, WorkRecord.serializer()).map { it.value }

    override suspend fun inspect(scope: WorkScope): WorkInspection {
        val records = mutableListOf<WorkRecord>()
        val unreadable = mutableListOf<String>()
        database.exportRaw(scope.storeName).forEach { row ->
            try {
                val record = (database.objectSerializer as TextSerializer).deserialize(WorkRecord.serializer(), row.payload)
                require(record.intent.scope == scope && record.intent.id == row.key)
                records += record
            } catch (invalid: Exception) { unreadable += row.key }
        }
        return WorkInspection(records, unreadable)
    }

    override suspend fun admit(intent: WorkIntent, nowMillis: Long, dueAtMillis: Long): WorkAdmission {
        val record = WorkRecord(intent, nowMillis, dueAtMillis).transition(nowMillis)
        if (database.compareAndSet(intent.scope.storeName, intent.id, null, record, WorkRecord.serializer())) {
            return WorkAdmission.Accepted(record, existing = false)
        }
        val existing = get(intent.scope, intent.id) ?: error("Accepted work disappeared")
        return if (existing.intent == intent) WorkAdmission.Accepted(existing, existing = true)
        else WorkAdmission.Conflict(intent.id)
    }

    override suspend fun claimDue(
        scope: WorkScope, nowMillis: Long, owner: String, leaseMillis: Long, limit: Int,
    ): List<ClaimedWork> {
        require(owner.isNotBlank() && leaseMillis > 0 && limit in 1..64)
        val claims = mutableListOf<ClaimedWork>()
        val inspection = inspect(scope)
        check(inspection.unreadableIds.isEmpty()) { "Unreadable Work records require inspection and explicit migration" }
        for (record in inspection.records.sortedWith(compareBy({ it.nextRunAtMillis }, { it.intent.id }))) {
            if (claims.size == limit) break
            val expired = record.state == WorkState.RUNNING && (record.leaseUntilMillis ?: 0) <= nowMillis
            if (!expired && (record.state != WorkState.QUEUED || record.nextRunAtMillis > nowMillis)) continue
            if (record.attempt >= record.intent.maxAttempts) {
                replace(record, record.copy(
                    state = if (expired) WorkState.UNKNOWN else WorkState.FAILED,
                    leaseOwner = null, leaseUntilMillis = null,
                    reason = if (expired) "Attempt budget exhausted with an interrupted effect" else "Attempt budget exhausted",
                ), nowMillis)
                continue
            }
            val claimed = record.copy(
                state = WorkState.RUNNING, attempt = record.attempt + 1, fence = record.fence + 1,
                leaseOwner = owner, leaseUntilMillis = nowMillis + leaseMillis, reason = null,
            ).transition(nowMillis)
            if (compare(record, claimed)) claims += ClaimedWork(
                claimed, ClaimToken(scope, record.intent.id, owner, claimed.fence),
            )
        }
        return claims
    }

    override suspend fun renew(token: ClaimToken, nowMillis: Long, leaseMillis: Long): Boolean {
        require(leaseMillis > 0)
        return updateClaim(token, nowMillis) { it.copy(leaseUntilMillis = nowMillis + leaseMillis) }
    }

    override suspend fun checkpoint(token: ClaimToken, nowMillis: Long, value: String): Boolean {
        require(value.encodeToByteArray().size <= 65_536)
        return updateClaim(token, nowMillis) { it.copy(checkpoint = value) }
    }

    override suspend fun commit(token: ClaimToken, nowMillis: Long, result: WorkResult): Boolean =
        updateClaim(token, nowMillis) { record ->
            val outcome = when (result) {
                is WorkResult.HandedOff -> record.copy(state = WorkState.HANDED_OFF, handoff = result.receipt, reason = null)
                is WorkResult.Success -> record.copy(state = WorkState.SUCCEEDED, receipt = result.receipt, reason = null)
                is WorkResult.Retry -> record.copy(
                    state = if (record.attempt < record.intent.maxAttempts) WorkState.QUEUED else WorkState.FAILED,
                    nextRunAtMillis = maxOf(nowMillis, result.nextRunAtMillis), reason = result.reason,
                )
                is WorkResult.Failed -> record.copy(state = WorkState.FAILED, reason = result.reason)
                is WorkResult.Blocked -> record.copy(state = WorkState.BLOCKED, reason = result.reason)
                is WorkResult.Unknown -> record.copy(state = WorkState.UNKNOWN, reason = result.reason)
                is WorkResult.Quarantined -> record.copy(state = WorkState.QUARANTINED, reason = result.reason)
            }
            outcome.copy(leaseOwner = null, leaseUntilMillis = null)
        }

    override suspend fun reconcileHandoff(scope: WorkScope, id: String, handoff: WorkHandoff, nowMillis: Long, result: WorkResult): Boolean {
        repeat(16) {
            val record = get(scope, id) ?: return false
            if (record.state != WorkState.HANDED_OFF || record.handoff != handoff) return false
            val after = when (result) {
                is WorkResult.Success -> record.copy(state = WorkState.SUCCEEDED, receipt = result.receipt, reason = null)
                is WorkResult.Failed -> record.copy(state = WorkState.FAILED, reason = result.reason)
                is WorkResult.Unknown -> record.copy(state = WorkState.UNKNOWN, reason = result.reason)
                is WorkResult.Quarantined -> record.copy(state = WorkState.QUARANTINED, reason = result.reason)
                else -> error("A provider-owned attempt cannot restart through local retry")
            }
            if (replace(record, after, nowMillis)) return true
        }
        return false
    }

    override suspend fun cancel(scope: WorkScope, id: String, nowMillis: Long): Boolean {
        repeat(16) {
            val record = get(scope, id) ?: return false
            if (record.state in setOf(WorkState.SUCCEEDED, WorkState.FAILED, WorkState.CANCELLED)) return false
            if (replace(record, record.copy(
                state = WorkState.CANCELLED, leaseOwner = null, leaseUntilMillis = null,
                reason = if (record.attempt > 0) "Cancelled; an accepted external effect may need reconciliation" else "Cancelled",
            ), nowMillis)) return true
        }
        return false
    }

    override suspend fun readEvents(scope: WorkScope, consumerId: String, limit: Int): WorkEventBatch {
        require(consumerId.isNotBlank() && consumerId.length <= 256 && limit in 1..256)
        val cursor = database.get(cursorStore(scope), consumerId, WorkEventCursor::class, WorkEventCursor.serializer())?.value
        val changes = database.readChanges(scope.storeName, cursor?.sequence ?: 0, limit)
        val codec = database.objectSerializer as TextSerializer
        val events = changes.map { change ->
            val record = change.payload?.let { codec.deserialize(WorkRecord.serializer(), it) }
            require(record == null || (record.intent.scope == scope && record.intent.id == change.key))
            WorkJournalEvent(change.sequence, change.key, record, change.atMillis)
        }
        return WorkEventBatch(this, scope, consumerId, cursor, events)
    }

    override suspend fun acknowledgeEvents(batch: WorkEventBatch): Boolean {
        require(batch.owner === this) { "Event batch belongs to a different WorkStore authority" }
        val sequence = batch.events.lastOrNull()?.sequence ?: return true
        require(sequence > (batch.previous?.sequence ?: 0))
        return database.compareAndSet(cursorStore(batch.scope), batch.consumerId, batch.previous,
            WorkEventCursor(sequence), WorkEventCursor.serializer())
    }

    override suspend fun inspectHistory(scope: WorkScope, afterSequence: Long, limit: Int): List<WorkHistoryEntry> {
        val codec = database.objectSerializer as TextSerializer
        return database.readChanges(scope.storeName, afterSequence, limit).map { change ->
            val record = change.payload?.let { payload -> runCatching {
                codec.deserialize(WorkRecord.serializer(), payload).also {
                    require(it.intent.scope == scope && it.intent.id == change.key)
                }
            }.getOrNull() }
            WorkHistoryEntry(change.sequence, change.key, change.atMillis, record?.revision, record?.state,
                record?.attempt, record?.fence, deleted = change.payload == null,
                unreadable = change.payload != null && record == null, definition = record?.intent?.definition)
        }
    }

    override suspend fun control(scope: WorkScope, id: String, expectedRevision: Long, nowMillis: Long,
        action: WorkControlAction, result: WorkResult?): WorkMetadata? {
        val before = get(scope, id) ?: return null
        if (before.revision != expectedRevision || before.intent.scope != scope) return null
        val after = when (action) {
            WorkControlAction.CANCEL -> {
                if (before.state in setOf(WorkState.SUCCEEDED, WorkState.FAILED, WorkState.CANCELLED)) return null
                before.copy(state = WorkState.CANCELLED, fence = before.fence + 1, leaseOwner = null, leaseUntilMillis = null,
                    reason = "Operator cancelled; accepted external effects may require reconciliation")
            }
            WorkControlAction.RETRY -> {
                if (before.state !in setOf(WorkState.FAILED, WorkState.BLOCKED) || before.handoff != null || before.attempt >= before.intent.maxAttempts) return null
                before.copy(state = WorkState.QUEUED, nextRunAtMillis = nowMillis, fence = before.fence + 1,
                    leaseOwner = null, leaseUntilMillis = null, reason = "Authorized operator retry")
            }
            WorkControlAction.RECONCILE -> {
                if (before.handoff == null || before.state !in setOf(WorkState.HANDED_OFF, WorkState.UNKNOWN, WorkState.CANCELLED)) return null
                when (result) {
                    is WorkResult.Success -> before.copy(state = WorkState.SUCCEEDED, receipt = result.receipt, reason = null)
                    is WorkResult.Failed -> before.copy(state = WorkState.FAILED, reason = result.reason)
                    is WorkResult.Unknown -> before.copy(state = WorkState.UNKNOWN, reason = result.reason)
                    is WorkResult.Quarantined -> before.copy(state = WorkState.QUARANTINED, reason = result.reason)
                    else -> return null
                }.copy(fence = before.fence + 1, leaseOwner = null, leaseUntilMillis = null)
            }
        }.transition(nowMillis)
        return if (compare(before, after)) after.metadata() else null
    }

    private fun cursorStore(scope: WorkScope) = "__reaktor_work_cursors:${scope.storeName}"

    override fun observe(scope: WorkScope, id: String): Flow<WorkRecord?> = database.events
        .filter { event ->
            when (event) {
                is DatabaseEvent.Invalidated -> event.storeName == scope.storeName && event.key == id
                is DatabaseEvent.Put<*> -> event.storeName == scope.storeName && event.key == id
                is DatabaseEvent.Delete -> event.storeName == scope.storeName && event.key == id
                is DatabaseEvent.Clear -> event.storeName == scope.storeName
                is DatabaseEvent.ClearAll -> true
                else -> false
            }
        }
        .map { get(scope, id) }
        .onStart { emit(get(scope, id)) }

    private suspend fun updateClaim(token: ClaimToken, nowMillis: Long, change: (WorkRecord) -> WorkRecord): Boolean {
        repeat(16) {
            val record = get(token.scope, token.id) ?: return false
            if (record.state != WorkState.RUNNING || record.fence != token.fence || record.leaseOwner != token.owner ||
                (record.leaseUntilMillis ?: 0) <= nowMillis) return false
            if (replace(record, change(record), nowMillis)) return true
        }
        return false
    }

    private suspend fun replace(before: WorkRecord, after: WorkRecord, nowMillis: Long): Boolean =
        compare(before, after.transition(nowMillis))

    private suspend fun compare(before: WorkRecord, after: WorkRecord): Boolean = database.compareAndSet(
        before.intent.scope.storeName, before.intent.id, before, after, WorkRecord.serializer(),
    )
}

private fun WorkRecord.transition(nowMillis: Long): WorkRecord {
    val next = revision + 1
    return copy(revision = next, transitions = (transitions + WorkTransition(next, state, nowMillis, attempt, fence)).takeLast(64))
}
