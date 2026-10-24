package dev.shibasis.reaktor.telemetry

import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.SpanBuffer
import dev.shibasis.reaktor.telemetry.export.ClientOperations
import dev.shibasis.reaktor.telemetry.export.captureClientOperation
import dev.shibasis.reaktor.telemetry.export.createClientServiceTelemetry
import dev.shibasis.reaktor.telemetry.port.ReaktorAttributes
import io.opentelemetry.kotlin.ExperimentalApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalApi::class)
class ClientOperationsTest {
    private val spans = SpanBuffer()
    private val telemetry = createClientServiceTelemetry("client", spans)
    private val attributes = mapOf(ReaktorAttributes.ContractId to "test.ui", ServiceCall.SessionAttribute to "session",
        ServiceCall.EnvironmentAttribute to "dev", ServiceCall.BuildAttribute to "fixture")
    private val operations = ClientOperations(telemetry, { attributes })

    @Test fun localActionsRecordFailuresAndDoNotLeakContext() {
        assertFailsWith<IllegalStateException> {
            operations.trace("ui.action", mapOf(ReaktorAttributes.NodeId to "screen")) {
                operations.trace("ui.nested") { error("private content") }
            }
        }
        val recorded = spans.snapshot().associateBy { it.operation }
        val parent = recorded.getValue("ui.action")
        val child = recorded.getValue("ui.nested")
        assertEquals(parent.traceId, child.traceId)
        assertEquals(parent.spanId, child.parentId)
        assertTrue(recorded.values.all { it.status == 500 && it.applicationSession == "session" && it.node == "screen" })
        assertNull(captureClientOperation())
    }

    @Test fun capturedActionParentsWorkAfterTheCallbackReturns() = runTest {
        val captured = operations.trace("ui.action") { requireNotNull(captureClientOperation()) }
        withContext(Dispatchers.Default + captured.context) {
            operations.traceAsync("ui.work") {
                val call = requireNotNull(currentCoroutineContext()[ServiceCall])
                delay(1)
                assertEquals(call, currentCoroutineContext()[ServiceCall])
                operations.traceAsync("ui.child") { delay(1) }
            }
        }
    }

    @Test fun suspensionAndCancellationRetainTheCausalChain() = runTest {
        val captured = operations.trace("ui.action") { requireNotNull(captureClientOperation()) }
        assertFailsWith<CancellationException> {
            withContext(captured.context) {
                operations.traceAsync("ui.work") {
                    delay(1)
                    operations.traceAsync("ui.cancel") { throw CancellationException() }
                }
            }
        }
        val recorded = spans.snapshot().associateBy { it.operation }
        val action = recorded.getValue("ui.action")
        val work = recorded.getValue("ui.work")
        val cancel = recorded.getValue("ui.cancel")
        assertEquals(action.spanId, work.parentId)
        assertEquals(work.spanId, cancel.parentId)
        assertTrue(recorded.values.all { it.traceId == action.traceId && it.durationMillis >= 0 })
        assertEquals(499, work.status)
        assertEquals(499, cancel.status)
        assertNull(currentCoroutineContext()[ServiceCall])
    }

    @Test fun disablingObservationSkipsIdentityLookupAndSpanCreation() {
        val disabled = ClientOperations(telemetry, { error("lookup should not run") }, enabled = { false })
        assertEquals(42, disabled.trace("ui.action") { 42 })
        assertTrue(spans.snapshot().isEmpty())
    }
}
