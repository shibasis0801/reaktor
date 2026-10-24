package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.*
import dev.shibasis.reaktor.service.*
import dev.shibasis.reaktor.service.contract.Metadata
import dev.shibasis.reaktor.service.contract.Spans
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.*
import dev.shibasis.reaktor.service.contract.Service

class GraphDefinitionTest {
    private fun definition(root: String, producer: String, consumer: String) = GraphShape(
        listOf(ScopeShape(root, "application")),
        listOf(
            NodeShape(producer, root, "messaging", "ServiceNode", NodeKind.Service,
                ports = listOf(PortShape("send", "Message", true, PortWiring.Connected))),
            NodeShape(consumer, root, "chat", "ChatSession", NodeKind.Controller,
                ports = listOf(PortShape("send", "Message", false, PortWiring.Connected))),
        ),
        listOf(WireShape(consumer, "send", producer, "send", "Message")), emptyList(),
    ).withDefinitionIdentity()

    @Test fun activationAndPresentationOrderDoNotChangeDefinitions() {
        val first = definition("first", "p1", "c1")
        val second = definition("second", "p2", "c2").let {
            it.copy(nodes = it.nodes.reversed(), scopes = it.scopes.map { scope -> scope.copy(active = false, backStack = listOf("c2")) })
        }.withDefinitionIdentity()
        assertEquals(first.definitionDigest, second.definitionDigest)
        assertEquals(first.nodes.associate { it.label to it.definitionId }, second.nodes.associate { it.label to it.definitionId })
        assertEquals(first.wires.single().definitionId, second.wires.single().definitionId)
        assertNotEquals(first.nodes.first().ports.first().definitionId, first.nodes.last().ports.first().definitionId)
        assertNotEquals(first.definitionDigest, first.copy(wires = emptyList()).withDefinitionIdentity().definitionDigest)
    }

    @Test fun retainedArtifactsRemoveRuntimeIdentityAndRejectChangedTopology() {
        val first = definition("launch-one", "p1", "c1")
        val artifact = first.definitionArtifact()
        assertEquals(first.definitionDigest, artifact.definitionDigest)
        assertEquals(artifact, definition("launch-two", "p2", "c2").definitionArtifact())
        assertEquals(artifact, artifact.definitionArtifact())
        assertTrue(artifact.nodes.all { it.id == it.definitionId && it.lifecycle == null && it.ports.all { port -> port.wiring == PortWiring.Unused && port.connections == 0 } })
        assertTrue(artifact.scopes.all { !it.active && it.backStack.isEmpty() })
        assertFailsWith<IllegalArgumentException> { first.copy(wires = emptyList()).definitionArtifact() }
        val ambiguous = first.copy(nodes = first.nodes + first.nodes.first().copy(id = "duplicate")).withDefinitionIdentity()
        assertFailsWith<IllegalArgumentException> { ambiguous.definitionArtifact() }
    }

    @Test fun retainedTopologyFitsTheUploadBudgetWithoutRepeatingServiceSchemas() {
        val contract = dev.shibasis.reaktor.service.contract.ContractShape.of(DefinitionProbe()).let { shape ->
            shape.copy(operations = shape.operations.map { operation ->
                operation.copy(request = operation.request.copy(values = listOf("x".repeat(600_000))))
            })
        }
        val first = definition("root", "producer", "consumer").let { shape ->
            shape.copy(nodes = shape.nodes.map { if (it.label == "messaging") it.copy(contract = contract) else it })
        }.withDefinitionIdentity()
        val artifact = first.definitionArtifact()
        val retained = requireNotNull(artifact.nodes.single { it.label == "messaging" }.contract)
        assertTrue(kotlinx.serialization.json.Json.encodeToString(GraphShape.serializer(), first).encodeToByteArray().size > 600_000)
        assertTrue(kotlinx.serialization.json.Json.encodeToString(GraphShape.serializer(), artifact).encodeToByteArray().size < 524_288)
        assertEquals(contract.id, retained.id)
        assertEquals(contract.version, retained.version)
        assertEquals(contract.fingerprint, retained.fingerprint)
        assertEquals(first.wires.single().definitionId, artifact.wires.single().definitionId)
        assertEquals(artifact, artifact.definitionArtifact())
        val changed = first.copy(nodes = first.nodes.map {
            if (it.contract == null) it else it.copy(contract = it.contract.copy(fingerprint = "changed-schema"))
        }).withDefinitionIdentity()
        assertNotEquals(first.definitionDigest, changed.definitionDigest)
    }

