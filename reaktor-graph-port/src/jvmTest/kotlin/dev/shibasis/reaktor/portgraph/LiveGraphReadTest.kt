package dev.shibasis.reaktor.portgraph

import dev.shibasis.reaktor.portgraph.edge.Edge
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.PortCapabilityImpl
import dev.shibasis.reaktor.portgraph.port.PortEvent
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.Type
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import dev.shibasis.reaktor.portgraph.port.registerConsumer
import dev.shibasis.reaktor.portgraph.port.registerProvider
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A graph must be readable while it is wiring itself.
 *
 * Every DevTools surface, the flow projection and telemetry instrumentation read a graph that is
 * still registering ports and connecting edges on other threads. When the port index was
 * `MutableMap<Type, MutableMap<Key, Port>>` over plain `linkedMapOf`, those readers hit
 * `ConcurrentModificationException`, and the only defence was a retry loop at each call site.
 *
 * **Every test here asserts that the race actually happened.** A reader thread that is scheduled
 * after the writer has already finished proves nothing, and a concurrency test that can pass
 * without overlapping is worse than no test — so [race] holds the writer until every reader is
 * demonstrably looping, and each caller then asserts it observed a partially built graph.
 */
class LiveGraphReadTest {

    private interface Contract { fun call(): Int }

    private class Impl(private val value: Int) : Contract {
        override fun call() = value
    }

    /** Enough that a reader's pass over the index is slow relative to one registration. */
    private val portCount = 500

    /** What a set of readers saw while a writer mutated the thing they were reading. */
    private class Race(val failures: List<Throwable>, val observed: List<Int>) {
        /** True when a reader saw the structure part-built — the evidence that this raced at all. */
        val overlapped: Boolean get() = observed.any { it > 0 } && observed.any { it < observed.max() }
    }

