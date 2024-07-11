@file:Suppress("UNCHECKED_CAST")
package dev.shibasis.reaktor.navigation.route

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import dev.shibasis.reaktor.navigation.screen.Props
import dev.shibasis.reaktor.core.framework.Feature

typealias ScreenPair = Pair<Props, Destination<Props>>

// Todo Write integration tests and design docs

/*
UseCases
1. DeepLink should open destination directly
2. Compose/React/WebView switch should happen automatically
3. Default destination to destination animation to be overridden
4. Exposes a flow to show the current route
5. Multi-Module support, how to trigger chat from feed without having feed depend on chat ? (DI)
*/
class Navigator(
    private val root: Junction
) {
    private val stack = ArrayDeque<ScreenPair>()
    private val currentState: MutableState<ScreenPair>
    val current: State<ScreenPair>
        get() { return currentState }

    init {
        val route = root.Index ?: getErrorRoute()
        currentState = mutableStateOf(ScreenPair(route.defaultParameters, route))
    }

    @Suppress("UNCHECKED_CAST")
    private fun getDestination(path: String, root: Junction): Destination<Props> {
        val segments = path.apply {
            if (startsWith("/")) substring(1)
        }.split("/").filter { it.isNotEmpty() }

        val errorRoute = getErrorRoute()
        var currentJunction = root
        for (segment in segments) {
            val route = currentJunction.routes[segment]
            println("Router: $segment -> $route")
            when (route) {
                is Junction -> currentJunction = route
                is Destination<*> -> return route as Destination<Props>
                else -> return errorRoute
            }
        }
        return errorRoute
    }

    fun <T: Props> push(destination: Destination<T>, props: T) {
        val pair = ScreenPair(props, destination as Destination<Props>)
        stack.add(pair)
        currentState.value = pair
    }

    fun <T: Props> push(route: String, props: T) {
        val destination = getDestination(route, root)
        push(destination, props)
    }
}

private val navigatorId = Feature.createId()
var Feature.Navigator: Navigator?
    get() = fetchDependency(navigatorId)
    set(navigator) = storeDependency(navigatorId, navigator)