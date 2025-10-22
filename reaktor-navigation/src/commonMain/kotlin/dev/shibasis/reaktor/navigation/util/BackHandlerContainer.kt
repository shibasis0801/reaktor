package dev.shibasis.reaktor.navigation.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier


@Composable
expect fun BackHandlerContainer(
    modifier: Modifier,
    enabled: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit
)
