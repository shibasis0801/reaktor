package dev.shibasis.reaktor.graph.core.node

import dev.shibasis.reaktor.graph.core.Graph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A node that owns a single immutable [state] and is the only thing able to change it.
 *
 * This is the "view model" shape: screens consume the node through a port, observe [state], and
 * call its methods to drive it. It differs from [ControllerNode] in two ways that matter — the
 * state is exposed read-only rather than as a `MutableStateFlow`, so it can only move through
 * [reduce] and [setState]; and it is a plain [BasicNode] rather than [Node.Routable], so it is
 * logic a route's screen observes rather than a route target itself.
 */
abstract class StateInteractor<S : Any>(
    graph: Graph,
    initialState: S,
) : BasicNode(graph) {
    private val mutableState = MutableStateFlow(initialState)

    val state: StateFlow<S> = mutableState.asStateFlow()

    protected val current: S
        get() = mutableState.value

    protected fun reduce(reducer: (S) -> S) {
        mutableState.update(reducer)
    }

    protected fun setState(value: S) {
        mutableState.value = value
    }
}

/**
 * A [StateInteractor] whose work kicks off the first time its screen appears.
 *
 * [start] is idempotent, so a screen can call it from every composition — or several screens can
 * share one interactor — without [onStart] running more than once.
 */
abstract class StartableInteractor<S : Any>(
    graph: Graph,
    initialState: S,
) : StateInteractor<S>(graph, initialState) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        onStart()
    }

    protected abstract fun onStart()
}
