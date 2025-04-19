package dev.shibasis.reaktor.navigation.route

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.navigation.common.Props
import dev.shibasis.reaktor.navigation.common.ScreenPair
import dev.shibasis.reaktor.navigation.util.ErrorScreen

/*
    todo
    if segment is on a route to a switch, then the home destination of that switch should be shown

    Go through the navigation framework properly.
    How would you support tabbed browsing ?
    How would you support two containers on one screen ?
    Is it possible to just use one navigator for all use cases ?
*/
open class ContainerProps(
    val nestedContent: @Composable (Props) -> Unit = {},
    val singleLayer: Boolean = false
): Props()

abstract class Container(
    val switch: Switch
): Route(), Route.Render<ContainerProps>, Route.Buildable {
    constructor(
        home: Screen<Props> = ErrorScreen("Home Screen not selected"),
        error: Screen<Props> = ErrorScreen(),
        builder: Switch.() -> Unit = {}
    ): this(Switch(home, error, builder))

    init {
        switch.container = this
    }

    override fun build() {
        switch.build()
    }

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

rememberSaveable

*/