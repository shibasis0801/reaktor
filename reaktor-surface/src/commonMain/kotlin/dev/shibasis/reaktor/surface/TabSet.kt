package dev.shibasis.reaktor.surface

data class TabSetProperties(
    val tabs: CollectionItems,
    val selected: String?,
    val closable: Set<String>,
    val rightToLeft: Boolean = false,
)

data class TabSetState(val roving: RovingState = RovingState(), val activeIndex: Int = -1, val shown: String? = null, val within: Boolean = false) {
    val active: String? get() = roving.active
}

sealed interface TabSetInput {
    data class Stroke(val stroke: KeyStroke) : TabSetInput
    data class Point(val key: String) : TabSetInput
    data class Close(val key: String) : TabSetInput
    data class Focused(val key: String) : TabSetInput
    data object Blurred : TabSetInput
    data class TypingElapsed(val ticket: Ticket) : TabSetInput
}

sealed interface TabSetEvent {
    data class Select(val key: String) : TabSetEvent
    data class CloseRequest(val key: String) : TabSetEvent
}

typealias TabSetBehavior = BehaviorKernel<TabSetProperties, TabSetState, TabSetInput, TabSetEvent>

data class TabSetKernel(val roving: RovingKernel = RovingKernel()) : TabSetBehavior {
    override fun initial(properties: TabSetProperties): TabSetState {
        val active = properties.selected?.takeIf { properties.tabs.indexOf(it) >= 0 } ?: properties.tabs.firstEnabled()
        return TabSetState(RovingState(active = active), properties.tabs.indexOf(active), properties.selected)
    }

    override fun reduce(properties: TabSetProperties, state: TabSetState, input: TabSetInput): Reduction<TabSetState, TabSetEvent> {
        val reduced = when (input) {
            is TabSetInput.Stroke -> stroke(properties, state, input.stroke)
            is TabSetInput.Point ->
                if (!properties.choosable(input.key)) Reduction(state)
                else Reduction(state.copy(roving = state.roving.copy(active = input.key)), properties.select(input.key),
                    commands = listOf(LocalCommand.Focus(PartKey(input.key)), LocalCommand.Reveal(PartKey(input.key))))
            is TabSetInput.Close -> close(properties, state, input.key).let { if (it.events.isEmpty()) it else it.copy(cues = listOf(FeedbackCue.Impact)) }
            is TabSetInput.Focused -> rove(properties, state.copy(within = true), RovingInput.Focused(input.key))
            TabSetInput.Blurred -> reconcile(properties, state.copy(within = false))
            is TabSetInput.TypingElapsed -> rove(properties, state, RovingInput.TypingElapsed(input.ticket))
        }
        return reduced.copy(state = reduced.state.copy(activeIndex = properties.tabs.indexOf(reduced.state.active)))
    }

    override fun reconcile(properties: TabSetProperties, state: TabSetState): Reduction<TabSetState, TabSetEvent> {
        val tabs = properties.tabs
        val active = when {
            !state.within && properties.selected?.let(properties::choosable) == true -> properties.selected
            state.active != null && tabs.indexOf(state.active).let { it >= 0 && tabs.enabled(it) } -> state.active
            state.active == null -> initial(properties).active
            else -> nearest(tabs, state.activeIndex.coerceAtMost(tabs.size - 1))
        }
        val reveal = properties.selected?.takeIf { it != state.shown && tabs.indexOf(it) >= 0 }
        return Reduction(
            TabSetState(state.roving.copy(active = active), tabs.indexOf(active), properties.selected, state.within),
            commands = listOfNotNull(reveal?.let { LocalCommand.Reveal(PartKey(it)) }),
        )
    }

    fun handles(properties: TabSetProperties, stroke: KeyStroke): Boolean = action(properties, stroke) != null

    private fun stroke(properties: TabSetProperties, state: TabSetState, stroke: KeyStroke): Reduction<TabSetState, TabSetEvent> {
        val active = state.active
        return when (action(properties, stroke)) {
            null -> Reduction(state)
            Action.Select -> Reduction(state, active?.let { properties.select(it) }.orEmpty())
            Action.Close -> if (active == null) Reduction(state) else close(properties, state, active)
            Action.Move -> rove(properties, state, RovingInput.Stroke(stroke))
        }
    }

    private fun close(properties: TabSetProperties, state: TabSetState, key: String): Reduction<TabSetState, TabSetEvent> {
        if (key !in properties.closable || properties.tabs.indexOf(key) < 0) return Reduction(state)
        val request = listOf<TabSetEvent>(TabSetEvent.CloseRequest(key))
        if (state.active != key) return Reduction(state, request)
        val next = properties.tabs.afterClosing(key) ?: return Reduction(state, request)
        val moved = roving.moveTo(state.roving, next)
        return Reduction(state.copy(roving = moved.state), request, commands = moved.commands.map(::retarget))
    }

    private fun rove(properties: TabSetProperties, state: TabSetState, input: RovingInput): Reduction<TabSetState, TabSetEvent> {
        val roved = roving.reduce(properties.roving(), state.roving, input)
        return Reduction(state.copy(roving = roved.state), commands = roved.commands.map(::retarget))
    }

    private fun action(properties: TabSetProperties, stroke: KeyStroke): Action? {
        if (stroke.meta || stroke.control || stroke.alt) return null
        return when {
            stroke.shift -> if (roving.handles(properties.roving(), stroke)) Action.Move else null
            stroke.key == KeyName.Enter || stroke.key == KeyName.Space -> Action.Select
            stroke.key == KeyName.Delete -> Action.Close
            roving.handles(properties.roving(), stroke) -> Action.Move
            else -> null
        }
    }

    private enum class Action { Move, Select, Close }
}

private fun TabSetProperties.roving() = RovingProperties(tabs, Axis.Horizontal, wrap = true, rightToLeft = rightToLeft)

private fun TabSetProperties.choosable(key: String): Boolean = tabs.indexOf(key).let { it >= 0 && tabs.enabled(it) }

private fun TabSetProperties.select(key: String): List<TabSetEvent> = if (key == selected) emptyList() else listOf(TabSetEvent.Select(key))

private fun CollectionItems.afterClosing(key: String): String? {
    val index = indexOf(key)
    return ((index + 1 until size) + (index - 1 downTo 0)).firstOrNull(::enabled)?.let(::key)
}

private fun retarget(command: LocalCommand): LocalCommand =
    if (command is LocalCommand.Schedule<*>) LocalCommand.Schedule(command.ticket, command.after, TabSetInput.TypingElapsed(command.ticket)) else command
