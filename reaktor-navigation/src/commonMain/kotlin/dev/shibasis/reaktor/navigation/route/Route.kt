package dev.shibasis.reaktor.navigation.route

import dev.shibasis.reaktor.navigation.common.RoutePattern

sealed class Route(
    var path: String = "/",
    var pattern: RoutePattern? = null
)

