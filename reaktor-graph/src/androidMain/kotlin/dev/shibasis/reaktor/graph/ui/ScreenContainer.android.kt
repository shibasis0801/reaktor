package dev.shibasis.reaktor.graph.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun BackHandlerContainer(
    modifier: Modifier,
    intercept: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        BackHandler(intercept) {
            onBack()
        }

        content()
    }
}