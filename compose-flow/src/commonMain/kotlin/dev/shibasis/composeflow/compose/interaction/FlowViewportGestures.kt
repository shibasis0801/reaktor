package dev.shibasis.composeflow.compose.interaction

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import dev.shibasis.composeflow.compose.theme.FlowSizing
import dev.shibasis.composeflow.runtime.FlowRuntimeDefaults
import dev.shibasis.composeflow.runtime.ReactFlowState
import kotlin.math.exp
import kotlin.math.sqrt

data class FlowViewportGestureConfig(
    val minZoom: Double = FlowRuntimeDefaults.minZoom,
    val maxZoom: Double = FlowRuntimeDefaults.maxZoom,
    val wheelZoomSensitivity: Double = FlowSizing.wheelZoomSensitivity,
    val wheelZoomFactorMin: Double = FlowSizing.wheelZoomFactorMin,
    val wheelZoomFactorMax: Double = FlowSizing.wheelZoomFactorMax,
    val controlZoomFactor: Double = FlowSizing.controlZoomFactor,
)

// References:
// - Compose pointer input guidance: keep gesture recognizers small and composable so platform-
//   specific tuning can happen without rewriting the full scene renderer.
// - xyflow / React Flow viewport helpers: pan/zoom are editor-space transforms applied around a
//   chosen anchor. This file owns those gesture-to-transform mappings for Compose Desktop.
fun Modifier.flowWheelAndTrackpadViewportGestures(
    state: ReactFlowState,
    interactionState: FlowViewportInteractionState,
    config: FlowViewportGestureConfig,
    platformBridge: FlowViewportPlatformBridge? = null,
): Modifier = pointerInput(config) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue
            if (event.type != PointerEventType.Scroll) continue
            val scrollDelta = change.scrollDelta
            val isZoomGesture =
                event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
            interactionState.markViewportAsUserModified()
            if (isZoomGesture) {
                val anchor =
                    platformBridge?.resolveScrollAnchor(event, interactionState)
                        ?: interactionState.lastPointerPosition
                        ?: change.position
                val factor =
                    exp(-scrollDelta.y * config.wheelZoomSensitivity)
                        .coerceIn(config.wheelZoomFactorMin, config.wheelZoomFactorMax)
                state.zoomBy(
                    factor = factor,
                    anchorX = anchor.x.toDouble(),
                    anchorY = anchor.y.toDouble(),
                    minZoom = config.minZoom,
                    maxZoom = config.maxZoom,
                )
            } else {
                state.panBy(
                    dx = -scrollDelta.x.toDouble() * FlowSizing.wheelPanFactor,
                    dy = -scrollDelta.y.toDouble() * FlowSizing.wheelPanFactor,
                )
            }
            interactionState.updatePointerPosition(change.position)
            change.consume()
        }
    }
}

@Composable
fun FlowViewportPlatformGestureEffect(
    state: ReactFlowState,
    interactionState: FlowViewportInteractionState,
    config: FlowViewportGestureConfig,
    platformBridge: FlowViewportPlatformBridge?,
) {
    DisposableEffect(platformBridge, state, interactionState, config) {
        val subscription = platformBridge?.installViewportGestures(
            state = state,
            interactionState = interactionState,
            config = config,
        )
        onDispose {
            subscription?.dispose()
        }
    }
}

fun Modifier.flowViewportPointerTracking(
    interactionState: FlowViewportInteractionState,
): Modifier = pointerInput(interactionState) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
            if (event.type == PointerEventType.Scroll) continue
            event.changes.firstOrNull()?.let { change ->
                interactionState.updatePointerPosition(change.position)
            }
        }
    }
}

