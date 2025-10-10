package dev.shibasis.reaktor.navigation.route

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.navigation.InputSignal
import dev.shibasis.reaktor.navigation.common.ScreenPair
import dev.shibasis.reaktor.navigation.util.ErrorScreen

open class ContainerInputs(
    val nestedContent: @Composable (InputSignal) -> Unit = {},
    val singleLayer: Boolean = false
): InputSignal()

abstract class Container(
    val switch: Switch
): Route(), Route.Render<ContainerInputs>, Route.Buildable, NavContainer {
    constructor(
        home: Screen<InputSignal> = ErrorScreen("Home Screen not selected"),
        error: Screen<InputSignal> = ErrorScreen(),
        builder: Switch.() -> Unit = {}
    ): this(Switch(home, error, builder))

    init {
        switch.container = this
    }

    override fun build() {
        switch.build()
    }

    abstract fun consumesBackEvent(): Boolean

    // todo bottleneck. exhaustive tree search every time you redirect. (but only for deep links)
    fun findScreen(segments: List<String>, inputs: InputSignal): ScreenPair {
        var switch = switch
        var screen = switch.error

        for ((index, segment) in segments.withIndex()) {
            when(val current = switch.routes[segment]) {
                is Screen<InputSignal> -> return current.with(inputs)
                is Container -> return current.findScreen(segments.subList(index, segments.size), inputs)
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

                            route.pattern.getParams(match).forEach { (key, value) ->
                                inputs.params[key] = value
                            }

                            when(route) {
                                is Screen<InputSignal> -> return route.with(inputs)
                                is Container -> return route.findScreen(segments.subList(index, segments.size), inputs)
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