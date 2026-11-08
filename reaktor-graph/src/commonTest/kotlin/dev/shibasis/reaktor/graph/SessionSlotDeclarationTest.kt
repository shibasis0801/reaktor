package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Route
import dev.shibasis.reaktor.graph.core.node.RouteBinding
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.graph.ui.SessionSlot
import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.port.KeyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionSlotDeclarationTest {
    private class Workspace(parent: Graph) : Graph(parentGraph = parent, label = "workspace") {
        init {
            Route("/learn") { RouteBinding(Payload()) }
            val type = KeyType("service", "workspace.service")
            val provider = BasicNode(this).also { attach(it) }
            val output = provider.registerProvider(type, "real-service")
            val consumer = BasicNode(this).also { attach(it) }
            connect(consumer.registerConsumer<String>(type), output).getOrThrow()
        }
    }

    @Test fun inactiveDefinitionPublishesRealRoutesAndPortsWithoutAnActivation() {
        val dependencies = koinApplication {}
        val root = Graph(dependencyAdapter = KoinDependencyAdapter(dependencies))
        var builds = 0
        val slot = SessionSlot(root, "/workspace", MutableStateFlow<String?>(null)) {
            builds++
            Workspace(root)
        }
        val definition = Workspace(root)
        val shape = definition.shape()
        definition.close()
        try {
            slot.declare(shape)
            root.attach(slot)
            assertEquals(0, builds)
            assertNull(slot.child.value)
            assertFalse(definition.coroutineScope.isActive)
            assertTrue(root.owned.isEmpty())
            val published = root.shape()
            assertTrue(published.nodes.any { it.route == "/learn" })
            assertTrue(published.wires.any { it.type == "workspace.service" })
            assertTrue(published.scopes.any { it.label == "workspace" && !it.active })
            assertEquals(shape, slot.dormant)
        } finally { root.close(); dependencies.close() }
    }

    @Test fun realIdentityActivatesAndRetiresWithoutReplacingTheInactiveDefinition() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val dependencies = koinApplication {}
        val root = Graph(dependencyAdapter = KoinDependencyAdapter(dependencies))
        val identity = MutableStateFlow<String?>(null)
        val slot = SessionSlot(root, "/workspace", identity) { Workspace(root) }
        val definition = Workspace(root)
        val shape = definition.shape()
        definition.close()
        slot.declare(shape)
        root.attach(slot)
        try {
            slot.start()
            assertNull(slot.child.value)
            identity.value = "actual-owner"
            val activation = slot.child.filterNotNull().first()
            assertEquals("actual-owner", slot.identityOf(activation))
            assertNull(slot.dormant)
            identity.value = null
            slot.child.first { it == null }
            activation.coroutineScope.coroutineContext[Job]?.join()
            assertFalse(activation.coroutineScope.isActive)
            assertEquals(shape, slot.dormant)
            assertTrue(root.owned.isEmpty())
        } finally {
            root.close()
            dependencies.close()
            Dispatchers.resetMain()
        }
    }
}