    /**
     * Runs [write] against [read] with guaranteed overlap.
     *
     * Readers start first and count down [reading] once each is inside its loop; the writer only
     * begins after all of them have. Readers then keep going until the writer sets `done`.
     */
    private fun race(readers: Int = 3, read: () -> Int, write: () -> Unit): Race {
        val pool = Executors.newFixedThreadPool(readers + 1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val observed = ConcurrentLinkedQueue<Int>()
        val reading = CountDownLatch(readers)
        val done = AtomicBoolean(false)
        try {
            val readerTasks = (0 until readers).map {
                pool.submit {
                    var announced = false
                    while (!done.get()) {
                        runCatching { observed.add(read()) }.onFailure(failures::add)
                        if (!announced) {
                            announced = true
                            reading.countDown()
                        }
                    }
                }
            }
            val writerTask = pool.submit {
                check(reading.await(30, TimeUnit.SECONDS)) { "readers never started" }
                try { write() } finally { done.set(true) }
            }
            (readerTasks + writerTask).forEach { it.get(120, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        return Race(failures.toList(), observed.toList())
    }

    @Test
    fun readingPortsWhileTheyRegisterNeverThrows() {
        val node = PortCapabilityImpl()
        val race = race(
            read = {
                node.providerPorts.flattenedValues().onEach { it.key.key }.size
            },
            write = {
                repeat(portCount) { index ->
                    node.registerProvider(Key("provider-$index"), Type("Contract$index"), Impl(index))
                    node.registerConsumer<Contract>(Key("consumer-$index"), Type("Contract$index"))
                }
            },
        )

        assertTrue(race.overlapped, "readers never saw a partially built index; this proved nothing")
        assertTrue(race.failures.isEmpty(), "readers saw ${race.failures.size} failures, first: ${race.failures.firstOrNull()}")
        assertEquals(portCount, node.providerPorts.flattenedValues().size)
        assertEquals(portCount, node.consumerPorts.flattenedValues().size)
    }

    @Test
    fun traversingAProviderWhileItConnectsNeverThrows() {
        val provider = PortCapabilityImpl()
        val type = Type("Contract")
        val providerPort = provider.registerProvider<Contract>(Key(""), type, Impl(0))
        val consumers = (0 until portCount).map { index ->
            PortCapabilityImpl().registerConsumer<Contract>(Key("consumer-$index"), type)
        }

        val race = race(
            read = {
                providerPort.edges.values.onEach { it.id }.size.also { providerPort.edges.keys.toList() }
            },
            write = {
                consumers.forEach { consumer -> Edge(consumer.owner, consumer, provider, providerPort) }
            },
        )

        assertTrue(race.overlapped, "readers never saw a partially connected provider; this proved nothing")
        assertTrue(race.failures.isEmpty(), "readers saw ${race.failures.size} failures, first: ${race.failures.firstOrNull()}")
        assertEquals(portCount, providerPort.edges.size)
    }

    @Test
    fun disposalIsVisibleToAReaderAsWholeStates() {
        val provider = PortCapabilityImpl()
        val type = Type("Contract")
        val providerPort = provider.registerProvider<Contract>(Key(""), type, Impl(0))
        val consumers = (0 until portCount).map { index ->
            PortCapabilityImpl().registerConsumer<Contract>(Key("consumer-$index"), type)
        }
        consumers.forEach { consumer -> Edge(consumer.owner, consumer, provider, providerPort) }

        val race = race(readers = 2, read = { providerPort.edges.values.toList().size }, write = { providerPort.close() })

        assertTrue(race.overlapped, "readers never saw disposal in progress; this proved nothing")
        assertTrue(race.failures.isEmpty(), "readers saw ${race.failures.size} failures, first: ${race.failures.firstOrNull()}")
        assertEquals(0, providerPort.edges.size)
        consumers.forEach { assertNull(it.edge, "close() must detach every consumer") }
    }

    @Test
    fun racingRegistrationsOfOnePortAgreeOnOneWinnerAndAnnounceItOnce() {
        val node = PortCapabilityImpl()
        val created = ConcurrentLinkedQueue<PortEvent.Created>()
        node.addPortEventListener { event -> if (event is PortEvent.Created) created.add(event) }
        val impl = Impl(1)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val ports = ConcurrentLinkedQueue<ProviderPort<Contract>>()
        try {
            val racers = (0 until 8).map {
                pool.submit {
                    start.await()
                    ports.add(node.registerProvider(Key("shared"), Type("Contract"), impl))
                }
            }
            start.countDown()
            racers.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val winner = ports.first()
        assertTrue(ports.all { it === winner }, "every caller must receive the same port instance")
        assertEquals(1, node.providerPorts.flattenedValues().size)
        // Read-then-write let two threads both create, both emit, and one silently overwrite the
        // other — leaving a port that its caller holds but the index does not contain.
        assertEquals(1, created.size, "Created must be announced exactly once per port")
        assertSame(winner, created.first().port)
    }

    @Test
    fun removingAContractsLastPortDropsItsTypeEntry() {
        val node = PortCapabilityImpl()
        val type = Type("Contract")
        node.registerProvider<Contract>(Key("a"), type, Impl(0))
        node.registerProvider<Contract>(Key("b"), type, Impl(1))

        assertEquals(2, node.providerPorts.flattenedValues().size)
        assertEquals(1, node.providerPorts.size, "both ports share one contract type")

        assertSame(node.providerPorts.get(type, Key("a")), node.providerPorts.remove(type, Key("a")))
        assertEquals(null, node.providerPorts.remove(type, Key("a")), "removing twice must report nothing was there")

        node.providerPorts.remove(type, Key("b"))
        assertEquals(0, node.providerPorts.size, "an emptied contract type must not linger in the index")
        assertTrue(node.providerPorts.isEmpty())
    }

    @Test
    fun portsReadBackInRegistrationOrder() {
        val node = PortCapabilityImpl()
        val type = Type("Contract")
        val keys = (0 until 20).map { "port-$it" }
        keys.forEach { node.registerProvider<Contract>(Key(it), type, Impl(0)) }

        assertEquals(keys, node.providerPorts.flattenedValues().map { it.key.key })
    }

    @Test
    fun aConsumerRegisteredTwiceIsTheSamePort() {
        val node = PortCapabilityImpl()
        val first = node.registerConsumer<Contract>(Key("only"), Type("Contract"))
        val second = node.registerConsumer<Contract>(Key("only"), Type("Contract"))

        assertSame(first, second)
        assertEquals(1, node.consumerPorts.flattenedValues().size)
    }

    /**
     * The harness is only worth having if it can still see the defect.
     *
     * Runs [readingPortsWhileTheyRegisterNeverThrows]'s access pattern against the structure
     * `TypedKeyedMap` replaced — two levels of `linkedMapOf`, flattened by iterating both. It is
     * expected to fail. If it ever stops, this file has gone blind and the tests above prove
     * nothing.
     */
    @Test
    fun theMutableMapItReplacedStillFailsThisHarness() {
        val index: MutableMap<Type, MutableMap<Key, ProviderPort<Contract>>> = linkedMapOf()
        val owner = PortCapabilityImpl()

        val race = race(
            read = { index.values.flatMap { it.values }.onEach { port -> port.key.key }.size },
            write = {
                repeat(portCount) { position ->
                    val type = Type("Contract$position")
                    val key = Key("provider-$position")
                    index.getOrPut(type) { linkedMapOf() }[key] = ProviderPort(owner, key, type, Impl(position))
                }
            },
        )

        assertTrue(race.overlapped, "the replaced structure was not read while it was written")
        assertTrue(
            race.failures.any { it is ConcurrentModificationException },
            "the replaced structure did not race; this harness can no longer detect the defect. " +
                "Saw ${race.failures.size} failures: ${race.failures.map { it::class.simpleName }.distinct()}",
        )
    }
}
