package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.autoWire
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.contract.CallAttributes
import dev.shibasis.reaktor.service.ServiceExecutionPhase
import dev.shibasis.reaktor.service.SpanBuffer
import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.Exchange
import dev.shibasis.reaktor.service.contract.Interceptor
import dev.shibasis.reaktor.service.contract.LocalLink
import dev.shibasis.reaktor.service.contract.Next
import dev.shibasis.reaktor.service.contract.Position
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.service.contract.Spans
import dev.shibasis.reaktor.service.contract.invoke
import dev.shibasis.reaktor.service.contract.over
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import dev.shibasis.reaktor.service.contract.CallMetadata
import dev.shibasis.reaktor.service.contract.Side
import kotlinx.coroutines.withContext

@Serializable
data class Add(val amount: Int)

@Serializable
data class Total(val value: Int)

open class Counter : Service("test.counter") {
    open val add by post<Add, Total>("/add")
    open val total by call<Unit, Total>(effect = Effect.ReadOnly)
}

class CounterServer : Counter() {
    private var value = 0

    override val add = super.add.handle { request -> Total(value + request.amount).also { value = it.value } }
    override val total = super.total.handle { Total(value) }
}

class CounterScreen(graph: Graph) : BasicNode(graph) {
    val counter by consumes(Counter())
}

class Adder(graph: Graph) : BasicNode(graph) {
    val add by consumes(Counter().add)
}

private class Seen(private val label: String, override val position: Int, private val log: MutableList<String>) : Interceptor {
    override val name: String get() = label
    override suspend fun <In, R> intercept(exchange: Exchange<In, R>, next: Next<In, R>): R {
        log += "$label:${exchange.side}"
        return next(exchange)
    }
}

class ContractNodeTest {
    @Test
    fun servedContractsWireToTheNodesThatUseThem() = runTest {
        Reaktor.web()
        val graph = Graph(label = "app")
        val server = graph.serve(CounterServer())
        val screen = graph.Node { CounterScreen(it) }
        graph.autoWire()

        assertEquals(Total(5), screen.counter.add(Add(5)))
        assertEquals(Total(5), screen.counter.total())

        val shape = graph.shape()
        val node = shape.nodes.single { it.id == server.id.toString() }
        assertEquals(Counter().fingerprint, node.contract?.fingerprint)
        assertEquals("server", node.attributes["role"])
        assertEquals("test.counter", node.label)
        assertEquals(listOf("add", "total"), node.operations.map { it.operation }.sorted())
        assertTrue(shape.wires.any { it.consumer == screen.id.toString() && it.type == "contract:test.counter" })
        graph.close()
    }

    @Test
    fun aRelayStandsInForTheNodeItRelays() = runTest {
        Reaktor.web()
        val root = Graph(label = "app")
        val mock = root.Graph { Graph(parentGraph = it, label = "mock") }
        val echo = root.Graph { Graph(parentGraph = it, label = "echo") }
        val served = mock.serve(CounterServer())
        val relayed = echo.relay(served)
        val screen = echo.Node { CounterScreen(it) }
        val adder = echo.Node { Adder(it) }
        echo.autoWire()

        assertEquals(Total(3), screen.counter.add(Add(3)))
        assertEquals(Total(5), adder.add(Add(2)))
        assertSame(served, relayed.origin)
        assertSame(served, root.relay(relayed).origin)
        assertEquals("test.counter", relayed.label)
        assertEquals(NodeKind.Service, relayed.kind)
        assertTrue(root.shape().wires.any { it.consumer == relayed.id.toString() && it.provider == served.id.toString() })
        root.close()
    }

    @Test
    fun graphInterceptorsReachContractsInTheGraphAndItsChildren() = runTest {
        Reaktor.web()
        val log = mutableListOf<String>()
        val root = Graph(label = "root")
        val child = Graph(parentGraph = root, label = "child")
        root.intercept(Seen("root", Position.Application, log))
        child.intercept(Seen("child", Position.Telemetry, log))
        val remote = child.remote(Counter() over LocalLink(CounterServer()))
        val screen = child.Node { CounterScreen(it) }
        child.autoWire()

        screen.counter.add(Add(2))

        assertEquals(listOf("child:Client", "root:Client"), log)
        val shown = child.shape().nodes.single { it.id == remote.id.toString() }
        assertEquals("client", shown.attributes["role"])
        assertEquals(listOf("child" to "Graph", "root" to "Graph"), shown.interceptors.map { it.name to it.scope })
        root.close()
    }

    @Test
    fun callMetadataCrossesThePortIntoTheRemoteCall() = runTest {
        Reaktor.web()
        val seen = mutableListOf<String?>()
        val graph = Graph(label = "app")
        graph.remote(Counter() over LocalLink(CounterServer()), object : Interceptor {
            override val sides: Set<Side> get() = setOf(Side.Client)
            override suspend fun <In, R> intercept(exchange: Exchange<In, R>, next: Next<In, R>): R { seen += exchange.metadata["x-caller"]; return next(exchange) }
        })
        val adder = graph.Node { Adder(it) }
        graph.autoWire()

        withContext(CallMetadata(mapOf("x-caller" to "ada"))) { adder.add(Add(1)) }
        adder.add(Add(1))

        assertEquals(listOf("ada", null), seen)
        graph.close()
    }

    @Test
    fun callerAttributesCrossBothPortFormsWithoutLeaking() = runTest {
        Reaktor.web()
        val seen = mutableListOf<Pair<String?, String?>>()
        val graph = Graph(label = "owner")
        graph.intercept(object : Interceptor {
            override val sides: Set<Side> get() = setOf(Side.Server)
            override suspend fun <In, R> intercept(exchange: Exchange<In, R>, next: Next<In, R>): R {
                seen += (exchange.attributes["forward.test.caller"] as? String) to
                    (exchange.attributes[ServiceCall.EdgeAttribute] as? String)
                return next(exchange)
            }
        })
        graph.serve(CounterServer())
        val adder = graph.Node { Adder(it) }
        val screen = graph.Node { CounterScreen(it) }
        graph.autoWire()
        try {
            withContext(CallAttributes(mapOf("forward.test.caller" to "ada"))) {
                adder.add(Add(1))
                screen.counter.add(Add(1))
            }
            adder.add(Add(1))
            assertEquals(listOf("ada", "ada", null), seen.map { it.first })
            assertTrue(seen.all { !it.second.isNullOrBlank() })
        } finally {
            graph.close()
        }
    }

    @Test
    fun callsCarryGraphIdentityIntoSpans() = runTest {
        Reaktor.web()
        val spans = SpanBuffer()
        val graph = Graph(label = "worker")
        graph.intercept(Spans("worker", spans))
        val server = graph.serve(CounterServer())
        val screen = graph.Node { CounterScreen(it) }
        graph.autoWire()

        screen.counter.add(Add(1))

        val shape = graph.shape()
        val node = shape.nodes.single { it.id == server.id.toString() }
        val wire = shape.wires.single { it.consumer == screen.id.toString() }
        val (client, served) = spans.snapshot().sortedBy { it.phase }
        assertEquals(ServiceExecutionPhase.CLIENT, client.phase)
        assertEquals(node.definitionId, served.node)
        assertEquals(node.ports.single { it.type == contractType(Counter()).type }.definitionId, served.port)
        assertEquals(shape.definitionDigest, served.graphDigest)
        assertEquals(graph.id.toString(), served.activation)
        assertEquals(wire.definitionId, client.edge)
        assertEquals(client.spanId, served.parentId)
        graph.close()
    }
}
