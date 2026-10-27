package dev.shibasis.reaktor.service

import dev.shibasis.reaktor.service.contract.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.*

class ActivityCaptureTest {
    @Test fun captureIndexesSiblingsAndKeepsNestedParents() = runTest {
        val keys = mutableListOf<ActivityCaptureKey>()
        val capture = object : ActivityCapture {
            override suspend fun <In, Out> invoke(key: ActivityCaptureKey, request: In, execute: suspend (In) -> Out): Out {
                keys += key
                return execute(request)
            }
        }
        val parent = ServiceCall(TraceContext.root(), null, emptyMap())
        withContext(parent) { withActivityCapture(capture) {
            activity("flow", "read", 1, Effect.LiveRead, emptyMap()) { input ->
                activity("store", "query", input, Effect.LiveRead, emptyMap()) { it + 1 }
            }
            activity("flow", "write", 2, Effect.ProductionReversibleWrite, emptyMap()) { it }
        } }
        assertEquals(listOf(0, 0, 1), keys.map { it.index })
        assertEquals(parent.trace.spanId, keys[0].parent)
        assertEquals(parent.trace.spanId, keys[2].parent)
        assertNotEquals(keys[0].parent, keys[1].parent)
        assertTrue(keys.all { it.trace == parent.trace.traceId })
    }
}
