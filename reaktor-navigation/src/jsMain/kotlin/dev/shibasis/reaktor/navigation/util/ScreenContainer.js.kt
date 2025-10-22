package dev.shibasis.reaktor.navigation.util

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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