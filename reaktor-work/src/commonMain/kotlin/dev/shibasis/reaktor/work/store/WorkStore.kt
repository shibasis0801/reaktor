package dev.shibasis.reaktor.work.store

import dev.shibasis.reaktor.work.run.RunRecord
import dev.shibasis.reaktor.work.run.RunTransition
import dev.shibasis.reaktor.work.run.WorkScope
import kotlinx.coroutines.flow.Flow

interface WorkStore {
    suspend fun admit(record: RunRecord): Admission
    suspend fun get(scope: WorkScope, id: String): RunRecord?
    suspend fun replace(before: RunRecord, after: RunRecord): Boolean
    suspend fun due(scope: WorkScope, nowMillis: Long, limit: Int): List<RunRecord>
    suspend fun nextWake(scope: WorkScope): Long?
    suspend fun page(scope: WorkScope, after: String?, limit: Int, include: (RunRecord) -> Boolean = { true }): RunPage
    fun observe(scope: WorkScope, id: String): Flow<RunRecord?>
    fun changes(scope: WorkScope): Flow<Unit>
    suspend fun history(scope: WorkScope, afterSequence: Long, limit: Int): List<JournalEvent>
    suspend fun events(scope: WorkScope, consumer: String, limit: Int = 64): JournalBatch
    suspend fun acknowledge(batch: JournalBatch): Boolean
}

sealed interface Admission {
    data class Accepted(val record: RunRecord, val existing: Boolean) : Admission
    data class Conflict(val id: String) : Admission
}

data class RunPage(val runs: List<RunRecord>, val unreadableIds: List<String>, val next: String?)

data class JournalEvent(val sequence: Long, val id: String, val record: RunRecord?, val atMillis: Long, val unreadable: Boolean = false) {
    val transitions: List<RunTransition> get() = record?.transitions.orEmpty()
}

class JournalBatch internal constructor(
    internal val owner: WorkStore,
    val scope: WorkScope,
    val consumer: String,
    internal val previous: Long?,
    val events: List<JournalEvent>,
)
