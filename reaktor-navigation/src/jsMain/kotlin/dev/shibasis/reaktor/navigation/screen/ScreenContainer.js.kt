package dev.shibasis.reaktor.navigation.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.navigation.navigation.Navigator
import dev.shibasis.reaktor.ui.Theme

@Composable
actual fun Theme.ScreenContainer(
    navigator: Navigator
) {
    Box(Modifier.fillMaxSize()) {
//        BackHandler(enabled = navigator.stack.size > 1) {
//            navigator.pop()
//        }

        ScreenContainerContent(navigator)
    }
}