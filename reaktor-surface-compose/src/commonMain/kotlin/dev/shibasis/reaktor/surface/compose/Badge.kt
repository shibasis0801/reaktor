package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.ThemeSnapshot

class BadgeSlots(val content: @Composable () -> Unit)

typealias BadgeAppearance = ComposeAppearance<Unit, Unit, BadgeSlots>

@Composable
fun Badge(
    modifier: Modifier = Modifier,
    appearance: BadgeAppearance = LocalAppearances.current[Appearance.Badge],
    content: @Composable () -> Unit,
) = Box(modifier, propagateMinConstraints = true) {
    appearance.Content(Unit, Unit, LocalThemeSnapshot.current, rememberFeedback(pressed = false, focusVisible = false), BadgeSlots(content))
}

val BareBadge: BadgeAppearance = object : BadgeAppearance {
    @Composable
    override fun Content(properties: Unit, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: BadgeSlots) {
        Box(Modifier.border(1.dp, Color.Gray, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp)) { slots.content() }
    }
}
