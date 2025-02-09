package dev.shibasis.reaktor.navigation.route

import dev.shibasis.reaktor.navigation.common.Props
import dev.shibasis.reaktor.navigation.common.RoutePattern
import dev.shibasis.reaktor.navigation.util.ErrorScreen

/**
 * 1. Support lazy init later, currently we eagerly initialise the screens.
 * 2. Move to prefix match if needed later
 */
class Switch(
    val home: Screen<Props> = ErrorScreen("Home Screen not selected"),
    val error: Screen<Props> = ErrorScreen(),
    private val builder: Switch.() -> Unit = {}
): Route() {
    // maintain insertion order
    val routes = linkedMapOf<String, Route>()

    private fun link(at: String, route: Route) {
        route.pattern = RoutePattern.from(at)
        route.parent = this
        route.container = container
        routes[at] = route
    }

    fun screen(route: String, screen: Screen<Props>): Screen<Props> {
        link(route, screen)
        return screen
    }

    fun switch(route: String, switch: Switch): Switch {
        link(route, switch)
        switch.build()
        return switch
    }

    fun switch(route: String, home: Screen<Props>, error: Screen<Props> = ErrorScreen(), builder: Switch.() -> Unit = {})
        = switch(route, Switch(home, error, builder))

    fun container(route: String, container: Container): Container {
        link(route, container)
        container.build()
        return container
    }

    // todo wtf! Why is this different from calling builder() directly ?
    // Calling builder directly is causing all sorts of issues.
    // Some rare bug ?
    private var built = false
    fun build() {
        if (!built) {
            screen("", home)
            builder()
            built = true
        }
    }
}
