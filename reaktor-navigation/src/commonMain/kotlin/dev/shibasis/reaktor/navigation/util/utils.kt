package dev.shibasis.reaktor.navigation.util

import dev.shibasis.reaktor.navigation.route.Screen
import dev.shibasis.reaktor.navigation.route.Switch

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