fun Modifier.flowPointerViewportGestures(
    state: ReactFlowState,
    interactionState: FlowViewportInteractionState,
    config: FlowViewportGestureConfig,
    onPaneClick: (() -> Unit)? = null,
): Modifier = pointerInput(config, onPaneClick) {
    val touchSlop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )
        interactionState.updatePointerPosition(down.position)
        var activePointerId = down.id
        var accumulatedDrag = Offset.Zero
        var dragStarted = false
        var viewportManipulated = false
        var secondaryButtonGesture = false
        var clickEligible = true

        while (true) {
            val event = awaitPointerEvent()
            val pressedChanges = event.changes.filter(PointerInputChange::pressed)
            secondaryButtonGesture = secondaryButtonGesture || event.buttons.isSecondaryPressed
            clickEligible = clickEligible && !secondaryButtonGesture

            if (pressedChanges.isEmpty()) {
                if (!viewportManipulated && clickEligible) {
                    onPaneClick?.invoke()
                }
                break
            }

            if (pressedChanges.size > 1) {
                clickEligible = false
                val centroid = pressedChanges.currentCentroid()
                val pan = pressedChanges.calculateGesturePan()
                val zoom = pressedChanges.calculateGestureZoom()
                if (pan != Offset.Zero || zoom != 1f) {
                    viewportManipulated = true
                    interactionState.markViewportAsUserModified()
                    if (pan != Offset.Zero) {
                        state.panBy(pan.x.toDouble(), pan.y.toDouble())
                    }
                    if (zoom != 1f) {
                        state.zoomBy(
                            factor = zoom.toDouble(),
                            anchorX = centroid.x.toDouble(),
                            anchorY = centroid.y.toDouble(),
                            minZoom = config.minZoom,
                            maxZoom = config.maxZoom,
                        )
                    }
                    pressedChanges.forEach { change ->
                        if (change.position != change.previousPosition) {
                            change.consume()
                        }
                    }
                }
                activePointerId = pressedChanges.first().id
                continue
            }

            val change = event.changes.firstOrNull { it.id == activePointerId }
                ?: pressedChanges.first().also { activePointerId = it.id }
            interactionState.updatePointerPosition(change.position)
            val delta = change.position - change.previousPosition
            if (!dragStarted) {
                accumulatedDrag += delta
                if (secondaryButtonGesture || accumulatedDrag.distance() > touchSlop) {
                    dragStarted = true
                }
            }
            if (dragStarted && delta != Offset.Zero) {
                viewportManipulated = true
                interactionState.markViewportAsUserModified()
                state.panBy(delta.x.toDouble(), delta.y.toDouble())
                change.consume()
            }
        }
    }
}

fun ReactFlowState.zoomAroundCanvasCenter(
    factor: Double,
    minZoom: Double = FlowRuntimeDefaults.minZoom,
    maxZoom: Double = FlowRuntimeDefaults.maxZoom,
) {
    zoomBy(
        factor = factor,
        anchorX = canvasSize.width / 2.0,
        anchorY = canvasSize.height / 2.0,
        minZoom = minZoom,
        maxZoom = maxZoom,
    )
}

fun Modifier.requestPointerFocusOnFirstDown(
    onFirstDown: () -> Unit,
): Modifier = pointerInput(onFirstDown) {
    awaitEachGesture {
        awaitFirstDown(pass = PointerEventPass.Initial)
        onFirstDown()
    }
}

private fun List<PointerInputChange>.currentCentroid(): Offset = centroid(useCurrent = true)

private fun List<PointerInputChange>.previousCentroid(): Offset = centroid(useCurrent = false)

private fun List<PointerInputChange>.centroid(useCurrent: Boolean): Offset {
    if (isEmpty()) return Offset.Zero
    val sum = fold(Offset.Zero) { acc, change ->
        acc + if (useCurrent) change.position else change.previousPosition
    }
    return sum / size.toFloat()
}

private fun List<PointerInputChange>.calculateGesturePan(): Offset =
    currentCentroid() - previousCentroid()

private fun List<PointerInputChange>.calculateGestureZoom(): Float {
    if (size < 2) return 1f
    val currentCentroid = currentCentroid()
    val previousCentroid = previousCentroid()
    val currentRadius = averageRadius(currentCentroid, useCurrent = true)
    val previousRadius = averageRadius(previousCentroid, useCurrent = false)
    return if (currentRadius > 0f && previousRadius > 0f) {
        currentRadius / previousRadius
    } else {
        1f
    }
}

private fun List<PointerInputChange>.averageRadius(
    centroid: Offset,
    useCurrent: Boolean,
): Float =
    if (isEmpty()) {
        0f
    } else {
        sumOf { change ->
            val point = if (useCurrent) change.position else change.previousPosition
            (point - centroid).distance().toDouble()
        }.toFloat() / size.toFloat()
    }

private fun Offset.distance(): Float = sqrt(x * x + y * y)
