@file:Suppress("UNCHECKED_CAST")
package dev.shibasis.reaktor.navigation.route

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.shibasis.reaktor.navigation.screen.Props
import dev.shibasis.reaktor.core.framework.Feature
import kotlin.js.JsName


data class ScreenPair(val props: Props, val destination: Destination<Props>)


internal class ScreenStack(rootDestination: Destination<Props>) {
    private val stack = ArrayDeque<ScreenPair>()
    val current = mutableStateOf(ScreenPair(rootDestination.defaultParameters, rootDestination))
    val handlesBack = mutableStateOf(false)

    init {
        stack.add(current.value)
    }

    fun push(screenPair: ScreenPair) {
        stack.add(screenPair)
        current.value = screenPair
        handlesBack.value = true
    }

    fun replace(screenPair: ScreenPair) {
        stack.removeLast()
        stack.add(screenPair)
        current.value = screenPair
    }

    fun pop() {
        stack.removeLast()
        current.value = stack.last()
        if (stack.size == 1) handlesBack.value = false
    }

}

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
    private val screenStack = ScreenStack(root.Index ?: getErrorRoute())

    val handlesBack = screenStack.handlesBack
    val current = screenStack.current

    @Suppress("UNCHECKED_CAST")
    private fun getDestination(path: String, root: Junction): Destination<Props> {
        val segments = path.apply {
            if (startsWith("/")) substring(1)
        }.split("/").filter { it.isNotEmpty() }

        val errorRoute = root.errorDestination ?: getErrorRoute()
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
        screenStack.push(ScreenPair(props, destination as Destination<Props>))
    }

    @JsName("pushRoute")
    fun <T: Props> push(route: String, props: T) {
        val destination = getDestination(route, root)
        // regex match
        push(destination, props)
    }

    fun <T: Props> replace(destination: Destination<T>, props: T) {
        screenStack.replace(ScreenPair(props, destination as Destination<Props>))
    }

    @JsName("replaceRoute")
    fun <T: Props> replace(route: String, props: T) {
        val destination = getDestination(route, root)
        replace(destination, props)
    }

    fun pop() {
        screenStack.pop()
    }
}

private val navigatorId = Feature.createId()
var Feature.Navigator: Navigator?
    get() = fetchDependency(navigatorId)
    set(navigator) = storeDependency(navigatorId, navigator)