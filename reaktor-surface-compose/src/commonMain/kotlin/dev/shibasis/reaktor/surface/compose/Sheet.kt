package dev.shibasis.reaktor.surface.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import dev.shibasis.reaktor.surface.DisclosureInput
import dev.shibasis.reaktor.surface.DisclosureKernel
import dev.shibasis.reaktor.surface.Release
import dev.shibasis.reaktor.surface.SettleEvent
import dev.shibasis.reaktor.surface.SettleKernel
import dev.shibasis.reaktor.surface.SettleProperties
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun Sheet(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    behavior: DisclosureBehavior = DisclosureKernel,
    content: @Composable SheetScope.() -> Unit,
) {
    val disclosure = rememberDisclosure(expanded, onExpandedChange, enabled, behavior)
    Box(modifier, propagateMinConstraints = true) { SheetScope(disclosure).content() }
}

@Stable
class SheetScope internal constructor(private val disclosure: Disclosure) {
    @Composable
    fun Trigger(
        modifier: Modifier = Modifier,
        appearance: ButtonAppearance = LocalAppearances.current.button,
        content: @Composable () -> Unit,
    ) = disclosure.Trigger(modifier, appearance, content)

    @Composable
    fun Content(
        appearance: PanelAppearance = LocalAppearances.current.sheet,
        scrim: Color = Color.Black.copy(alpha = 0.4f),
        content: @Composable PanelScope.() -> Unit,
    ) {
        val dismiss = { disclosure.machine.send(DisclosureInput.Dismiss) }
        Presence(disclosure.properties.expanded) { exiting, exited ->
            Overlay(modal = !exiting) {
                OverlayBack(enabled = !exiting, onBack = dismiss)
                BottomSheetFrame(scrim, dismiss, exiting, exited) {
                    disclosure.Panel(appearance, disclosure::initialFocus) { PanelScope(disclosure).content() }
                }
            }
        }
    }
}

@Composable
private fun BottomSheetFrame(scrim: Color, onDismiss: () -> Unit, exiting: Boolean, exited: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(Float.MAX_VALUE) }
    var extent by remember { mutableFloatStateOf(0f) }
    val settle = rememberMachine(SettleKernel, SettleProperties()) { event ->
        when (event) {
            SettleEvent.Dismiss -> onDismiss()
            SettleEvent.Restore -> scope.launch { offset.animateTo(0f) }
        }
    }
    val closing by rememberUpdatedState(exiting)
    val settled by remember { derivedStateOf { !offset.isRunning && offset.value == 0f } }
    LaunchedEffect(extent > 0f) {
        if (extent > 0f && !closing) {
            offset.snapTo(extent)
            offset.animateTo(0f)
        }
    }
    LaunchedEffect(exiting) {
        if (!exiting) {
            if (offset.value in 0f..extent && offset.value != 0f) offset.animateTo(0f)
            return@LaunchedEffect
        }
        try {
            offset.animateTo(extent.coerceAtLeast(1f))
        } finally {
            exited()
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(scrim)
            .then(if (exiting) Modifier.clearAndSetSemantics {} else Modifier.pointerInput(Unit) { detectTapGestures { onDismiss() } }),
    ) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { extent = it.height.toFloat() }
                .offset { IntOffset(0, offset.value.coerceAtMost(extent.coerceAtLeast(0f) + 1f).roundToInt()) }
                .then(if (settled) Modifier else Modifier.clearAndSetSemantics {})
                .pointerInput(exiting) { if (exiting) awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } } else detectTapGestures { } }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta -> scope.launch { offset.snapTo((offset.value + delta).coerceAtLeast(0f)) } },
                    onDragStopped = { velocity -> settle.send(Release(offset.value, extent, velocity)) },
                ),
        ) { content() }
    }
}

