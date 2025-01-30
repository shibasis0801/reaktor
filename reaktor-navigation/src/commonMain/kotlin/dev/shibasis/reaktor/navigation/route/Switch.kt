package dev.shibasis.reaktor.navigation.route

import dev.shibasis.reaktor.navigation.common.Props
import dev.shibasis.reaktor.navigation.common.RoutePattern
import dev.shibasis.reaktor.navigation.util.ErrorScreen

/**
 * 1. Support lazy init later, currently we eagerly initialise the screens.
 * 2. Move to prefix match if needed later
 */
open class Switch(
    val home: Screen<Props> = ErrorScreen("Home Screen not selected"),
    val error: Screen<Props> = ErrorScreen(),
    private val builder: Switch.() -> Unit = {}
): Route() {
    lateinit var container: Container
    val routes = hashMapOf<String, Route>()

    fun screen(route: String, screen: Screen<Props>) {
        screen.pattern = RoutePattern.from(route)
        screen.container = container
        routes[route] = screen
    }

    fun switch(route: String, switch: Switch) {
        switch.pattern = RoutePattern.from(route)
        switch.container = container
        routes[route] = switch
        switch.build()
    }

    fun container(route: String, container: Container) {
        pattern = RoutePattern.from(route)
        container.switch.container = container
        routes[route] = container
        container.switch.build()
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
