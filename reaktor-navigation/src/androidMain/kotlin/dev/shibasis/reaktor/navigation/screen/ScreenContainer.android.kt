package dev.shibasis.reaktor.navigation.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.navigation.navigation.Navigator
import dev.shibasis.reaktor.ui.Theme

@Composable
actual fun Theme.ScreenContainer(navigator: Navigator) {
    Box(Modifier.fillMaxSize()) {
        val navigatorHandlesBack by navigator.handlesBack

        BackHandler(enabled = navigatorHandlesBack) {
            navigator.pop()
        }

        ScreenContainerContent(navigator)
    }
}