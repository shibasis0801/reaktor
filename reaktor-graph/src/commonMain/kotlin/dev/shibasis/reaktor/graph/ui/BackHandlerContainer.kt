package dev.shibasis.reaktor.graph.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier


@Composable
expect fun BackHandlerContainer(
    modifier: Modifier,
    intercept: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit
)