package dev.shibasis.reaktor.navigation.route

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.navigation.common.Props
import dev.shibasis.reaktor.navigation.common.ScreenPair

/*
    todo
    if segment is on a route to a switch, then the home destination of that switch should be shown
     */

abstract class Container(
    val switch: Switch
): Route() {
    @Composable
    abstract fun Render()
    abstract fun push(screenPair: ScreenPair)
    abstract fun replace(screenPair: ScreenPair)
    // Return true if your Screens are finished and this container can be popped
    abstract fun pop(): Boolean

    fun findScreen(segments: List<String>, props: Props): ScreenPair {
        var switch = switch
        var screen = switch.error

        for ((index, segment) in segments.withIndex()) {
            when(val route = switch.routes[segment]) {
                is Switch -> {
                    switch = route
                    screen = route.error
                }
                is Screen<Props> -> return route.with(props)
                is Container -> return route.findScreen(segments.subList(index, segments.size), props)
                null -> {
                    var matched = false
                    val dynamicRoutes = switch.routes.values.filter { it.pattern != null }

                    for (dynamicRoute in dynamicRoutes) {
                        val pattern = dynamicRoute.pattern!!
                        val match = pattern.regex.matchEntire(segment)
                        if (match != null) {
                            matched = true
                            val values = match.groupValues.drop(1) // 0th match is the full one.
                            // relies on implicit order, fix later.
                            pattern.paramNames.forEachIndexed { idx, name ->
                                props.params[name] = values[idx]
                            }
                            when(dynamicRoute) {
                                is Switch -> {
                                    switch = dynamicRoute
                                    screen = dynamicRoute.error
                                }
                                is Screen<Props> -> return dynamicRoute.with(props)
                                is Container -> return dynamicRoute.findScreen(segments.subList(index, segments.size), props)
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
}

class ContainerFactory<out T: Container>(
    val switch: Switch,
    val factory: (Switch) -> T
) {
    fun build() = factory(switch)
}

/*

StackContainer: Container
    single stack, direct UI

MultiStackContainer: Container
    abstract class, multiple stacks and utility functions

BottomNavigationContainer: MultiStackContainer
    Scaffold, BottomNavBar, AppBar

TabbedContainer: MultiStackContainer
    Tabs

How would nesting/rendering work ?



*/