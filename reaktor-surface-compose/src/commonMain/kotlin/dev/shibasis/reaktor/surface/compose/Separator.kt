package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.ThemeSnapshot

typealias SeparatorAppearance = ComposeAppearance<Unit, Unit, Unit>

@Composable
fun Separator(
    modifier: Modifier = Modifier,
    appearance: SeparatorAppearance = LocalAppearances.current[Appearance.Separator],
) = Box(modifier, propagateMinConstraints = true) {
    appearance.Content(Unit, Unit, LocalThemeSnapshot.current, rememberFeedback(pressed = false, focusVisible = false), Unit)
}

val BareSeparator: SeparatorAppearance = object : SeparatorAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Unit) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Color.LightGray))
    }
}
