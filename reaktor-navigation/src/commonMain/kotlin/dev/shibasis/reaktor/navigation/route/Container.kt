package dev.shibasis.reaktor.navigation.route

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.navigation.common.Props
import dev.shibasis.reaktor.navigation.common.ScreenPair
import dev.shibasis.reaktor.navigation.util.ErrorScreen

/*
    todo
    if segment is on a route to a switch, then the home destination of that switch should be shown
     */

abstract class Container(
    val switch: Switch
): Route() {
    constructor(
        home: Screen<Props> = ErrorScreen("Home Screen not selected"),
        error: Screen<Props> = ErrorScreen(),
        builder: Switch.() -> Unit = {}
    ): this(Switch(home, error, builder))

    open fun build() {
        switch.container = this
        switch.build()
    }

    @Composable
    abstract fun Render()
    abstract fun consumesBackEvent(): Boolean
    abstract fun push(screenPair: ScreenPair)
    abstract fun replace(screenPair: ScreenPair)
    abstract fun pop()

    // todo bottleneck. exhaustive tree search every time you redirect. (but only for deep links)
    fun findScreen(segments: List<String>, props: Props): ScreenPair {
        var switch = switch
        var screen = switch.error


        for ((index, segment) in segments.withIndex()) {
            when(val current = switch.routes[segment]) {
                is Screen<Props> -> return current.with(props)
                is Container -> return current.findScreen(segments.subList(index, segments.size), props)
                is Switch -> {
                    switch = current
                    screen = current.error
                }
                null -> {
                    var matched = false

                    for (route in switch.routes.values) {
                        val match = route.pattern.regex.matchEntire(segment)
                        if (match != null) {
                            matched = true

                            route.pattern.params(match).forEach { (key, value) ->
                                props.params[key] = value
                            }

                            when(route) {
                                is Screen<Props> -> return route.with(props)
                                is Container -> return route.findScreen(segments.subList(index, segments.size), props)
                                is Switch -> {
                                    switch = route
                                    screen = route.error
                                }
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