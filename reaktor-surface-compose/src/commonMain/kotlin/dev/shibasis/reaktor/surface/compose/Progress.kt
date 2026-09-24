package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.ThemeSnapshot

data class ProgressProperties(val value: Float?)

object ProgressSlots

typealias ProgressAppearance = ComposeAppearance<ProgressProperties, Unit, ProgressSlots>

@Composable
fun Progress(
    value: Float?,
    modifier: Modifier = Modifier,
    appearance: ProgressAppearance = LocalAppearances.current.progress,
) {
    val clamped = value?.coerceIn(0f, 1f)
    Box(
        modifier.semantics { progressBarRangeInfo = clamped?.let { ProgressBarRangeInfo(it, 0f..1f) } ?: ProgressBarRangeInfo.Indeterminate },
        propagateMinConstraints = true,
    ) {
        appearance.Content(ProgressProperties(clamped), Unit, LocalThemeSnapshot.current, rememberFeedback(pressed = false, focusVisible = false), ProgressSlots)
    }
}

val BareProgress: ProgressAppearance = object : ProgressAppearance {
    @Composable
    override fun Content(properties: ProgressProperties, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ProgressSlots) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color.LightGray)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(properties.value ?: 0.3f).background(Color.Black))
        }
    }
}
