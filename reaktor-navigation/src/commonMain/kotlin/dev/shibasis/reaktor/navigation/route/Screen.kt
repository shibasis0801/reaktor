package dev.shibasis.reaktor.navigation.route

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.navigation.common.Props
import dev.shibasis.reaktor.navigation.common.ScreenPair

/**
 * Constructor parameters meant for configuring static fields such as repositories, apis, and animations, etc.
 * For dynamic fields, use props and path params.
 * todo figure out how to remove default props
 * reified reverse map to get screen from props so that we can directly push props ?
 */
abstract class Screen<out T: Props>(val defaultProps: T): Route(), Route.Render<T> {
    fun with(props: @UnsafeVariance T, vararg params: Pair<String, Any>) =
        ScreenPair(this, props.also {
            params.forEach { pair ->
                it.params[pair.first] = pair.second.toString()
            }
        })

    fun screenPair() = with(defaultProps)
}