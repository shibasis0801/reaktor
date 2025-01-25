package dev.shibasis.reaktor.navigation.route

import dev.shibasis.reaktor.navigation.common.Props
import dev.shibasis.reaktor.navigation.common.buildRoutePattern
import dev.shibasis.reaktor.navigation.util.ErrorScreen
import dev.shibasis.reaktor.navigation.util.joinPath


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
    init {
        screen("", home)
    }

    fun screen(route: String, screen: Screen<Props>) {
        screen.pattern = buildRoutePattern(route)
        screen.path = joinPath(path, route)
        screen.container = container

        routes[route] = screen
    }

    fun switch(route: String, switch: Switch) {
        switch.pattern = buildRoutePattern(route)
        switch.path = joinPath(path, route)
        switch.container = container

        switch.home.path = switch.path
        routes[route] = switch
        switch.build()
    }

    fun container(route: String, container: Container) {
        container.pattern = buildRoutePattern(route)
        container.path = joinPath(path, route)
        container.switch.path = container.path
        container.switch.home.path = container.switch.path
        container.switch.container = container

        routes[route] = container.switch
        container.switch.build()
    }

    // todo wtf! Why is this different from calling builder() directly ?
    // Calling builder directly is causing all sorts of issues.
    // Some rare bug ?
    private var built = false
    fun build() {
        if (!built) {
            builder()
            built = true
        }
    }
}
