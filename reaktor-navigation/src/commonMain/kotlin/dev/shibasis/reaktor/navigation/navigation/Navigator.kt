@file:Suppress("UNCHECKED_CAST")
package dev.shibasis.reaktor.navigation.navigation

import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.navigation.route.Switch
import dev.shibasis.reaktor.navigation.route.Props
import dev.shibasis.reaktor.navigation.route.Screen
import dev.shibasis.reaktor.navigation.route.ScreenPair


// Todo Write integration tests and design docs
/*
UseCases
1. DeepLink should open destination directly
3. Default destination to destination animation to be overridden
4. Exposes a flow to show the current route
5. Multi-Module support, how to trigger chat from feed without having feed depend on chat ? (DI)
*/
class Navigator(
    private val root: Switch
) {
    private val screenStack = ScreenStack(root.home)
    val handlesBack = screenStack.handlesBack
    val current = screenStack.current

    init {
//        root.apply { builder() }
    }

    fun findScreen(path: String, props: Props): ScreenPair {
        val segments = path.split("/").filter { it.isNotEmpty() }
        var switch = root
        var screen = switch.error

        for (segment in segments) {
            when(val route = switch.routes[segment]) {
                is Switch -> {
                    switch = route
                    screen = route.error
                }
                is Screen<Props> -> return route.with(props)
                null -> {
                    var matched = false
                    val dynamicRoutes = switch.routes.values.filter { it.pattern != null }
                    for (dynamicRoute in dynamicRoutes) {
                        val pattern = dynamicRoute.pattern!!
                        val match = pattern.regex.matchEntire(segment)
                        if (match != null) {
                            matched = true
                            val values = match.groupValues.drop(1) // 0th match is the full one.
                            pattern.paramNames.forEachIndexed { idx, name ->
                                props.params[name] = values[idx]
                            }
                            when(dynamicRoute) {
                                is Switch -> {
                                    switch = dynamicRoute
                                    screen = dynamicRoute.error
                                }
                                is Screen<Props> -> return dynamicRoute.with(props)
                            }
                            break
                        }
                    }
                    if (!matched) break
                }
            }
        }

        return screen.screenPair()
    }

    fun pop() {
        screenStack.pop()
    }

    /** Preferred for direct navigation */
    fun push(screenPair: ScreenPair) {
        screenStack.push(screenPair)
    }
    /** Preferred for direct navigation */
    fun replace(screenPair: ScreenPair) {
        screenStack.replace(screenPair)
    }

    /** Used for deep links */
    fun <T: Props> push(route: String, props: T) {
        push(findScreen(route, props))
    }
    /** Used for deep links */
    fun <T: Props> replace(route: String, props: T) {
        replace(findScreen(route, props))
    }

    fun push(screen: Screen<Props>) {
        push(screen.screenPair())
    }

    fun replace(screen: Screen<Props>) {
        push(screen.screenPair())
    }
}


var Feature.Navigator by CreateSlot<Navigator>()