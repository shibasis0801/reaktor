package dev.shibasis.reaktor.navigation.screen

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.core.framework.Dispatch
import dev.shibasis.reaktor.navigation.route.Navigator
import dev.shibasis.reaktor.ui.Theme
import kotlin.native.internal.collectReferenceFieldValues

private val edgeThreshold = 64.dp

// Enhance later https://exyte.com/blog/jetpack-compose-multiplatform
@Composable
actual fun Theme.ScreenContainer(
    navigator: Navigator
) {
    val navigatorHandlesBack by navigator.handlesBack
    var shouldGoBack by remember { mutableStateOf(false) }

    LaunchedEffect(shouldGoBack) {
        if (navigatorHandlesBack && shouldGoBack)
            navigator.pop()
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .pointerInput(navigatorHandlesBack) {
                if (navigatorHandlesBack)
                    detectHorizontalDragGestures(
                        onDragStart = { shouldGoBack = it.x < edgeThreshold.toPx() },
                        onHorizontalDrag = { _, dragAmount -> shouldGoBack = (shouldGoBack && dragAmount > 0) },
                        onDragEnd = { shouldGoBack = false },
                        onDragCancel = { shouldGoBack = false }
                    )
            }
    ) {
        ScreenContainerContent(navigator)
    }
}