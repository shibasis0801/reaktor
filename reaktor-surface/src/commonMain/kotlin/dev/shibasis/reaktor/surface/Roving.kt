package dev.shibasis.reaktor.surface

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

enum class Axis { Horizontal, Vertical, Both }

data class RovingItem(val key: String, val enabled: Boolean = true, val text: String? = null)

data class RovingProperties(
    val items: List<RovingItem>,
    val axis: Axis,
    val wrap: Boolean = true,
    val rightToLeft: Boolean = false,
)

data class RovingState(
    val active: String? = null,
    val typed: String = "",
    val typing: Ticket? = null,
    val nextTicket: Long = 1,
)

sealed interface RovingInput {
    data class Stroke(val stroke: KeyStroke) : RovingInput
    data class Point(val key: String, val focus: Boolean) : RovingInput
    data class Focused(val key: String) : RovingInput
    data class TypingElapsed(val ticket: Ticket) : RovingInput
}

data class ActiveChange(val key: String)

class RovingKernel(val typeahead: Duration = 500.milliseconds) :
    BehaviorKernel<RovingProperties, RovingState, RovingInput, ActiveChange> {

    override fun initial(properties: RovingProperties) = RovingState(active = properties.items.firstOrNull { it.enabled }?.key)

    override fun reduce(properties: RovingProperties, state: RovingState, input: RovingInput): Reduction<RovingState, ActiveChange> =
        when (input) {
            is RovingInput.Stroke -> when (val gesture = gesture(properties, input.stroke)) {
                null -> Reduction(state)
                Gesture.First -> moveTo(state, properties.items.firstOrNull { it.enabled }?.key)
                Gesture.Last -> moveTo(state, properties.items.lastOrNull { it.enabled }?.key)
                is Gesture.Step -> moveTo(state, step(properties, state.active, gesture.delta))
                is Gesture.Type -> type(properties, state, gesture.character)
            }
            is RovingInput.Point ->
                if (properties.items.none { it.key == input.key && it.enabled }) Reduction(state)
                else Reduction(state.copy(active = input.key), commands = if (input.focus) listOf(LocalCommand.Focus(PartKey(input.key))) else emptyList())
            is RovingInput.Focused ->
                if (properties.items.none { it.key == input.key }) Reduction(state) else Reduction(state.copy(active = input.key))
            is RovingInput.TypingElapsed ->
                if (input.ticket != state.typing) Reduction(state) else Reduction(state.copy(typed = "", typing = null))
        }

    override fun reconcile(properties: RovingProperties, state: RovingState): Reduction<RovingState, ActiveChange> {
        val index = properties.items.indexOfFirst { it.key == state.active }
        return if (index >= 0 && properties.items[index].enabled) Reduction(state)
        else Reduction(state.copy(active = nearest(properties.items, index)))
    }

    fun handles(properties: RovingProperties, stroke: KeyStroke): Boolean = gesture(properties, stroke) != null

    private fun type(properties: RovingProperties, state: RovingState, character: Char): Reduction<RovingState, ActiveChange> {
        val typed = state.typed + character
        val ticket = Ticket(state.nextTicket)
        val timing = listOfNotNull(state.typing?.let { LocalCommand.Cancel(it) }) +
            LocalCommand.Schedule(ticket, typeahead, RovingInput.TypingElapsed(ticket))
        val next = state.copy(typed = typed, typing = ticket, nextTicket = state.nextTicket + 1)
        return moveTo(next, match(properties.items, state.active, typed), timing)
    }

    private fun moveTo(state: RovingState, key: String?, commands: List<LocalCommand> = emptyList()): Reduction<RovingState, ActiveChange> =
        if (key == null || key == state.active) Reduction(state, commands = commands)
        else Reduction(
            state.copy(active = key),
            listOf(ActiveChange(key)),
            commands = commands + LocalCommand.Focus(PartKey(key)) + LocalCommand.Reveal(PartKey(key)),
        )
}

private sealed interface Gesture {
    data object First : Gesture
    data object Last : Gesture
    data class Step(val delta: Int) : Gesture
    data class Type(val character: Char) : Gesture
}

private fun gesture(properties: RovingProperties, stroke: KeyStroke): Gesture? = when {
    stroke.meta || stroke.control || stroke.alt -> null
    stroke.shift -> typed(properties, stroke)
    else -> navigation(properties, stroke.key) ?: typed(properties, stroke)
}

private fun navigation(properties: RovingProperties, key: KeyName): Gesture? {
    val horizontal = properties.axis != Axis.Vertical
    val vertical = properties.axis != Axis.Horizontal
    val forward = if (properties.rightToLeft) -1 else 1
    return when (key) {
        KeyName.Home -> Gesture.First
        KeyName.End -> Gesture.Last
        KeyName.Up -> if (vertical) Gesture.Step(-1) else null
        KeyName.Down -> if (vertical) Gesture.Step(1) else null
        KeyName.Left -> if (horizontal) Gesture.Step(-forward) else null
        KeyName.Right -> if (horizontal) Gesture.Step(forward) else null
        else -> null
    }
}

private fun typed(properties: RovingProperties, stroke: KeyStroke): Gesture? =
    stroke.character
        ?.takeIf { !it.isWhitespace() && properties.items.any { item -> item.text != null } }
        ?.let { Gesture.Type(it) }

private fun step(properties: RovingProperties, active: String?, delta: Int): String? {
    val items = properties.items
    val from = items.indexOfFirst { it.key == active }
    val start = if (from >= 0) from else if (delta > 0) -1 else items.size
    for (offset in 1..items.size) {
        val index = (start + delta * offset).let { if (properties.wrap) it.mod(items.size) else it }
        if (index !in items.indices) return null
        if (items[index].enabled) return items[index].key
    }
    return null
}

private fun match(items: List<RovingItem>, active: String?, typed: String): String? {
    if (items.isEmpty()) return null
    val repeated = typed.length > 1 && typed.all { it.equals(typed.first(), ignoreCase = true) }
    val search = if (repeated) typed.take(1) else typed
    val from = items.indexOfFirst { it.key == active }
    val offsets = if (from >= 0 && search.length == 1) 1..items.size else 0 until items.size
    return offsets.asSequence()
        .map { items[(from.coerceAtLeast(0) + it).mod(items.size)] }
        .firstOrNull { it.enabled && it.text?.startsWith(search, ignoreCase = true) == true }
        ?.key
}

private fun nearest(items: List<RovingItem>, index: Int): String? {
    if (index < 0) return items.firstOrNull { it.enabled }?.key
    for (distance in 1..items.size) {
        items.getOrNull(index + distance)?.takeIf { it.enabled }?.let { return it.key }
        items.getOrNull(index - distance)?.takeIf { it.enabled }?.let { return it.key }
    }
    return null
}
