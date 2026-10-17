package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.ViewState

class StateViewSlots(val reason: @Composable () -> Unit, val action: (@Composable () -> Unit)?)

typealias StateViewAppearance = ComposeAppearance<ViewState, Unit, StateViewSlots>

@Composable
fun StateView(
    state: ViewState,
    modifier: Modifier = Modifier,
    appearance: StateViewAppearance = LocalAppearances.current[Appearance.StateView],
    action: (@Composable () -> Unit)? = null,
    reason: @Composable () -> Unit,
) {
    val announced = state in Announced
    Box(modifier.semantics { if (state == ViewState.Loading) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }, propagateMinConstraints = true) {
        appearance.Content(
            state,
            Unit,
            LocalThemeSnapshot.current,
            rememberFeedback(pressed = false, focusVisible = false),
            StateViewSlots({ Box(Modifier.semantics { if (announced) liveRegion = LiveRegionMode.Polite }) { reason() } }, action),
        )
    }
}

fun Modifier.busy(busy: Boolean): Modifier = semantics { if (busy) stateDescription = "Busy" }

private val Announced = setOf(ViewState.Loading, ViewState.Failed, ViewState.Stale)

val BareStateView: StateViewAppearance = object : StateViewAppearance {
    @Composable
    override fun Content(properties: ViewState, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: StateViewSlots) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            slots.reason()
            slots.action?.invoke()
        }
    }
}
