package dev.shibasis.reaktor.graph.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun BackHandlerContainer(
    modifier: Modifier,
    intercept: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        BackHandler(enabled = intercept) {
            onBack()
        }
        content()
    }
}
