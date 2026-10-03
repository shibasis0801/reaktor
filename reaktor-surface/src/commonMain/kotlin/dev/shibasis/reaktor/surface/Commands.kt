package dev.shibasis.reaktor.surface

import kotlin.jvm.JvmInline

@JvmInline
value class CommandId(val value: String)

sealed interface Chord {
    data class Of(
        val key: KeyName,
        val primary: Boolean = false,
        val shift: Boolean = false,
        val alt: Boolean = false,
        val control: Boolean = false,
    ) : Chord

    data class Platform(val mac: Of?, val pc: Of?) : Chord
}

sealed interface Availability {
    data object Available : Availability
    data class Unavailable(val reason: String) : Availability
}

sealed interface Mark {
    data class Check(val on: Boolean) : Mark
    data class Choice(val on: Boolean) : Mark
}

data class Command(
    val id: CommandId,
    val label: String,
    val chord: Chord? = null,
    val mark: Mark? = null,
    val availability: Availability = Availability.Available,
    val detail: String? = null,
)

sealed interface CommandEntry {
    data class Item(val id: CommandId) : CommandEntry
    data class Group(val label: String, val entries: List<CommandEntry>) : CommandEntry
    data class Caption(val text: String) : CommandEntry
    data object Separator : CommandEntry
}

data class CommandMenu(val label: String, val entries: List<CommandEntry>)

data class CommandSet(val commands: List<Command>, val menus: List<CommandMenu> = emptyList())

data class CommandPath(val path: List<String>, val command: Command)

sealed interface ChordFinding {
    data class Clash(val chord: Chord.Of, val commands: List<CommandId>) : ChordFinding
    data class Collapsed(val command: CommandId) : ChordFinding
}

fun Chord.on(convention: KeyConvention): Chord.Of? = when (this) {
    is Chord.Of -> this
    is Chord.Platform -> if (convention == KeyConvention.Mac) mac else pc
}

fun Chord.matches(stroke: KeyStroke, convention: KeyConvention): Boolean =
    on(convention)?.stroke(convention) == stroke.copy(character = null)

fun Chord.label(convention: KeyConvention): String {
    val chord = on(convention) ?: return ""
    val name = chord.key.label(convention)
    return when (convention) {
        KeyConvention.Mac -> buildString {
            if (chord.control) append('⌃')
            if (chord.alt) append('⌥')
            if (chord.shift) append('⇧')
            if (chord.primary) append('⌘')
            append(name)
        }
        KeyConvention.Pc -> buildString {
            if (chord.primary || chord.control) append("Ctrl+")
            if (chord.alt) append("Alt+")
            if (chord.shift) append("Shift+")
            append(name)
        }
    }
}

fun CommandSet.paths(): List<CommandPath> {
    val byId = commands.associateBy { it.id }
    fun walk(path: List<String>, entries: List<CommandEntry>): List<CommandPath> = entries.flatMap { entry ->
        when (entry) {
            is CommandEntry.Item -> listOfNotNull(byId[entry.id]?.let { CommandPath(path, it) })
            is CommandEntry.Group -> walk(path + entry.label, entry.entries)
            is CommandEntry.Caption, CommandEntry.Separator -> emptyList()
        }
    }
    val presented = menus.flatMap { walk(listOf(it.label), it.entries) }
    val shown = presented.mapTo(mutableSetOf()) { it.command.id }
    return presented + commands.filter { it.id !in shown }.map { CommandPath(emptyList(), it) }
}

fun CommandSet.find(stroke: KeyStroke, convention: KeyConvention): Command? =
    commands.firstOrNull { it.availability == Availability.Available && it.chord?.matches(stroke, convention) == true }

fun CommandSet.findings(convention: KeyConvention): List<ChordFinding> {
    val bound = commands.mapNotNull { command -> command.chord?.on(convention)?.let { command to it } }
    val clashes = bound.groupBy { (_, chord) -> chord.stroke(convention) }.values
        .filter { it.size > 1 }
        .map { group -> ChordFinding.Clash(group.first().second, group.map { (command, _) -> command.id }) }
    val collapsed = bound
        .filter { (_, chord) -> convention == KeyConvention.Pc && chord.primary && chord.control }
        .map { (command, _) -> ChordFinding.Collapsed(command.id) }
    return clashes + collapsed
}

private fun Chord.Of.stroke(convention: KeyConvention): KeyStroke = when (convention) {
    KeyConvention.Mac -> KeyStroke(key, meta = primary, control = control, alt = alt, shift = shift)
    KeyConvention.Pc -> KeyStroke(key, control = primary || control, alt = alt, shift = shift)
}

private fun KeyName.label(convention: KeyConvention): String {
    val mac = convention == KeyConvention.Mac
    return when (this) {
        KeyName.Digit0, KeyName.Digit1, KeyName.Digit2, KeyName.Digit3, KeyName.Digit4,
        KeyName.Digit5, KeyName.Digit6, KeyName.Digit7, KeyName.Digit8, KeyName.Digit9 -> name.removePrefix("Digit")
        KeyName.Enter -> if (mac) "↩" else "Enter"
        KeyName.Escape -> if (mac) "⎋" else "Esc"
        KeyName.Tab -> if (mac) "⇥" else "Tab"
        KeyName.Space -> "Space"
        KeyName.Backspace -> if (mac) "⌫" else "Backspace"
        KeyName.Delete -> if (mac) "⌦" else "Delete"
        KeyName.Up -> if (mac) "↑" else "Up"
        KeyName.Down -> if (mac) "↓" else "Down"
        KeyName.Left -> if (mac) "←" else "Left"
        KeyName.Right -> if (mac) "→" else "Right"
        KeyName.Home -> if (mac) "↖" else "Home"
        KeyName.End -> if (mac) "↘" else "End"
        KeyName.PageUp -> if (mac) "⇞" else "Page Up"
        KeyName.PageDown -> if (mac) "⇟" else "Page Down"
        KeyName.LeftBracket -> "["
        KeyName.RightBracket -> "]"
        KeyName.Equals -> "="
        KeyName.Minus -> "-"
        KeyName.Comma -> ","
        KeyName.Period -> "."
        KeyName.Slash -> "/"
        KeyName.ContextMenu -> "Menu"
        else -> name
    }
}
