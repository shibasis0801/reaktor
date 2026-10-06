package dev.shibasis.reaktor.work

import kotlinx.serialization.Serializable

@Serializable
data class WorkEventCursor(val sequence: Long)

/** A null record is an explicit administrative deletion, never a successful effect. */
data class WorkJournalEvent(val sequence: Long, val id: String, val record: WorkRecord?, val atMillis: Long)

/**
 * Deliver effects/projections idempotently using event.sequence, then acknowledge the whole batch.
 * A crash before acknowledgement redelivers it. Acknowledgement does not atomically commit an
 * external side effect; that resource still owns deduplication. Batches are authority-local.
 */
class WorkEventBatch internal constructor(
    internal val owner: WorkStore,
    val scope: WorkScope,
    val consumerId: String,
    internal val previous: WorkEventCursor?,
    val events: List<WorkJournalEvent>,
)
