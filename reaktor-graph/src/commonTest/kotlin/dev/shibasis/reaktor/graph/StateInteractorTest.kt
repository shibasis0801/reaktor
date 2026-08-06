package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.StartableInteractor
import dev.shibasis.reaktor.graph.core.node.StateInteractor
import dev.shibasis.reaktor.graph.di.DependencyAdapter
import dev.shibasis.reaktor.graph.di.DependencyScopeCapability
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

class StateInteractorTest {
    private data class Counter(val value: Int = 0, val label: String = "")

    private class CountingInteractor(graph: Graph) : StateInteractor<Counter>(graph, Counter()) {
        fun increment() = reduce { it.copy(value = it.value + 1) }

        fun replace(counter: Counter) = setState(counter)

        fun readCurrent(): Counter = current
    }

    private class StartCountingInteractor(graph: Graph) : StartableInteractor<Counter>(graph, Counter()) {
        var starts = 0
            private set

        override fun onStart() {
            starts += 1
            reduce { it.copy(label = "started") }
        }
    }

    private fun graph() = Graph(label = "test", dependencyAdapter = TestDependencyAdapter())

    @Test
    fun stateStartsAtTheInitialValue() {
        val interactor = CountingInteractor(graph())
        assertEquals(Counter(), interactor.state.value)
    }

    @Test
    fun reduceDerivesTheNextStateFromTheCurrentOne() {
        val interactor = CountingInteractor(graph())

        interactor.increment()
        interactor.increment()

        assertEquals(2, interactor.state.value.value)
    }

    @Test
    fun setStateReplacesTheWholeState() {
        val interactor = CountingInteractor(graph())

        interactor.increment()
        interactor.replace(Counter(value = 9, label = "replaced"))

        assertEquals(Counter(value = 9, label = "replaced"), interactor.state.value)
    }

    @Test
    fun currentTracksTheLatestState() {
        val interactor = CountingInteractor(graph())

        interactor.increment()

        assertEquals(interactor.state.value, interactor.readCurrent())
    }

    @Test
    fun startRunsOnStartExactlyOnceHoweverOftenItIsCalled() {
        val interactor = StartCountingInteractor(graph())

        interactor.start()
        interactor.start()
        interactor.start()

        assertEquals(1, interactor.starts)
        assertEquals("started", interactor.state.value.label)
    }

    @Test
    fun onStartDoesNotRunUntilStartIsCalled() {
        val interactor = StartCountingInteractor(graph())

        assertEquals(0, interactor.starts)
        assertEquals("", interactor.state.value.label)
    }

    private class TestDependencyAdapter : DependencyAdapter<Unit>(Unit) {
        override fun createScope(
            id: String,
            parent: DependencyScopeCapability?,
            configure: (ScopeBuilder.() -> Unit),
        ): DependencyScopeCapability = TestScope(id, parent as? TestScope)

        override fun closeScope(scope: DependencyScopeCapability) {
            scope.close()
        }

        override fun <T : Any> get(
            scope: DependencyScopeCapability,
            type: KClass<T>,
            qualifier: String?,
            parameters: Map<String, Any?>,
        ): T = (scope as TestScope).get(type, qualifier, parameters)

        override fun <T : Any> register(
            scope: DependencyScopeCapability,
            instance: T,
            type: KClass<T>,
            qualifier: String?,
        ) {
            (scope as TestScope).register(type, qualifier, instance)
        }
    }

    private class TestScope(
        override val id: String,
        private val parent: TestScope? = null,
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
}
