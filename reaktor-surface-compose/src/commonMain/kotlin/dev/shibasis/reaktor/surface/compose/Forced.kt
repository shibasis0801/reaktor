package dev.shibasis.reaktor.surface.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import dev.shibasis.reaktor.surface.PressState

enum class ForcedInteraction { Rest, Hovered, Pressed, Focused }

val LocalForcedInteraction: ProvidableCompositionLocal<ForcedInteraction?> = staticCompositionLocalOf { null }

@Composable
fun PressState.forced(): PressState = when (LocalForcedInteraction.current) {
    null -> this
    ForcedInteraction.Rest -> PressState()
    ForcedInteraction.Hovered -> PressState(hovered = true)
    ForcedInteraction.Pressed -> PressState(contacts = setOf(-1L))
    ForcedInteraction.Focused -> PressState(focused = true, focusVisible = true)
}
