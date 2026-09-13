package dev.shibasis.reaktor.telemetry.export

import io.opentelemetry.kotlin.ExperimentalApi
import io.opentelemetry.kotlin.export.OperationResultCode
import io.opentelemetry.kotlin.tracing.data.SpanData
import io.opentelemetry.kotlin.tracing.export.SpanExporter
import kotlinx.atomicfu.atomic

/**
 * How an encoded batch leaves the process.
 *
 * Injected rather than fixed so the exporter is testable without a network, and so the same
 * exporter serves the production path (OTLP over HTTP) and the development path (a local socket
 * to the workbench) without a second implementation.
 */
interface SpanBatchTransport {
    /** Returns false when the batch was not accepted; the exporter counts that as a failure. */
    suspend fun send(body: String): Boolean

    suspend fun flush(): Boolean = true

    suspend fun close(): Boolean = true
}

/**
 * Keeps batches in memory. The development path, and what the tests assert against.
 *
 * The export worker appends from its own coroutine while a test reads, so [batches] is an
 * immutable list swapped under compare-and-set rather than a live `mutableListOf`. Exposing the
 * mutable list directly made every reader racy: `transport.batches.flatMap { ... }` threw
 * `ConcurrentModificationException` whenever a straggler batch landed mid-iteration, which is the
 * shape of flakiness that gets blamed on the thing being measured.
 */
class RecordingTransport(private val accept: Boolean = true) : SpanBatchTransport {
    private val _batches = atomic<List<String>>(emptyList())
    private val _flushes = atomic(0)
    private val _closed = atomic(false)

    /** A snapshot. Safe to iterate while exports are still in flight. */
    val batches: List<String> get() = _batches.value
    val flushes: Int get() = _flushes.value
    val closed: Boolean get() = _closed.value

    override suspend fun send(body: String): Boolean {
        if (!accept) return false
        while (true) {
            val current = _batches.value
            if (_batches.compareAndSet(current, current + body)) return true
        }
    }

    override suspend fun flush(): Boolean { _flushes.incrementAndGet(); return true }

    override suspend fun close(): Boolean { _closed.value = true; return true }
}

/**
 * The OTLP exporter. Encodes with [OtlpJsonSpans] and hands the body to a [SpanBatchTransport].
 *
 * A batch that the transport rejects is reported as a failure rather than retried here —
 * retry policy belongs to the transport, which knows whether the destination is a local socket
 * or a metered network.
 */
@OptIn(ExperimentalApi::class)
class OtlpSpanExporter(private val transport: SpanBatchTransport) : SpanExporter {
    private val sent = atomic(0L)
    private val rejected = atomic(0L)

    val spansSent: Long get() = sent.value
    val spansRejected: Long get() = rejected.value

    override suspend fun export(spans: List<SpanData>): OperationResultCode {
        if (spans.isEmpty()) return OperationResultCode.Success
        val accepted = runCatching { transport.send(OtlpJsonSpans.encode(spans)) }.getOrDefault(false)
        return if (accepted) {
            sent.addAndGet(spans.size.toLong())
            OperationResultCode.Success
        } else {
            rejected.addAndGet(spans.size.toLong())
            OperationResultCode.Failure
        }
    }

    override suspend fun forceFlush(): OperationResultCode =
        if (runCatching { transport.flush() }.getOrDefault(false)) {
            OperationResultCode.Success
        } else {
            OperationResultCode.Failure
        }

    override suspend fun shutdown(): OperationResultCode =
        if (runCatching { transport.close() }.getOrDefault(false)) {
            OperationResultCode.Success
        } else {
            OperationResultCode.Failure
        }
}
