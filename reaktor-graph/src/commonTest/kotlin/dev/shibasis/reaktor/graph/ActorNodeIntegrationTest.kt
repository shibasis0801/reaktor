package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.db.Database
import dev.shibasis.reaktor.db.ObjectDatabase
import dev.shibasis.reaktor.db.StoredObject
import dev.shibasis.reaktor.graph.capabilities.Lifecycle
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.autoWire
import dev.shibasis.reaktor.graph.core.node.ActorDirective
import dev.shibasis.reaktor.graph.core.node.ActorNode
import dev.shibasis.reaktor.graph.core.node.ActorStatus
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Reply
import dev.shibasis.reaktor.graph.core.node.actorKey
import dev.shibasis.reaktor.graph.core.node.consumes
import dev.shibasis.reaktor.graph.core.node.findActor
import dev.shibasis.reaktor.graph.core.node.provides
import dev.shibasis.reaktor.graph.di.DependencyAdapter
import dev.shibasis.reaktor.graph.di.DependencyScopeCapability
import dev.shibasis.reaktor.io.serialization.TextSerializer
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ActorNodeIntegrationTest {
    @Test
    fun actorProcessesMessagesSequentiallyAndRepliesThroughAsk() = runTest {
        val graph = Graph(
            dispatcher = StandardTestDispatcher(testScheduler),
            dependencyAdapter = ActorTestDependencyAdapter(),
        )

        try {
            val actor = graph.provides(CounterKey, ::CounterActor)

            actor.send(CounterMessage.Add(1))
            actor.send(CounterMessage.Add(2))
            actor.send(CounterMessage.Add(3))

            val value = actor.ask { reply ->
                CounterMessage.Get(reply)
            }

            assertEquals(6, value)
            assertEquals(listOf(1, 3, 6), actor.observedCounts)
            assertTrue(actor.status.value is ActorStatus.Running)
        } finally {
            graph.close()
        }
    }

    @Test
    fun actorFailuresResumeByDefaultWithoutBypassingMailbox() = runTest {
        val graph = Graph(
            dispatcher = StandardTestDispatcher(testScheduler),
            dependencyAdapter = ActorTestDependencyAdapter(),
        )

        try {
            val actor = graph.provides(CounterKey, ::CounterActor)

            actor.send(CounterMessage.Add(2))
            actor.send(CounterMessage.Fail)
            actor.send(CounterMessage.Add(5))

            val value = actor.ask { reply ->
                CounterMessage.Get(reply)
            }

            assertEquals(7, value)
            assertEquals(1, actor.failures)
        } finally {
            graph.close()
        }
    }

    @Test
    fun actorsAreNormalNodesAndCanBeConsumedThroughPorts() = runTest {
        val graph = Graph(
            dispatcher = StandardTestDispatcher(testScheduler),
            dependencyAdapter = ActorTestDependencyAdapter(),
        )

        try {
            val actor = graph.provides(CounterKey, ::CounterActor)
            val consumer = CounterConsumer(graph)
            val duplicate = CounterActor(graph)

            graph.attach(consumer)
            graph.autoWire()

            assertSame(actor, graph.findActor<CounterMessage, CounterActor>(CounterKey))
            assertSame(actor, graph.node(CounterKey.nodeId))
            assertSame(actor, consumer.counter.impl)
            assertFalse(graph.attach(duplicate))
            duplicate.close()

            actor.send(CounterMessage.Add(4))
            val value = consumer.counter.impl!!.ask { reply ->
                CounterMessage.Get(reply)
            }

            assertEquals(4, value)
        } finally {
            graph.close()
        }
    }

    @Test
    fun actorStateUsesAnExclusiveObjectStore() = runTest {
        val database = ActorTestDatabase()
        Feature.Database = database

        val graph = Graph(
            dispatcher = StandardTestDispatcher(testScheduler),
            dependencyAdapter = ActorTestDependencyAdapter(),
        )

        try {
            val actor = graph.provides(StateKey, ::StateActor)

            actor.send(StateMessage.Set(42))
            val value = actor.ask { reply ->
                StateMessage.Get(reply)
            }

            assertEquals(42, value)
            assertEquals(listOf("actors/${StateKey.name}"), database.storeNames())
        } finally {
            graph.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun actorMailboxSurvivesSaveRestoreLifecycle() = runTest {
        val graph = Graph(
            dispatcher = StandardTestDispatcher(testScheduler),
            dependencyAdapter = ActorTestDependencyAdapter(),
        )

        try {
            val actor = graph.provides(CounterKey, ::CounterActor)

            actor.send(CounterMessage.Add(1))
            assertEquals(1, actor.ask { reply -> CounterMessage.Get(reply) })

            actor.transition(Lifecycle.Saving)
            advanceUntilIdle()

            actor.send(CounterMessage.Add(2))

            actor.transition(Lifecycle.Restoring)
            actor.transition(Lifecycle.Attaching)

            assertEquals(3, actor.ask { reply -> CounterMessage.Get(reply) })
        } finally {
            graph.close()
        }
    }

    @Test
    fun graphNodesRemainACollectionApiOverIndexedStorage() {
        val graph = Graph(dependencyAdapter = ActorTestDependencyAdapter())
        val first = BasicNode(graph)
        val second = BasicNode(graph)

        assertTrue(graph.attach(first))
        assertTrue(graph.attach(second))

        assertEquals(2, graph.nodes.size)
        assertTrue(graph.nodes.contains(first))
        assertTrue(graph.nodes.contains(second))
        assertEquals(first, graph.node(first.id))
        assertEquals(2, graph.nodes.filterIsInstance<BasicNode>().size)
        assertFalse(graph.attach(first))

        assertTrue(graph.detach(first))
        assertEquals(1, graph.nodes.size)
        assertFalse(graph.nodes.contains(first))
        assertEquals(null, graph.node(first.id))

        graph.close()
    }
}

private val CounterKey = actorKey<CounterMessage>("test.counter")
private val StateKey = actorKey<StateMessage>("test.state")

private sealed interface CounterMessage {
    data class Add(val value: Int) : CounterMessage
    data class Get(val reply: Reply<Int>) : CounterMessage
    data object Fail : CounterMessage
}

private class CounterActor(
    graph: Graph,
) : ActorNode<CounterMessage>(
    graph = graph,
    key = CounterKey,
) {
    private var count = 0
    val observedCounts = mutableListOf<Int>()
    var failures = 0

    override suspend fun receive(message: CounterMessage) {
        when (message) {
            is CounterMessage.Add -> {
                count += message.value
                observedCounts += count
            }

            is CounterMessage.Get -> {
                message.reply.complete(count)
            }

            CounterMessage.Fail -> {
                error("counter failure")
            }
        }
    }

    override suspend fun onFailure(
        message: CounterMessage,
        error: Throwable,
    ): ActorDirective {
        failures += 1
        return ActorDirective.Resume
    }
}

private class CounterConsumer(
    graph: Graph,
) : BasicNode(graph) {
    val counter: ConsumerPort<CounterActor> by consumes(CounterKey)
}

private sealed interface StateMessage {
    data class Set(val value: Int) : StateMessage
    data class Get(val reply: Reply<Int>) : StateMessage
}

private class StateActor(
    graph: Graph,
) : ActorNode<StateMessage>(
    graph = graph,
    key = StateKey,
) {
    private val storedValue = state<Int>("value")

    override suspend fun receive(message: StateMessage) {
        when (message) {
            is StateMessage.Set -> storedValue.set(message.value)
            is StateMessage.Get -> message.reply.complete(storedValue.get() ?: 0)
        }
    }
}

private class ActorTestDatabase : ObjectDatabase(TextSerializer()) {
    private val values = mutableMapOf<Pair<String, String>, StoredObject<Any>>()
    private var clock = 0L

    fun storeNames(): List<String> =
        values.keys.map { it.first }.distinct()

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> putRaw(
        storeName: String,
        key: String,
        value: T,
        serializer: KSerializer<T>,
    ): StoredObject<T> {
        val now = ++clock
        val stored = StoredObject(
            key = key,
            value = value,
            storeName = storeName,
            createdAt = now,
            updatedAt = now,
        )
        values[storeName to key] = stored as StoredObject<Any>
        return stored
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> getRaw(
        storeName: String,
        key: String,
        type: KClass<T>,
        serializer: KSerializer<T>,
    ): StoredObject<T>? {
        val stored = values[storeName to key] ?: return null
        return if (type.isInstance(stored.value)) stored as StoredObject<T> else null
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> getAllRaw(
        storeName: String,
        type: KClass<T>,
        serializer: KSerializer<T>,
    ): List<StoredObject<T>> =
        values.values
            .filter { it.storeName == storeName && type.isInstance(it.value) }
            .map { it as StoredObject<T> }

    override suspend fun deleteRaw(storeName: String, key: String) {
        values.remove(storeName to key)
    }

    override suspend fun clearRaw(storeName: String) {
        values.keys
            .filter { it.first == storeName }
            .forEach(values::remove)
    }

    override suspend fun clearRaw() {
        values.clear()
    }
}

private class ActorTestDependencyAdapter : DependencyAdapter<Unit>(Unit) {
    override fun createScope(
        id: String,
        parent: DependencyScopeCapability?,
        configure: (ScopeBuilder.() -> Unit),
    ): DependencyScopeCapability = ActorTestScope(id, parent as? ActorTestScope)

    override fun closeScope(scope: DependencyScopeCapability) {
        scope.close()
    }

    override fun <T : Any> get(
        scope: DependencyScopeCapability,
        type: KClass<T>,
        qualifier: String?,
        parameters: Map<String, Any?>,
    ): T = (scope as ActorTestScope).get(type, qualifier, parameters)

    override fun <T : Any> register(
        scope: DependencyScopeCapability,
        instance: T,
        type: KClass<T>,
        qualifier: String?,
    ) {
        (scope as ActorTestScope).register(type, qualifier, instance)
    }
}

private class ActorTestScope(
    override val id: String,
    private val parent: ActorTestScope? = null,
) : DependencyScopeCapability {
    private val values = mutableMapOf<Pair<KClass<*>, String?>, Any>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(
        type: KClass<T>,
        qualifier: String?,
        parameters: Map<String, Any?>,
    ): T = (values[type to qualifier] ?: parent?.get(type, qualifier, parameters))
        as? T
        ?: error("No dependency for ${type.simpleName} qualifier=$qualifier")

    fun <T : Any> register(
        type: KClass<T>,
        qualifier: String?,
        instance: T,
    ) {
        values[type to qualifier] = instance
    }

    override fun close() {
        values.clear()
    }
}
