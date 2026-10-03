package dev.shibasis.reaktor.surface.compose

import androidx.compose.ui.input.key.KeyShortcut
import dev.shibasis.reaktor.surface.Chord
import dev.shibasis.reaktor.surface.KeyConvention
import dev.shibasis.reaktor.surface.on

fun Chord.keyShortcut(convention: KeyConvention): KeyShortcut? {
    val chord = on(convention) ?: return null
    val key = ComposeKeys.getValue(chord.key)
    return when (convention) {
        KeyConvention.Mac -> KeyShortcut(key, ctrl = chord.control, meta = chord.primary, alt = chord.alt, shift = chord.shift)
        KeyConvention.Pc -> KeyShortcut(key, ctrl = chord.primary || chord.control, alt = chord.alt, shift = chord.shift)
    }
}
