package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.autoWire
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.contract.CallAttributes
import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.resource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallAttributesTest {
    @Test fun resourcePortsPreserveCallerAttributesAndReplaceTheCallingEdge() = runTest {
        Reaktor.web()
        val graph = Graph(label = "resources")
        try {
            val binding = graph.Node { ResourceNode(it, "storage", NodeKind.Service) }
            val ports = graph.resourcePorts(listOf(binding))
            val caller = mapOf("principal" to "ada", "authorized" to true, ServiceCall.EdgeAttribute to "outer-edge")
            withContext(CallAttributes(caller)) {
                val result = ports.execute("storage", "read", Effect.ReadOnly) {
                    val attributes = currentCoroutineContext()[ServiceCall]!!.attributes
                    assertEquals("ada", attributes["principal"])
                    assertEquals(true, attributes["authorized"])
                    val edge = graph.shape().wires.single { it.consumer == ports.id.toString() }.definitionId
                    assertTrue(edge.isNotBlank())
                    assertEquals(edge, attributes[ServiceCall.EdgeAttribute])
                    42
                }
                assertEquals(42, result)
                assertEquals(caller, currentCoroutineContext()[CallAttributes]?.values)
            }
        } finally {
            graph.close()
        }
    }

    @Test fun usesPreservesAttributesSetInsideItsScopeAndReplacesTheCallingEdge() = runTest {
        Reaktor.web()
        val graph = Graph(label = "resources")
        try {
            graph.Node { ResourceNode(it, "storage", NodeKind.Service) }
            val node = graph.Node { BasicNode(it) }
            val access = node.uses("storage")
            graph.autoWire()
            access {
                val caller = mapOf("principal" to "ada", "authorized" to true, ServiceCall.EdgeAttribute to "outer-edge")
                withContext(CallAttributes(caller)) {
                    val result = resource("storage", "read", Effect.ReadOnly) {
                        val attributes = currentCoroutineContext()[ServiceCall]!!.attributes
                        assertEquals("ada", attributes["principal"])
                        assertEquals(true, attributes["authorized"])
                        val edge = graph.shape().wires.single { it.consumer == node.id.toString() }.definitionId
                        assertTrue(edge.isNotBlank())
                        assertEquals(edge, attributes[ServiceCall.EdgeAttribute])
                        42
                    }
                    assertEquals(42, result)
                    assertEquals(caller, currentCoroutineContext()[CallAttributes]?.values)
                }
            }
        } finally {
            graph.close()
        }
    }

    @Test fun sharedActivitiesPreserveCallerAttributesAndRecordEachCallingEdge() = runTest {
        Reaktor.web()
        val graph = Graph(label = "workflow")
        try {
            val owner = graph.Node { Workflow(it, "owner", "Owner") }
            val caller = graph.Node { Workflow(it, "caller", "Caller") }
            val seen = mutableListOf<Map<String, Any?>>()
            val original = owner.activity<Int, Int>("read", Effect.ReadOnly) {
                seen += currentCoroutineContext()[ServiceCall]!!.attributes
                it + 1
            }
            val shared = caller.call("read", original)
            val attributes = mapOf("principal" to "ada", "authorized" to true, ServiceCall.EdgeAttribute to "outer-edge")
            withContext(CallAttributes(attributes)) {
                assertEquals(2, original(1))
                assertEquals(3, shared(2))
                assertEquals(attributes, currentCoroutineContext()[CallAttributes]?.values)
            }
            val shape = graph.shape()
            listOf(owner, caller).zip(seen).forEach { (node, recorded) ->
                assertEquals("ada", recorded["principal"])
                assertEquals(true, recorded["authorized"])
                val edge = shape.wires.single { it.consumer == node.id.toString() }.definitionId
                assertTrue(edge.isNotBlank())
                assertEquals(edge, recorded[ServiceCall.EdgeAttribute])
            }
        } finally {
            graph.close()
        }
    }
}
