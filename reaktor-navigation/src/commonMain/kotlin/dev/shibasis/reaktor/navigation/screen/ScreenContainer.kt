package dev.shibasis.reaktor.navigation.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.navigation.navigation.Navigator
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
            Scaffold(Modifier.fillMaxSize()) {
                route.screen.Render(route.props)
            }
        }
    }
}

@Composable
expect fun Theme.ScreenContainer(
    navigator: Navigator
)

