package dev.shibasis.reaktor.navigation.route

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.navigation.navigation.RoutePattern
import dev.shibasis.reaktor.navigation.navigation.buildRoutePattern
import dev.shibasis.reaktor.navigation.util.ErrorScreen

open class Props(val params: MutableMap<String, String> = hashMapOf())

sealed class Route(
    var path: String = "/",
    var pattern: RoutePattern? = null
)

/**
 * Constructor parameters meant for configuring static fields such as Enter/Exit animations
 * For Screen Parameters, use props and path params.
 */
abstract class Screen<out T: Props>(val defaultProps: T): Route() {
    @Composable abstract fun Render(props: @UnsafeVariance T)

    fun with(props: @UnsafeVariance T) = ScreenPair(this, props)

    internal fun screenPair() = ScreenPair(this, defaultProps)
}

data class ScreenPair(val screen: Screen<Props>, val props: Props)

/**
 * 1. Support lazy init later, currently we eagerly initialise the screens.
 * 2. Move to prefix match if needed later
 */
class Switch(
    val home: Screen<Props>,
    val error: Screen<Props> = ErrorScreen(),
    private val builder: Switch.() -> Unit = {}
): Route() {
    internal val routes = hashMapOf<String, Route>()
    private var built = false
    fun build() {
//        if (!built) {
            builder()
//            built = true
//        }
    }


    fun<T: Props> screen(route: String, screen: Screen<T>) {
        screen.path = joinPath(path, route)
        screen.pattern = buildRoutePattern(screen.path)
        routes[route] = screen
    }

    fun switch(route: String, switch: Switch) {
        switch.path = joinPath(path, route)
        switch.home.path = switch.path
        switch.pattern = buildRoutePattern(switch.path)
        routes[route] = switch
        switch.build()
    }
}

fun joinPath(parent: String, child: String): String {
    val trimmedParent = parent.trimEnd('/')
    val trimmedChild = child.trimStart('/')
    return "$trimmedParent/$trimmedChild"
}


/**
 * Recursively pretty-prints a Switch, showing its path, home, error, and routes.
 * Usage: println(mySwitch.prettyPrint())
 */
fun Switch.prettyPrint(indent: String = ""): String {
    val sb = StringBuilder()

    // Header for this switch
    sb.append("$indent Switch(path = \"$path\") {\n")

    // Home and error
    sb.append("$indent   home  = ${home::class.simpleName} (path=\"${home.path}\")\n")
    sb.append("$indent   error = ${error::class.simpleName} (path=\"${error.path}\")\n")

    // Child routes
    if (routes.isEmpty()) {
        sb.append("$indent   (no routes)\n")
    } else {
        sb.append("$indent   routes:\n")
        for ((routeKey, route) in routes) {
            when (route) {
                is Screen<*> -> {
                    sb.append("$indent     - \"$routeKey\" => Screen: ${route::class.simpleName} (path=\"${route.path}\")\n")
                }
                is Switch -> {
                    sb.append("$indent     - \"$routeKey\" => Nested Switch:\n")
                    sb.append(route.prettyPrint(indent + "        "))
                }
                else -> {
                    sb.append("$indent     - \"$routeKey\" => Unknown route type: ${route::class.simpleName}\n")
                }
            }
        }
    }

    sb.append("$indent}\n")
    return sb.toString()
}
