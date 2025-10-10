package dev.shibasis.reaktor.navigation.route

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.navigation.InputSignal
import dev.shibasis.reaktor.io.network.RoutePattern
import dev.shibasis.reaktor.navigation.common.ScreenPair

sealed class Route(var pattern: RoutePattern = RoutePattern()) {
    var parent: Route? = null
    var container: Container? = null

    interface Render<out T: InputSignal> {
        @Suppress("NotConstructor")
        @Composable
        fun Render(props: @UnsafeVariance T)
    }

    interface Buildable {
        fun build()
    }
}


interface NavContainer {
    fun push(screenPair: ScreenPair)
    fun replace(screenPair: ScreenPair)
    fun pop()
}