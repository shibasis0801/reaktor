package dev.shibasis.reaktor.surface

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

enum class Axis { Horizontal, Vertical, Both }

interface RovingItems {
    val size: Int
    fun key(index: Int): String
    fun enabled(index: Int): Boolean
    fun text(index: Int): String?
    fun indexOf(key: String): Int = (0 until size).firstOrNull { this.key(it) == key } ?: -1
}

data class RovingItem(val key: String, val enabled: Boolean = true, val text: String? = null)

data class RovingList(val items: List<RovingItem>) : RovingItems {
    override val size: Int get() = items.size
    override fun key(index: Int): String = items[index].key
    override fun enabled(index: Int): Boolean = items[index].enabled
    override fun text(index: Int): String? = items[index].text
}

data class RovingProperties(
    val items: RovingItems,
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

data class RovingKernel(val typeahead: Duration = 500.milliseconds) :
    BehaviorKernel<RovingProperties, RovingState, RovingInput, ActiveChange> {

    override fun initial(properties: RovingProperties) = RovingState(active = properties.items.firstEnabled())

    override fun reduce(properties: RovingProperties, state: RovingState, input: RovingInput): Reduction<RovingState, ActiveChange> =
        when (input) {
            is RovingInput.Stroke -> when (val gesture = gesture(properties, input.stroke)) {
                null -> Reduction(state)
                Gesture.First -> moveTo(state, properties.items.firstEnabled())
                Gesture.Last -> moveTo(state, properties.items.lastEnabled())
                is Gesture.Step -> moveTo(state, step(properties, state.active, gesture.delta))
                is Gesture.Type -> type(properties, state, gesture.character)
            }
            is RovingInput.Point ->
                if (properties.items.indexOf(input.key).let { it < 0 || !properties.items.enabled(it) }) Reduction(state)
                else Reduction(state.copy(active = input.key), commands = if (input.focus) listOf(LocalCommand.Focus(PartKey(input.key))) else emptyList())
            is RovingInput.Focused ->
                if (properties.items.indexOf(input.key) < 0) Reduction(state) else Reduction(state.copy(active = input.key))
            is RovingInput.TypingElapsed ->
                if (input.ticket != state.typing) Reduction(state) else Reduction(state.copy(typed = "", typing = null))
        }

    override fun reconcile(properties: RovingProperties, state: RovingState): Reduction<RovingState, ActiveChange> {
        val index = properties.items.indexOf(state.active)
        return if (index >= 0 && properties.items.enabled(index)) Reduction(state)
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
        ?.takeIf { !it.isWhitespace() && (0 until properties.items.size).any { index -> properties.items.text(index) != null } }
        ?.let { Gesture.Type(it) }

private fun step(properties: RovingProperties, active: String?, delta: Int): String? {
    val items = properties.items
    val from = items.indexOf(active)
    val start = if (from >= 0) from else if (delta > 0) -1 else items.size
    for (offset in 1..items.size) {
        val index = (start + delta * offset).let { if (properties.wrap) it.mod(items.size) else it }
        if (index !in 0 until items.size) return null
        if (items.enabled(index)) return items.key(index)
    }
    return null
}

private fun match(items: RovingItems, active: String?, typed: String): String? {
    if (items.size == 0) return null
    val repeated = typed.length > 1 && typed.all { it.equals(typed.first(), ignoreCase = true) }
    val search = if (repeated) typed.take(1) else typed
    val from = items.indexOf(active)
    val offsets = if (from >= 0 && search.length == 1) 1..items.size else 0 until items.size
    return offsets.asSequence()
        .map { (from.coerceAtLeast(0) + it).mod(items.size) }
        .firstOrNull { items.enabled(it) && items.text(it)?.startsWith(search, ignoreCase = true) == true }
        ?.let(items::key)
}

private fun nearest(items: RovingItems, index: Int): String? {
    if (index < 0) return items.firstEnabled()
    for (distance in 1..items.size) {
        (index + distance).takeIf { it < items.size && items.enabled(it) }?.let { return items.key(it) }
        (index - distance).takeIf { it >= 0 && items.enabled(it) }?.let { return items.key(it) }
    }
    return null
}

private fun RovingItems.indexOf(key: String?): Int = if (key == null) -1 else indexOf(key)

private fun RovingItems.firstEnabled(): String? = (0 until size).firstOrNull(::enabled)?.let(::key)

private fun RovingItems.lastEnabled(): String? = (size - 1 downTo 0).firstOrNull(::enabled)?.let(::key)
