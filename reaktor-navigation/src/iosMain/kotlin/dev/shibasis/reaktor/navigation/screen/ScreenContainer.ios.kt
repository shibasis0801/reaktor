package dev.shibasis.reaktor.navigation.screen

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

private val edgeThreshold = 64.dp

// Enhance later https://exyte.com/blog/jetpack-compose-multiplatform
@Composable
actual fun BackHandlerContainer(
    modifier: Modifier,
    enabled: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    var shouldGoBack by remember { mutableStateOf(false) }

    LaunchedEffect(shouldGoBack) {
        if (enabled && shouldGoBack)
            onBack()
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .pointerInput(enabled) {
                if (enabled)
                    detectHorizontalDragGestures(
                        onDragStart = { shouldGoBack = it.x < edgeThreshold.toPx() },
                        onHorizontalDrag = { _, dragAmount -> shouldGoBack = (shouldGoBack && dragAmount > 0) },
                        onDragEnd = { shouldGoBack = false },
                        onDragCancel = { shouldGoBack = false }
                    )
            }
    ) {
        content()
    }
}