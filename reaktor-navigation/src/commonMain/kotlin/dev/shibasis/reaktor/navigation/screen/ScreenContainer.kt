package dev.shibasis.reaktor.navigation.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.shibasis.reaktor.navigation.route.Navigator
import dev.shibasis.reaktor.ui.Theme


val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("No Navigator provided")
}

@Composable
internal fun Theme.ScreenContainerContent(
    navigator: Navigator
) {
    val route by navigator.current
    CompositionLocalProvider(LocalNavigator provides navigator) {
        MaterialTheme(
            colorScheme = colors,
            typography = text,
            shapes = shapes
        ) {
            route.destination.content(route.props)
        }
    }
}

@Composable
expect fun Theme.ScreenContainer(
    navigator: Navigator
)

