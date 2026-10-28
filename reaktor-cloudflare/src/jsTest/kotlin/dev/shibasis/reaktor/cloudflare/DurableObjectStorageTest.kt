package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.graph.Reaktor
import dev.shibasis.reaktor.graph.intercept
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.service.SpanBuffer
import dev.shibasis.reaktor.service.contract.Spans
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.*

class DurableObjectStorageTest {
    @Test fun storageCallsUseTheirStorePortOnceAndKeepMissingValuesMissing() = runTest {
        Reaktor.web()
        val graph = Graph(label = "object")
        val spans = SpanBuffer()
        graph.intercept(Spans("object", spans))
        val access = graph.durableObjectStorage()
        var reads = 0
        val values = mutableMapOf<String, Any?>()
        val raw = js("({})")
        raw.get = { key: String -> reads++; Promise.resolve(values[key]) }
        raw.put = { key: String, value: Any? -> values[key] = value; Promise.resolve(Unit) }
        raw.delete = { key: String -> Promise.resolve(values.remove(key) != null) }
        raw.deleteAll = { values.clear(); Promise.resolve(Unit) }
        raw.getAlarm = { Promise.resolve(null) }
        val storage = DurableObjectStorage(raw.unsafeCast<RawDurableObjectStorage>()).traced(access)
        val shape = graph.shape()
        assertNull(storage.text("missing"))
        assertEquals(1, reads)
        storage.putText("state", "ready")
        assertEquals("ready", storage.text("state"))
        assertTrue(storage.delete("state"))
        assertNull(storage.alarm())
        storage.clear()
        val recorded = spans.snapshot()
        assertEquals(listOf("get", "put", "get", "delete", "get-alarm", "clear"), recorded.map { it.operation })
        recorded.forEach { span ->
            assertEquals(shape.definitionDigest, span.graphDigest)
            assertTrue(shape.nodes.any { it.definitionId == span.node && it.attributes["storage"] == "durable-object" })
            assertTrue(shape.wires.any { it.definitionId == span.edge })
        }
        graph.close()
    }
}
