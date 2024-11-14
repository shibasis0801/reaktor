package dev.shibasis.reaktor.navigation.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.navigation.route.Junction
import dev.shibasis.reaktor.navigation.route.Navigator
import dev.shibasis.reaktor.ui.DesignSystem
import dev.shibasis.reaktor.ui.Theme
import dev.shibasis.reaktor.ui.material.ReaktorDesignSystem


val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("No Navigator provided")
}

@Composable
fun ScreenContainer(
    junction: Junction,
    theme: Theme = Feature.Theme ?: throw Error("A theme is needed for the app."),
) {
    Box(Modifier.fillMaxSize()) {
        val navigator = remember { Navigator(junction) }
        val route by navigator.current
        val (props, destination) = route

        // Not KMP yet
//        BackHandler(enabled = navigator.stack.size > 1) {
//            navigator.pop()
//        }

        CompositionLocalProvider(LocalNavigator provides navigator) {
            MaterialTheme(
                colorScheme = theme.colors,
                typography = theme.text,
                shapes = theme.shapes
            ) {
                destination.content(props)
            }
        }

    }
}