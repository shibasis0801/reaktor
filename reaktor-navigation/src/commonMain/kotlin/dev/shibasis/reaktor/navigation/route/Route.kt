package dev.shibasis.reaktor.navigation.route

import dev.shibasis.reaktor.navigation.common.RoutePattern

sealed class Route(var pattern: RoutePattern = RoutePattern()) {
    var parent: Route? = null
    var container: Container? = null
}


