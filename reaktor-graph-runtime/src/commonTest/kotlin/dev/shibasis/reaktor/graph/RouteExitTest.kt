package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.node.currentRoute
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.autoWire
import dev.shibasis.reaktor.graph.core.definitionArtifact
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.Route
import dev.shibasis.reaktor.graph.core.node.RouteBinding
import dev.shibasis.reaktor.graph.core.node.exit
import dev.shibasis.reaktor.graph.core.requireFullyWired
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.graph.navigation.Payload
import kotlin.test.*

class RouteExitTest {
    class Inbox : RouteBinding<Payload>(Payload()) {
        val thread by exit<Thread>()
        val profile by exit<Profile>()
    }

    class Thread : RouteBinding<Payload>(Payload()) {
        val inbox by exit<Inbox>()
    }

    class Profile : RouteBinding<Payload>(Payload()) {
        val tabs by exit<ContainerNode>()
    }

    @Test fun exitsWireByDestinationTypeAcrossGraphsAndContainers() {
        Reaktor.web()
        val root = Graph(label = "application")
        val left = Graph(parentGraph = root, label = "left")
        val right = Graph(parentGraph = root, label = "right")
        val container = ContainerNode(root, "/tabs", arrayListOf(left, right)).also { root.attach(it) }
        val inboxRoute = left.Route("/inbox") { Inbox() }
        val threadRoute = left.Route("/inbox/{id}") { Thread() }
        val profileRoute = right.Route("/profile") { Profile() }
        root.addRoot(container.route, Payload())
        left.addRoot(inboxRoute, Payload())
        right.addRoot(profileRoute, Payload())

        left.autoWire()
        right.autoWire()
        assertFailsWith<IllegalArgumentException> { left.requireFullyWired() }
        left.autoWire(right)
        left.requireFullyWired()
        right.requireFullyWired()

        val inbox = inboxRoute.routeBinding.impl
        assertSame(threadRoute, inbox.thread.end)
        assertSame(inboxRoute, threadRoute.routeBinding.impl.inbox.end)
        assertSame(container.route, profileRoute.routeBinding.impl.tabs.end)
        assertSame(inbox.thread, inboxRoute.edge(threadRoute))
        assertEquals(setOf(threadRoute, profileRoute), inboxRoute.navigationTargets().toSet())

        inbox.run { profile.push(Payload()) }
        assertSame(right, container.activeGraph)
        assertSame(profileRoute, right.currentRoute)

        val shape = root.shape()
        assertTrue(shape.wires.any { it.consumer == inboxRoute.id.toString() && it.consumerPort == "profile" && it.provider == profileRoute.id.toString() })
        assertEquals(shape.definitionDigest, shape.definitionArtifact().definitionDigest)
        root.close()
    }

    @Test fun anExitReadBeforeWiringNamesWhatIsMissing() {
        val failure = assertFailsWith<IllegalStateException> { Inbox().thread }
        assertEquals("No Thread route is wired to thread, an exit of Inbox", failure.message)
    }
}
