package dev.shibasis.reaktor.graph.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

@Composable
actual fun BackHandlerContainer(
    modifier: Modifier,
    intercept: Boolean,
    onBack: () -> Unit,
    content: @Composable (backProgress: () -> Float) -> Unit
) {
    val state = rememberNavigationEventState(NavigationEventInfo.None)
    val shown = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) {
        snapshotFlow { (state.transitionState as? NavigationEventTransitionState.InProgress)?.latestEvent?.progress }
            .collect { progress -> if (progress != null) shown.snapTo(progress.coerceIn(0f, 1f)) }
    }
    NavigationBackHandler(
        state = state,
        isBackEnabled = intercept,
        onBackCancelled = { scope.launch { shown.animateTo(0f, tween(SettleMillis)) } },
        onBackCompleted = {
            scope.launch {
                shown.animateTo(1f, tween(SettleMillis))
                onBack()
                yield()
                shown.snapTo(0f)
            }
        },
    )
    Box(modifier) { content { shown.value } }
}

private const val SettleMillis = 200
