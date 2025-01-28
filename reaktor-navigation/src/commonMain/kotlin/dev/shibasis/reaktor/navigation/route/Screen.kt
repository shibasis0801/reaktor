package dev.shibasis.reaktor.navigation.route

import androidx.compose.runtime.Composable
import dev.shibasis.reaktor.navigation.common.Props
import dev.shibasis.reaktor.navigation.common.ScreenPair

/**
 * Constructor parameters meant for configuring static fields such as Enter/Exit animations
 * For Screen Parameters, use props and path params.
 */
abstract class Screen<out T: Props>(val defaultProps: T): Route() {
    lateinit var container: Container
    @Composable
    abstract fun Render(props: @UnsafeVariance T)

    fun with(props: @UnsafeVariance T, vararg params: Pair<String, Any>) =
        ScreenPair(this, props.also {
            params.forEach { pair ->
                it.params[pair.first] = pair.second.toString()
            }
        })

    internal fun screenPair() = with(defaultProps)
}