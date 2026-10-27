package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.service.SpanBuffer
import dev.shibasis.reaktor.service.contract.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PersistentActorNodeTest {
    private data class State(var phase: String)
    @Test fun storeOwnsRollbackAndKeysDoNotChangeDefinition() = runTest {
        Reaktor.web()
        val graph = Graph(label = "worker")
        val spans = SpanBuffer()
        graph.intercept(Spans("worker", spans))
        val persisted = mutableMapOf<String, State>()
        val store = object : ActorStateStore<String, State> {
            override suspend fun <Out> transact(input: String, execute: suspend (State) -> Out): Out {
                val value = (persisted[input] ?: State("pending")).copy()
                return execute(value).also { persisted[input] = value }
            }
        }
        val actor = graph.Node { PersistentActorNode(it, "verification", "Verification", "postgres", store,
            setOf("pending", "verified"), listOf(ActorTransition("callback", setOf("pending"), setOf("verified"))), State::phase) }
        val callback = actor.transition<Unit>("callback", Effect.ProductionReversibleWrite) { key, state ->
            state.phase = "verified"
            if (key == "failed") error("storage failure")
        }
        val shape = graph.shape()
        callback("one")
        callback("two")
        assertFailsWith<IllegalStateException> { callback("failed") }
        assertEquals(setOf("one", "two"), persisted.keys)
        assertEquals(shape.definitionDigest, graph.shape().definitionDigest)
        assertEquals("verified", spans.snapshot().first().decisions["state-after"])
        assertEquals("failed", spans.snapshot().last().decisions["supervision"])
        assertFailsWith<IllegalArgumentException> { callback("one") }
        graph.close()
    }
}
