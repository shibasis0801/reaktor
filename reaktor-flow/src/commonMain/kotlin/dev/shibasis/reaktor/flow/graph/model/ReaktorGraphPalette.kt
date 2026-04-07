package dev.shibasis.reaktor.flow.graph.model

import androidx.compose.ui.graphics.Color

// Keep graph palette values in one place so adapter/layout code can tag semantic meaning without
// depending on renderer implementation details. This mirrors token palettes in Fluent/React and
// keeps graph extraction stable when the editor skin changes.
internal object ReaktorGraphPalette {
    val disconnectedPort = Color(0xFF666C80)
    val navigationPort = Color(0xFF55A8F4)
    val dataPort = Color(0xFF55D46E)
    val rootRegion = Color(0xFF4A5878)
    val nestedRegion = Color(0xFF4A7858)
    val deepRegion = Color(0xFF6A5A7E)
}
