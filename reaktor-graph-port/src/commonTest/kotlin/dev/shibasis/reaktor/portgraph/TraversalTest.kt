package dev.shibasis.reaktor.portgraph

import dev.shibasis.reaktor.portgraph.graph.PortGraph
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.portgraph.node.PortNode
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import dev.shibasis.reaktor.portgraph.traversal.*
import kotlin.test.*

class TraversalTest {
    private class Graph : PortGraph<Graph, PortNode<Graph>>()
    private class Element(val name: String) : Visitable

    @Test fun depthFirstNestsWithParentsAndDepths() {
        val root = Element("root")
        val left = Element("left")
        val leaf = Element("leaf")
        val right = Element("right")
        val relation = Relation { when (it) {
            root -> sequenceOf(left, right)
            left -> sequenceOf(leaf)
            right -> sequenceOf(leaf, root)
            else -> emptySequence()
        } }
        assertEquals(listOf(
            Step.Enter(root, null, 0), Step.Enter(left, root, 1), Step.Enter(leaf, left, 2),
            Step.Leave(leaf, left, 2), Step.Leave(left, root, 1), Step.Enter(right, root, 1),
            Step.Leave(right, root, 1), Step.Leave(root, null, 0),
        ), root.depthFirst(relation).toList())
    }

    @Test fun declarationOrderSurvivesDetach() {
        val graph = Graph()
        val nodes = List(50) { PortNode(graph, label = "node-$it").also { graph.attach(it) } }
        assertEquals(nodes, graph.nodes.toList())
        listOf(0, 7, 24, 49).forEach { assertTrue(graph.detach(nodes[it])) }
        assertEquals(nodes.filterIndexed { index, _ -> index !in listOf(0, 7, 24, 49) }, graph.nodes.toList())
    }

    @Test fun prunedElementsStillEnterAndLeave() {
        val root = Element("root")
        val branch = Element("branch")
        val leaf = Element("leaf")
        val relation = Relation { when (it) { root -> sequenceOf(branch); branch -> sequenceOf(leaf); else -> emptySequence() } }
        assertEquals(listOf(Step.Enter(root, null, 0), Step.Enter(branch, root, 1),
            Step.Leave(branch, root, 1), Step.Leave(root, null, 0)), root.depthFirst(relation) { it !== branch }.toList())
        assertEquals(listOf(Step.Enter(root, null, 0), Step.Leave(root, null, 0)), root.depthFirst(relation) { false }.toList())
    }

    @Test fun breadthFirstYieldsOnlyEntersInLevelOrder() {
        val root = Element("root")
        val left = Element("left")
        val right = Element("right")
        val leaf = Element("leaf")
        val relation = Relation { when (it) { root -> sequenceOf(left, right); left -> sequenceOf(leaf); right -> sequenceOf(root, leaf); else -> emptySequence() } }
        assertEquals(listOf(Step.Enter(root, null, 0), Step.Enter(left, root, 1), Step.Enter(right, root, 1), Step.Enter(leaf, left, 2)), root.breadthFirst(relation).toList())
        assertEquals(listOf(root, left, right), root.breadthFirst(relation) { it !== left && it !== right }.map { it.element }.toList())
    }

    @Test fun wiredRelationsAndContainsFollowConsumerOwnership() {
        val graph = Graph()
        val provider = PortNode(graph, label = "provider").also { graph.attach(it) }
        val consumer = PortNode(graph, label = "consumer").also { graph.attach(it) }
        val output = provider.registerProvider("message", "hello")
        val inputs = List(2) { consumer.registerConsumer<String>("input-$it") }
        inputs.forEach { connect(it, output).getOrThrow() }
        assertEquals(listOf(provider), Upstream.next(consumer).toList())
        assertEquals(listOf(consumer), Downstream.next(provider).toList())
        assertEquals(listOf(provider, consumer), listOf(consumer, provider).topological())
        assertTrue(Upstream.next(provider).none())
        assertTrue(Downstream.next(consumer).none())
        val steps = graph.depthFirst(Contains).filterIsInstance<Step.Enter>().toList()
        inputs.forEach { input ->
            val step = steps.single { it.element === input.edge }
            assertSame(input, step.parent)
            assertEquals(3, step.depth)
        }
        assertEquals(2, steps.count { step -> inputs.any { it.edge === step.element } })
    }

    @Test fun topologicalKeepsDeclarationTiesAndNamesEveryCyclePair() {
        val graph = Graph()
        val nodes = listOf("first", "second", "third", "fourth").map { PortNode(graph, label = it) }
        val relation = Relation { if (it === nodes[0]) sequenceOf(nodes[2]) else emptySequence() }
        assertEquals(listOf(nodes[1], nodes[2], nodes[0], nodes[3]), nodes.topological(relation))
        val cycle = Relation { when (it) { nodes[0] -> sequenceOf(nodes[1]); nodes[1] -> sequenceOf(nodes[0]); else -> emptySequence() } }
        val failure = assertFailsWith<IllegalArgumentException> { nodes.topological(cycle) }
        assertTrue("'first' waits on 'second'" in failure.message.orEmpty())
        assertTrue("'second' waits on 'first'" in failure.message.orEmpty())
    }
}
