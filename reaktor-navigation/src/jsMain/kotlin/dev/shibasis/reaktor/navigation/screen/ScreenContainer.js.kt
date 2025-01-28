package dev.shibasis.reaktor.navigation.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shibasis.reaktor.navigation.navigation.Navigator
import dev.shibasis.reaktor.ui.Theme

@Composable
actual fun BackHandlerContainer(
    modifier: Modifier,
    enabled: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(modifier) {
//        BackHandler(enabled = navigator.stack.size > 1) {
//            navigator.pop()
//        }
        content()
    }
}