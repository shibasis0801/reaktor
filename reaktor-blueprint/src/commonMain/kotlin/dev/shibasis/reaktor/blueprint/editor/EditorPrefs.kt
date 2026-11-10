package dev.shibasis.reaktor.blueprint.editor

import dev.shibasis.reaktor.surface.Chord
import dev.shibasis.reaktor.surface.CommandSet
import dev.shibasis.reaktor.surface.KeyConvention
import dev.shibasis.reaktor.surface.KeyName
import dev.shibasis.reaktor.surface.KeyStroke
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EditorPrefs(val keys: Map<String, String> = emptyMap(), val favourites: List<String> = emptyList(), val minimap: Boolean = false, val legend: Boolean = false) {
    fun encode(): String = PrefsJson.encodeToString(serializer(), this)

    companion object {
        fun decode(text: String?): EditorPrefs = text?.let { runCatching { PrefsJson.decodeFromString(serializer(), it) }.getOrNull() } ?: EditorPrefs()
    }
}

fun Chord.Of.text(): String = listOfNotNull("primary".takeIf { primary }, "shift".takeIf { shift }, "alt".takeIf { alt }, "control".takeIf { control }, key.name).joinToString("+")

fun chordOf(text: String): Chord.Of? {
    val parts = text.split('+')
    val modifiers = parts.dropLast(1)
    if (modifiers.distinct().size != modifiers.size || modifiers.any { it !in setOf("primary", "shift", "alt", "control") }) return null
    val key = parts.lastOrNull()?.let { name -> KeyName.entries.firstOrNull { it.name == name } } ?: return null
    return Chord.Of(key, "primary" in parts, "shift" in parts, "alt" in parts, "control" in parts)
}

fun KeyStroke.chord(convention: KeyConvention): Chord.Of = when (convention) {
    KeyConvention.Mac -> Chord.Of(key, primary = meta, shift = shift, alt = alt, control = control)
    KeyConvention.Pc -> Chord.Of(key, primary = control || meta, shift = shift, alt = alt)
}

fun CommandSet.remapped(keys: Map<String, String>): CommandSet {
    require(keys.values.all { it.isEmpty() || chordOf(it) != null }) { "Invalid command shortcut" }
    return if (keys.isEmpty()) this else copy(commands = commands.map { command ->
        keys[command.id.value]?.let { command.copy(chord = chordOf(it)) } ?: command
    })
}

private val PrefsJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }
