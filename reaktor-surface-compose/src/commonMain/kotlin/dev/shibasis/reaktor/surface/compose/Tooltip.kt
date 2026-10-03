package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.ThemeSnapshot

data class TipContent(val chord: String? = null, val reason: String? = null)

class TooltipSlots(val tip: @Composable () -> Unit)

typealias TooltipAppearance = ComposeAppearance<TipContent, Unit, TooltipSlots>

val BareTooltip: TooltipAppearance = object : TooltipAppearance {
    @Composable
    override fun Content(properties: TipContent, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: TooltipSlots) {
        Column(Modifier.background(Color.DarkGray).padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            slots.tip()
            properties.chord?.let { BasicText(it) }
            properties.reason?.let { BasicText(it) }
        }
    }
}
