package dev.shibasis.reaktor.ui.machinesignal.surface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.Type
import dev.shibasis.reaktor.surface.compose.Button
import dev.shibasis.reaktor.surface.compose.PreviewZoomState
import dev.shibasis.reaktor.surface.compose.Text
import dev.shibasis.reaktor.ui.machinesignal.MachineSignal
import dev.shibasis.reaktor.ui.machinesignal.SignalTone
import kotlin.math.roundToInt

@Composable
fun PreviewZoomControls(state: PreviewZoomState, modifier: Modifier = Modifier, tagPrefix: String = "preview-zoom") {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(MachineSignal.Space.s1), verticalAlignment = Alignment.CenterVertically) {
        Button({ state.zoomTo((state.scale - .1f).coerceAtLeast(.1f)) },
            Modifier.testTag("$tagPrefix-out").semantics { contentDescription = "Zoom preview out" },
            enabled = state.scale > .1f, appearance = toneButton(SignalTone.Ghost)) { Text("−", role = Type.Label) }
        Text("${(state.scale * 100).roundToInt()}%", Modifier.testTag("$tagPrefix-level"), role = Type.Data.code, ink = Ink.Text)
        Button({ state.zoomBy(.1f) }, Modifier.testTag("$tagPrefix-in").semantics { contentDescription = "Zoom preview in" },
            enabled = state.scale < 4f, appearance = toneButton(SignalTone.Ghost)) { Text("+", role = Type.Label) }
        Button({ state.zoomTo(1f) }, Modifier.testTag("$tagPrefix-actual"), appearance = toneButton(SignalTone.Ghost)) { Text("100%", role = Type.Label) }
        Button({ state.fit() }, Modifier.testTag("$tagPrefix-fit"), appearance = toneButton(if (state.fitting) SignalTone.Secondary else SignalTone.Ghost)) { Text("Fit", role = Type.Label) }
    }
}
