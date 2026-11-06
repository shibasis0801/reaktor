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
import dev.shibasis.reaktor.graph.core.provides
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

    class FeedGraph(parent: Graph) : Graph(parent, label = "feed") {
        val reader = Node(::Reader)
        val home = Root("/feed", ::Feed) { BasicNode(it) }
    }

    class ProfileGraph(parent: Graph) : Graph(parent, label = "profile") {
        val clock by provides(Node(::Clock))
        val page by provides(Route("/profile", ::Profile) { BasicNode(it) })
    }

    class Cache(parent: Graph) : Graph(parent, label = "cache") {
        val clock by provides(Node(::Clock))
    }

    private fun application(): Graph {
        Reaktor.web()
        return Graph(label = "application")
    }

    @Test fun featuresOfOneSessionReachWhatTheOthersOffer() {
        val app = application()
        app.provide("hello", "greeting")
        val session = app.Graph { Graph(it, label = "session") }
        val feed = session.Graph(::FeedGraph)
        val profile = session.Graph(::ProfileGraph)

        session.wire()

        assertSame(profile.clock, feed.reader.clock())
        assertEquals("hello", feed.reader.greeting())
        assertSame(profile.page, feed.home.routeBinding.impl.profile.end)
        assertSame(feed.home, feed.currentRoute)
        assertSame(app.coroutineDispatcher, feed.coroutineDispatcher)
        app.close()
    }

    @Test fun whatAGraphDoesNotOfferStaysInside() {
        val app = application()
        app.provide("hello", "greeting")
        val session = app.Graph { Graph(it, label = "session") }
        val feed = session.Graph { Graph(it, label = "feed") }
        val profile = session.Graph { Graph(it, label = "profile") }
        feed.Node(::Reader)
        profile.Node(::Clock)

        val refusal = assertFailsWith<IllegalArgumentException> { session.wire() }
        assertTrue("offer it with `by provides` from 'profile'" in refusal.message.orEmpty())
        app.close()
    }

    @Test fun theNearestOfferWinsAndATieIsNamed() {
        val app = application()
        app.provide("hello", "greeting")
        val session = app.Graph { Graph(it, label = "session") }
        val feed = session.Graph { Graph(it, label = "feed") }
        val near = feed.Graph(::Cache)
        val far = session.Graph(::Cache)
        val reader = feed.Node(::Reader)

        session.wire()
        assertSame(near.clock, reader.clock())

        val tied = session.Graph(::Cache)
        session.Node(::Reader)
        val tie = assertFailsWith<IllegalArgumentException> { session.wire() }
        assertTrue(far.clock.toString() in tie.message.orEmpty() && tied.clock.toString() in tie.message.orEmpty())
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