    @Test fun ambiguousDeclarationsDoNotAcquireAnExactIdentity() {
        val shape = definition("root", "p", "c")
        val ambiguous = shape.copy(nodes = shape.nodes + shape.nodes.first().copy(id = "p-other")).withDefinitionIdentity()
        assertTrue(ambiguous.nodes.filter { it.label == "messaging" }.all { it.definitionId.isEmpty() && it.ports.all { port -> port.definitionId.isEmpty() } })
        assertTrue(ambiguous.wires.single().definitionId.isEmpty())
    }

    @Test fun navigationRuntimeKeysDoNotChangeDefinitionsAcrossLaunches() {
        fun navigation(root: String, from: String, to: String) = GraphShape(
            listOf(ScopeShape(root, "application")),
            listOf(
                NodeShape(from, root, "chats", "RouteNode", NodeKind.Route, route = "/chats",
                    ports = listOf(PortShape(to, "NavBinding", false, PortWiring.Connected))),
                NodeShape(to, root, "chat", "RouteNode", NodeKind.Route, route = "/chat/{id}",
                    ports = listOf(PortShape("navBinding", "NavBinding", true, PortWiring.Connected))),
            ),
            listOf(WireShape(from, to, to, "navBinding", "NavBinding")),
            listOf(RouteShape(from, to)),
        ).withDefinitionIdentity()
        val first = navigation("launch-one", "chats-one", "chat-one")
        val second = navigation("launch-two", "chats-two", "chat-two")
        assertEquals(first.definitionDigest, second.definitionDigest)
        assertEquals(first.nodes.first().ports.single().definitionId, second.nodes.first().ports.single().definitionId)
        assertEquals(first.wires.single().definitionId, second.wires.single().definitionId)
        assertEquals("chat-one", first.nodes.first().ports.single().key)
        assertEquals("chat-one", first.wires.single().consumerPort)
        val ambiguous = first.copy(nodes = first.nodes + first.nodes.last().copy(id = "chat-other")).withDefinitionIdentity()
        assertTrue(ambiguous.nodes.first().ports.single().definitionId.isEmpty())
        assertTrue(ambiguous.wires.single().definitionId.isEmpty())
    }

    @Test fun servedExecutionUsesItsOwnGraphSubjectAndPreservesTheRemoteParent() = runTest {
        Reaktor.web()
        val sink = SpanBuffer()
        val graph = Graph(label = "worker")
        val node = graph.serve(object : DefinitionProbe() {
            override val ping = super.ping.handle { DefinitionResponse() }
        }, Spans("worker", sink) { mapOf(ServiceCall.BuildAttribute to "binary", ServiceCall.EnvironmentAttribute to "dev") })
        val shape = graph.shape()
        val parent = TraceContext.root()
        requireNotNull(node.served).call(DefinitionProbe().ping, DefinitionRequest().apply {
            attributes[ServiceCall.NodeAttribute] = "caller-node"
            attributes[ServiceCall.GraphAttribute] = "caller-graph"
        }, Metadata(mapOf(TraceContext.Header to parent.traceparent, Metadata.Baggage to "reaktor-session=phone-session")))
        val recorded = sink.snapshot().single()
        assertEquals(parent.traceId, recorded.traceId)
        assertEquals(parent.spanId, recorded.parentId)
        assertEquals("phone-session", recorded.applicationSession)
        assertEquals(shape.definitionDigest, recorded.graphDigest)
        val subject = shape.nodes.single { it.id == node.id.toString() }
        assertEquals(subject.definitionId, recorded.node)
        assertEquals(subject.ports.single { it.type == contractType(DefinitionProbe()).type }.definitionId, recorded.port)
        assertEquals(graph.id.toString(), recorded.activation)
        graph.close()
    }
}

@Serializable private class DefinitionRequest : Request()
@Serializable private class DefinitionResponse : Response()

private open class DefinitionProbe : Service("test.messaging") {
    open val ping by get<DefinitionRequest, DefinitionResponse>("/ping")
}
