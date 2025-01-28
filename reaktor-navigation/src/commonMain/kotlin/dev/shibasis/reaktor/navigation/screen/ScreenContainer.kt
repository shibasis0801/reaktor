package dev.shibasis.reaktor.navigation.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.navigation.navigation.Navigator
import dev.shibasis.reaktor.navigation.structs.collectAsState
import dev.shibasis.reaktor.ui.Theme


val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("No Navigator provided")
}

@Composable
expect fun BackHandlerContainer(
    modifier: Modifier,
    enabled: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit
)

@Composable
fun Theme.ScreenContainer(navigator: Navigator) {
    val stack = navigator.containerStack
    val handlesBack = navigator.handlesBack.collectAsState()

    BackHandlerContainer(Modifier.fillMaxSize(), handlesBack.value, navigator::onBack) {
        val container by stack.top.collectAsState()
        CompositionLocalProvider(LocalNavigator provides navigator) {
            MaterialTheme(
                colorScheme = colors,
                typography = text,
                shapes = shapes
            ) {
                Scaffold(Modifier.fillMaxSize()) {
                    container.Render()
                }
            }
        }
    }
}
