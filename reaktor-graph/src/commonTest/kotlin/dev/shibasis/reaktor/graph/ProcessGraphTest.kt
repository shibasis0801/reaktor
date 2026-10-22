package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.ProcessGraph
import dev.shibasis.reaktor.graph.core.autoWire
import dev.shibasis.reaktor.graph.core.exposePort
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.unconnectedConsumers
import dev.shibasis.reaktor.graph.di.DependencyAdapter
import dev.shibasis.reaktor.graph.di.DependencyScopeCapability
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The session-graph pattern: long-lived nodes in a graph with no routes, screens in a child of it.
 */
class ProcessGraphTest {
    private interface Player {
        fun nowPlaying(): String
    }

    private class PlayerNode(graph: Graph) : BasicNode(graph) {
        val player by provides<Player>(object : Player {
            override fun nowPlaying() = "episode 1"
        })
    }

    private class Session(adapter: DependencyAdapter<*>) : Graph(dependencyAdapter = adapter, label = "session") {
        val playerNode = Node(::PlayerNode).apply { exposePort(player) }

        init {
            autoWire()
        }
    }

    private class ScreenNode(graph: Graph) : BasicNode(graph) {
        // Across graphs the key must name the provider's property, not be left empty.
        val player by consumes<Player>("player")
        val selection by provides<String>("episode 2")
    }

    private class Ui(parent: Session) : Graph(parent, dependencyAdapter = parent.diAdapter, label = "ui") {
        val screen = Node(::ScreenNode)

        init {
            autoWire()
        }
    }

    @Test
    fun buildsOnceAndHandsBackTheSameGraph() {
        var builds = 0
        val adapter = ScopedTestAdapter()
        val session = ProcessGraph { builds++; Session(adapter) }

        assertNull(session.peek())
        val first = session.get()
        val second = session.get()

        assertSame(first, second)
        assertSame(first, session.peek())
        assertEquals(1, builds)
    }

    @Test
    fun aChildBuiltThroughTheParentReachesItsPortsByKey() {
        val adapter = ScopedTestAdapter()
        val session = ProcessGraph { Session(adapter) }
        val ui = ProcessGraph { Ui(session.get()) }

        val screen = ui.get().screen

        assertTrue(ui.get().unconnectedConsumers().isEmpty())
        assertEquals("episode 1", screen.player().nowPlaying())
    }

    @Test
    fun closingTheUiLeavesTheSessionPlaying() {
        val adapter = ScopedTestAdapter()
        val session = ProcessGraph { Session(adapter) }
        val ui = ProcessGraph { Ui(session.get()) }

        val before = ui.get()
        val playing = session.get()
        ui.close()

        assertNull(ui.peek())
        assertSame(playing, session.peek())
        assertEquals("episode 1", playing.playerNode.player().nowPlaying())

        // A new screen after the old one is gone finds the same player.
        val after = ui.get()
        assertNotSame(before, after)
        assertEquals("episode 1", after.screen.player().nowPlaying())
    }

    @Test
    fun theSessionCannotSeeAnythingItsChildrenProvide() {
        val adapter = ScopedTestAdapter()
        val sessionGraph = Session(adapter)
        val ui = Ui(sessionGraph)
        ui.screen.exposePort(ui.screen.selection)
        val qualifier = ui.screen.selection.qualifier

        // Lookups chain upward only: the child's scope can see the parent's, never the reverse.
        assertTrue(runCatching { ui.diScope.get(ProviderPort::class, qualifier) }.isSuccess)
        assertTrue(runCatching { sessionGraph.diScope.get(ProviderPort::class, qualifier) }.isFailure)
    }
}

/** A dependency adapter whose scopes chain to their parent, the way Koin's do. */
internal class ScopedTestAdapter : DependencyAdapter<Unit>(Unit) {
    override fun createScope(
        id: String,
        parent: DependencyScopeCapability?,
        configure: (ScopeBuilder.() -> Unit),
    ): DependencyScopeCapability = ChainedScope(id, parent as? ChainedScope)

    override fun closeScope(scope: DependencyScopeCapability) {
        scope.close()
    }

    override fun <T : Any> get(
        scope: DependencyScopeCapability,
        type: KClass<T>,
        qualifier: String?,
        parameters: Map<String, Any?>,
    ): T = (scope as ChainedScope).get(type, qualifier, parameters)

    override fun <T : Any> register(
        scope: DependencyScopeCapability,
        instance: T,
        type: KClass<T>,
        qualifier: String?,
    ) {
        (scope as ChainedScope).values[type to qualifier] = instance
    }
}

internal class ChainedScope(
    override val id: String,
    private val parent: ChainedScope?,
) : DependencyScopeCapability {
    val values = mutableMapOf<Pair<KClass<*>, String?>, Any>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(type: KClass<T>, qualifier: String?, parameters: Map<String, Any?>): T =
        (values[type to qualifier] ?: parent?.get(type, qualifier, parameters)) as? T
            ?: error("No dependency for ${type.simpleName} qualifier=$qualifier")

    override fun close() = values.clear()
}
