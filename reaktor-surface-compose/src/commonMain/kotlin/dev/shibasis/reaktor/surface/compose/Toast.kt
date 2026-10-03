package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.PressKernel
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.ToastEntry
import dev.shibasis.reaktor.surface.ToastEvent
import dev.shibasis.reaktor.surface.ToastInput
import dev.shibasis.reaktor.surface.ToastKernel
import dev.shibasis.reaktor.surface.ToastState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

typealias ToastAppearance = ComposeAppearance<ToastEntry, PressState, ToastSlots>

class ToastSlots(val message: @Composable () -> Unit)

@Stable
class ToastQueue internal constructor(
    internal val machine: Machine<Unit, ToastState, ToastInput, ToastEvent>,
) {
    private var nextId = 0L

    fun show(message: String, duration: Duration = 4.seconds) {
        machine.send(ToastInput.Show(ToastEntry(++nextId, message, duration)))
    }
}

@Composable
fun rememberToastQueue(onEvent: (ToastEvent) -> Unit = {}): ToastQueue {
    val latest by rememberUpdatedState(onEvent)
    val machine = rememberMachine(ToastKernel, Unit) { latest(it) }
    return remember(machine) { ToastQueue(machine) }
}

@Composable
fun ToastHost(
    queue: ToastQueue,
    modifier: Modifier = Modifier,
    appearance: ToastAppearance = LocalAppearances.current.toast,
    message: @Composable (ToastEntry) -> Unit,
) {
    val entry = queue.machine.state.shown ?: return
    if (LocalOverlayHost.current == null) return Toast(queue, entry, modifier, appearance, message)
    Overlay(modal = false) {
        Box(Modifier.fillMaxSize().padding(ToastMargin), contentAlignment = Alignment.BottomEnd) {
            Toast(queue, entry, modifier.focusProperties { canFocus = false }.holdsOnHover(queue), appearance, message)
        }
    }
}

@Composable
private fun Toast(
    queue: ToastQueue,
    entry: ToastEntry,
    modifier: Modifier,
    appearance: ToastAppearance,
    message: @Composable (ToastEntry) -> Unit,
) {
    val press = rememberMachine(PressKernel, PressProperties()) {}
    val source = rememberInteractions(press)
    Box(
        modifier
            .semantics { liveRegion = LiveRegionMode.Polite }
            .clickable(source, indication = null) { queue.machine.send(ToastInput.Dismiss(entry.id)) },
        propagateMinConstraints = true,
    ) {
        val state = press.state
        appearance.Content(entry, state, LocalThemeSnapshot.current, rememberFeedback(state.pressed, state.focusVisible), ToastSlots { message(entry) })
    }
}

private fun Modifier.holdsOnHover(queue: ToastQueue): Modifier = pointerInput(queue) {
    awaitPointerEventScope {
        while (true) {
            when (awaitPointerEvent().type) {
                PointerEventType.Enter -> queue.machine.send(ToastInput.Hold(true))
                PointerEventType.Exit -> queue.machine.send(ToastInput.Hold(false))
            }
        }
    }
}

private val ToastMargin = 16.dp

val BareToast: ToastAppearance = object : ToastAppearance {
    @Composable
    override fun Content(properties: ToastEntry, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ToastSlots) {
        Box(Modifier.background(Color.DarkGray).padding(horizontal = 16.dp, vertical = 12.dp)) { slots.message() }
    }
}
