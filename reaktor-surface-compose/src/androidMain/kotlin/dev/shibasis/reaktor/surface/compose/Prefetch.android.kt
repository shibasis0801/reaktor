package dev.shibasis.reaktor.surface.compose

import androidx.compose.runtime.Composable

@Composable
internal actual fun Prefetching(content: @Composable () -> Unit) = content()
