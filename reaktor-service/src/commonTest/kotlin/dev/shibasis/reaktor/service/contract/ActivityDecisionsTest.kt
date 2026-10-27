package dev.shibasis.reaktor.service.contract

import dev.shibasis.reaktor.service.SpanBuffer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityDecisionsTest {
    @Test fun decisionsBelongToTheStepThatMadeThem() = runTest {
        val sink = SpanBuffer()
        val interceptors = listOf(Spans("test", sink)).installedAs(InterceptorScope.Graph)
        activity("test", "parent", Unit, Effect.ReadOnly, emptyMap(), interceptors) {
            decision("strategy", "v0")
            activity("test", "child", Unit, Effect.ReadOnly, emptyMap(), interceptors) {
                decision("verdict", "allowed")
            }
        }
        val spans = sink.snapshot().associateBy { it.operation }
        assertEquals(mapOf("strategy" to "v0"), spans.getValue("parent").decisions)
        assertEquals(mapOf("verdict" to "allowed"), spans.getValue("child").decisions)
        assertEquals(spans.getValue("parent").spanId, spans.getValue("child").parentId)
    }

    @Test fun decisionsAreBounded() = runTest {
        val sink = SpanBuffer()
        activity("test", "bounded", Unit, Effect.ReadOnly, emptyMap(), listOf(Spans("test", sink)).installedAs(InterceptorScope.Graph)) {
            decision("invalid key", "ignored")
            decision("oversized", "x".repeat(257))
            repeat(20) { decision("choice.$it", "allowed") }
        }
        assertEquals(16, sink.snapshot().single().decisions.size)
    }
}
