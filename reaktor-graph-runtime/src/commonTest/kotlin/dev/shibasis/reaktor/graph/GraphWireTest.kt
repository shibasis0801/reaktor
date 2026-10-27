package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.node.currentRoute
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.node.Root
import dev.shibasis.reaktor.graph.core.node.Route
import dev.shibasis.reaktor.graph.core.node.RouteBinding
import dev.shibasis.reaktor.graph.core.node.exit
import dev.shibasis.reaktor.graph.core.node.provide
import dev.shibasis.reaktor.graph.core.wire
import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides
import kotlin.test.*

class GraphWireTest {
    class Clock(graph: Graph) : BasicNode(graph) {
        val clock by provides<Clock>(this)
    }

    class Reader(graph: Graph) : BasicNode(graph) {
        val clock by consumes<Clock>()
        val greeting by consumes<String>()
    }

    class Feed : RouteBinding<Payload>(Payload()) {
        val profile by exit<Profile>()
    }

    class Profile : RouteBinding<Payload>(Payload())

    private fun application(): Graph {
        Reaktor.web()
        return Graph(label = "application")
    }

    @Test fun featuresOfOneSessionReachEachOtherWithoutWiringLists() {
        val app = application()
        app.provide("hello", "greeting")
        val session = app.Graph { Graph(it, label = "session") }
        val feed = session.Graph { Graph(it, label = "feed") }
        val profile = session.Graph { Graph(it, label = "profile") }
        val reader = feed.Node(::Reader)
        val home = feed.Root("/feed", ::Feed) { BasicNode(it) }
        val clock = profile.Node(::Clock)
        val page = profile.Route("/profile", ::Profile) { BasicNode(it) }

        session.wire()

        assertSame(clock, reader.clock())
        assertEquals("hello", reader.greeting())
        assertSame(page, home.routeBinding.impl.profile.end)
        assertSame(home, feed.currentRoute)
        assertSame(app.coroutineDispatcher, feed.coroutineDispatcher)
        app.close()
    }

    @Test fun theNearestProviderWinsAndATieIsNamed() {
        val app = application()
        app.provide("hello", "greeting")
        val session = app.Graph { Graph(it, label = "session") }
        val feed = session.Graph { Graph(it, label = "feed") }
        val cache = feed.Graph { Graph(it, label = "cache") }
        val profile = session.Graph { Graph(it, label = "profile") }
        val reader = feed.Node(::Reader)
        val near = cache.Node(::Clock)
        profile.Node(::Clock)

        session.wire()
        assertSame(near, reader.clock())

        session.Node(::Reader)
        val tie = assertFailsWith<IllegalArgumentException> { session.wire() }
        assertTrue("can take" in tie.message.orEmpty())
        app.close()
    }

    @Test fun aNameThatMatchesNothingTakesTheOnlyProviderOfItsType() {
        val app = application()
        app.provide("hello")
        val feed = app.Graph { Graph(it, label = "feed") }
        feed.Node(::Clock)
        val reader = feed.Node(::Reader)

        app.wire()
        assertEquals("hello", reader.greeting())
        app.close()
    }

    @Test fun anExactNameFartherAwayStillBeatsTheOnlyProviderNearby() {
        val app = application()
        app.provide("hello", "greeting")
        val session = app.Graph { Graph(it, label = "session") }
        session.provide("nearby")
        val feed = session.Graph { Graph(it, label = "feed") }
        feed.Node(::Clock)
        val reader = feed.Node(::Reader)

        app.wire()
        assertEquals("hello", reader.greeting())
        app.close()
    }
}
