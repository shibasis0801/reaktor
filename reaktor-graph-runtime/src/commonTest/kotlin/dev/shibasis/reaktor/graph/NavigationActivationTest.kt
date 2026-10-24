package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.Route
import dev.shibasis.reaktor.graph.core.node.RouteBinding
import dev.shibasis.reaktor.graph.navigation.Payload
import dev.shibasis.reaktor.graph.navigation.Pop
import dev.shibasis.reaktor.graph.navigation.Push
import dev.shibasis.reaktor.graph.navigation.Replace
import kotlinx.coroutines.CoroutineName
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*

class NavigationActivationTest {
    @Test fun forwardReplaceAndBackCaptureTheCurrentOperation() {
        Reaktor.web()
        val graph = Graph(label = "navigation")
        var origin: CoroutineContext = CoroutineName("start")
        graph.captureNavigationContext = { origin }
        val first = graph.Route("/first") { RouteBinding(Payload()) }
        val second = graph.Route("/second") { RouteBinding(Payload()) }
        val third = graph.Route("/third") { RouteBinding(Payload()) }
        graph.addRoot(first, Payload())
        val originalEntry = graph.backStack.top.value
        val start = requireNotNull(graph.navigationActivation.value)
        assertEquals(originalEntry?.id, start.entryId)
        assertEquals("start", start.context[CoroutineName]?.name)

        origin = CoroutineName("tap")
        graph.dispatch(Push(first.edge(second), Payload()))
        assertEquals(graph.backStack.top.value?.id, graph.navigationActivation.value?.entryId)
        assertEquals("tap", graph.navigationActivation.value?.context?.get(CoroutineName)?.name)
        origin = CoroutineName("replace")
        graph.dispatch(Replace(second.edge(third), Payload()))
        assertEquals("replace", graph.navigationActivation.value?.context?.get(CoroutineName)?.name)
        origin = CoroutineName("back")
        graph.dispatch(Pop)
        assertSame(originalEntry, graph.backStack.top.value)
        assertEquals(originalEntry?.id, graph.navigationActivation.value?.entryId)
        assertEquals("back", graph.navigationActivation.value?.context?.get(CoroutineName)?.name)
        assertEquals("start", start.context[CoroutineName]?.name)
        graph.close()
        assertNull(graph.navigationActivation.value)
    }

    @Test fun crossGraphForwardAndBackKeepTheActualNavigationOrigin() {
        Reaktor.web()
        val root = Graph(label = "application")
        val left = Graph(parentGraph = root, label = "left")
        val right = Graph(parentGraph = root, label = "right")
        val tabs = ContainerNode(root, "/tabs", arrayListOf(left, right)).also { root.attach(it) }
        val from = left.Route("/left") { RouteBinding(Payload()) }
        val to = right.Route("/right") { RouteBinding(Payload()) }
        root.addRoot(tabs.route, Payload())
        left.addRoot(from, Payload())
        right.addRoot(to, Payload())
        var origin: CoroutineContext = CoroutineName("cross-graph tap")
        root.captureNavigationContext = { origin }
        left.dispatch(Push(from.edge(to), Payload()))
        assertSame(right, tabs.activeGraph)
        assertEquals(right.backStack.top.value?.id, right.navigationActivation.value?.entryId)
        assertEquals("cross-graph tap", right.navigationActivation.value?.context?.get(CoroutineName)?.name)
        val retainedRight = right.navigationActivation.value

        origin = CoroutineName("cross-graph back")
        right.dispatch(Pop)
        assertSame(left, tabs.activeGraph)
        assertEquals(left.backStack.top.value?.id, left.navigationActivation.value?.entryId)
        assertEquals("cross-graph back", left.navigationActivation.value?.context?.get(CoroutineName)?.name)
        assertEquals("cross-graph tap", retainedRight?.context?.get(CoroutineName)?.name)
        left.close()
        right.close()
        root.close()
    }

    @Test fun tabActivationDoesNotReuseAnEarlierTapOrAnotherGraphsContext() {
        Reaktor.web()
        val root = Graph(label = "application")
        val left = Graph(parentGraph = root, label = "left")
        val right = Graph(parentGraph = root, label = "right")
        val tabs = ContainerNode(root, "/tabs", arrayListOf(left, right)).also { root.attach(it) }
        left.addRoot(left.Route("/left") { RouteBinding(Payload()) }, Payload())
        right.addRoot(right.Route("/right") { RouteBinding(Payload()) }, Payload())
        var origin: CoroutineContext = CoroutineName("left tap")
        root.captureNavigationContext = { origin }
        assertTrue(tabs.activate(left))
        val leftActivation = left.navigationActivation.value
        origin = CoroutineName("right tap")
        assertTrue(tabs.activate(right))
        assertSame(leftActivation, left.navigationActivation.value)
        assertEquals("right tap", right.navigationActivation.value?.context?.get(CoroutineName)?.name)
        origin = EmptyCoroutineContext
        assertTrue(tabs.activate(left))
        assertEquals(EmptyCoroutineContext, left.navigationActivation.value?.context)
        assertNotSame(leftActivation, left.navigationActivation.value)
        left.close()
        right.close()
        root.close()
    }

    @Test fun navigationWithoutAnObserverDoesNotRetainAnActivation() {
        Reaktor.web()
        val graph = Graph(label = "unobserved")
        val first = graph.Route("/first") { RouteBinding(Payload()) }
        val second = graph.Route("/second") { RouteBinding(Payload()) }
        graph.addRoot(first, Payload())
        graph.dispatch(Push(first.edge(second), Payload()))
        graph.dispatch(Pop)
        assertNull(graph.navigationActivation.value)
        graph.close()
    }
}
