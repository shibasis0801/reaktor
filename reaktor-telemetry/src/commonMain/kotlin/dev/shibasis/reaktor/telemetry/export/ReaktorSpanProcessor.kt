package dev.shibasis.reaktor.telemetry.export

import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.context.Context
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.tracing.data.SpanData
import io.opentelemetry.kotlin.tracing.export.SpanExporter
import io.opentelemetry.kotlin.tracing.export.SpanProcessor
import io.opentelemetry.kotlin.tracing.model.ReadWriteSpan
import io.opentelemetry.kotlin.tracing.model.ReadableSpan
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the processor is doing to itself, for D18 self-monitoring. *No data* is a state. */
data class SpanPipelineHealth(
    val accepted: Long,
    val exported: Long,
    val dropped: Long,
    val failed: Long,
    val queued: Int,
) {
    val healthy: Boolean get() = dropped == 0L && failed == 0L
}

/**
 * D2 — the bounded batching processor.
 *
 * Three properties the design requires of anything that observes a running system:
 *
 * 1. **The producer never blocks.** `onEnd` only offers to a bounded channel. A full queue drops
 *    rather than suspending the traced call, because a tool that perturbs the timing of the thing
 *    it inspects cannot be used to debug timing.
 * 2. **Every drop is counted.** [health] reports drops, so a gap in the record is visible as a
 *    gap rather than as quiescence.
 * 3. **Batches are bounded.** At most [maxBatchSize] spans per export, so one burst cannot turn
 *    into one enormous request.
 */
@OptIn(ExperimentalApi::class)
class ReaktorSpanProcessor(
    private val exporter: SpanExporter,
    scope: CoroutineScope,
    private val maxBatchSize: Int = 50,
    maxQueuedSpans: Int = 2048,
) : SpanProcessor {

    init {
        require(maxBatchSize in 1..10_000) { "maxBatchSize must be between 1 and 10000" }
        require(maxQueuedSpans >= maxBatchSize) { "The queue must hold at least one batch" }
    }

    private val queue = Channel<SpanData>(capacity = maxQueuedSpans)
    private val accepted = atomic(0L)
    private val exported = atomic(0L)
    private val dropped = atomic(0L)
    private val failed = atomic(0L)
    private val queued = atomic(0)
    private val stopped = atomic(false)

    /**
     * Guards a batch from the moment it leaves the queue until its export completes, so
     * [forceFlush] waits for work already in flight instead of reporting success while the
     * worker still holds spans. Without this a flush can claim to have exported everything
     * while most of the batch is still in the worker's hands.
     */
    private val batchInFlight = Mutex()

    private val worker: Job = scope.launch {
        for (span in queue) {
            batchInFlight.withLock {
                queued.decrementAndGet()
                val batch = ArrayList<SpanData>(maxBatchSize)
                batch += span
                while (batch.size < maxBatchSize) {
                    val more = queue.tryReceive().getOrNull() ?: break
                    queued.decrementAndGet()
                    batch += more
                }
                exportBatch(batch)
            }
        }
    }

    override fun isStartRequired(): Boolean = false

    override fun isEndRequired(): Boolean = true

    override fun onStart(span: ReadWriteSpan, parentContext: Context) = Unit

    override fun onEnding(span: ReadWriteSpan) = Unit

    override fun onEnd(span: ReadableSpan) {
        if (stopped.value) {
            dropped.incrementAndGet()
            return
        }
        accepted.incrementAndGet()
        val result = queue.trySend(span.toSpanData())
        if (result.isSuccess) queued.incrementAndGet() else dropped.incrementAndGet()
    }

    override suspend fun forceFlush(): OperationResultCode {
        var ok = true
        // Taking the lock waits for any batch the worker is already exporting.
        batchInFlight.withLock {
            val pending = ArrayList<SpanData>(maxBatchSize)
            while (true) {
                val next = queue.tryReceive().getOrNull() ?: break
                queued.decrementAndGet()
                pending += next
                if (pending.size == maxBatchSize) {
                    ok = exportBatch(pending.toList()) && ok
                    pending.clear()
                }
            }
            if (pending.isNotEmpty()) ok = exportBatch(pending.toList()) && ok
        }
        val downstream = exporter.forceFlush()
        return if (ok && downstream is OperationResultCode.Success) {
            OperationResultCode.Success
        } else {
            OperationResultCode.Failure
        }
    }

    override suspend fun shutdown(): OperationResultCode {
        stopped.value = true
        val flushed = forceFlush()
        queue.close()
        worker.cancel()
        val closed = exporter.shutdown()
        return if (flushed is OperationResultCode.Success && closed is OperationResultCode.Success) {
            OperationResultCode.Success
        } else {
            OperationResultCode.Failure
        }
    }

    val health: SpanPipelineHealth
        get() = SpanPipelineHealth(
            accepted = accepted.value,
            exported = exported.value,
            dropped = dropped.value,
            failed = failed.value,
            queued = queued.value,
        )

    private suspend fun exportBatch(batch: List<SpanData>): Boolean {
        if (batch.isEmpty()) return true
        val result = runCatching { exporter.export(batch) }.getOrElse { OperationResultCode.Failure }
        return if (result is OperationResultCode.Success) {
            exported.addAndGet(batch.size.toLong())
            true
        } else {
            failed.addAndGet(batch.size.toLong())
            false
        }
    }
}
