package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.graph.core.shapes
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.KeyType
import dev.shibasis.reaktor.portgraph.port.Type
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class GraphShapeObservationTest {
    @Test fun structuralChangesPublishWithoutNavigation() = runTest {
        Reaktor.web()
        val root = Graph(dispatcher = StandardTestDispatcher(testScheduler), label = "application")
        val snapshots = mutableListOf<GraphShape>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { root.shapes().collect { snapshots += it } }
        try {
            runCurrent()
            val initial = snapshots.last().definitionDigest
            val child = root.Graph { Graph(it, label = "workspace") }
            val node = child.Node { BasicNode(it) }
            runCurrent()
            assertTrue(snapshots.last().nodes.any { it.id == node.id.toString() })
            assertNotEquals(initial, snapshots.last().definitionDigest)
            child.close()
            runCurrent()
            assertFalse(snapshots.last().scopes.any { it.label == "workspace" })
            assertEquals(initial, snapshots.last().definitionDigest)
        } finally { root.close() }
    }

    @Test fun keyTypePortsKeepTheirNodeOwnersAndLiveWireChanges() = runTest {
        Reaktor.web()
        val root = Graph(dispatcher = StandardTestDispatcher(testScheduler))
        val source = root.Node { BasicNode(it) }
        val target = root.Node { BasicNode(it) }
        val type = KeyType(Key("workspace"), Type("workspace.model"))
        val provider = source.registerProvider(type, "model")
        val consumer = target.registerConsumer<String>(type)
        val snapshots = mutableListOf<GraphShape>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { root.shapes().collect { snapshots += it } }
        try {
            runCurrent()
            assertSame(source, provider.owner)
            assertSame(target, consumer.owner)
            connect(consumer, provider).getOrThrow()
            runCurrent()
            val wire = snapshots.last().wires.single()
            assertEquals(source.id.toString(), wire.provider)
            assertEquals(target.id.toString(), wire.consumer)
            assertEquals("workspace", wire.consumerPort)
            assertSame(provider.impl, consumer.impl)
            consumer.close()
            runCurrent()
            assertTrue(snapshots.last().wires.isEmpty())
            assertEquals(root.shape().definitionDigest, snapshots.last().definitionDigest)
        } finally { root.close() }
    }
}
