package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.DisclosureProperties
import dev.shibasis.reaktor.surface.DisclosureState
import dev.shibasis.reaktor.surface.ThemeSnapshot

val BarePanel: PanelAppearance = object : PanelAppearance {
    @Composable
    override fun Content(properties: DisclosureProperties, state: DisclosureState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: PanelSlots) {
        Column(Modifier.background(Color.White).border(1.dp, Color.Gray).padding(8.dp)) { slots.content() }
    }
}
