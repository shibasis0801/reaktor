package dev.shibasis.reaktor.surface

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

enum class Edge { First, Last }

data class MenuProperties(
    val expanded: Boolean,
    val enabled: Boolean = true,
    val items: RovingItems = RovingList(emptyList()),
    val submenus: Set<String> = emptySet(),
    val rightToLeft: Boolean = false,
    val nested: Boolean = false,
)

data class MenuState(
    val mounted: Boolean = false,
    val lastSequence: Long = 0,
    val roving: RovingState = RovingState(),
    val submenu: String? = null,
    val intent: Ticket? = null,
    val opening: Edge? = Edge.First,
)

sealed interface MenuInput {
    data class Toggle(val sequence: Long) : MenuInput
    data class Open(val sequence: Long, val edge: Edge) : MenuInput
    data class Choose(val key: String, val sequence: Long) : MenuInput
    data class Stroke(val stroke: KeyStroke) : MenuInput
    data class Hover(val key: String) : MenuInput
    data class Focused(val key: String) : MenuInput
    data object Mounted : MenuInput
    data object Unmounted : MenuInput
    data object Dismiss : MenuInput
    data class IntentElapsed(val ticket: Ticket) : MenuInput
    data class TypingElapsed(val ticket: Ticket) : MenuInput
    data object SubmenuClosed : MenuInput
}

sealed interface MenuEvent {
    data class ExpandedChange(val expanded: Boolean) : MenuEvent
    data class Chosen(val key: String) : MenuEvent
    data class SubmenuChange(val key: String?, val edge: Edge? = null) : MenuEvent
}

data class MenuKernel(
    val roving: RovingKernel = RovingKernel(),
    val submenuDelay: Duration = 200.milliseconds,
) : BehaviorKernel<MenuProperties, MenuState, MenuInput, MenuEvent> {

    override fun initial(properties: MenuProperties) = MenuState(opening = resting(properties))

    override fun reduce(properties: MenuProperties, state: MenuState, input: MenuInput): Reduction<MenuState, MenuEvent> =
        when (input) {
            is MenuInput.Toggle ->
                if (!properties.enabled || input.sequence <= state.lastSequence) Reduction(state)
                else Reduction(state.copy(lastSequence = input.sequence, opening = Edge.First), listOf(MenuEvent.ExpandedChange(!properties.expanded)))
            is MenuInput.Open -> when {
                !properties.enabled || input.sequence <= state.lastSequence -> Reduction(state)
                properties.expanded -> focusOn(state.copy(lastSequence = input.sequence), properties.items.edge(input.edge))
                else -> Reduction(state.copy(lastSequence = input.sequence, opening = input.edge), listOf(MenuEvent.ExpandedChange(true)))
            }
            is MenuInput.Choose -> when {
                !properties.expanded || input.sequence <= state.lastSequence -> Reduction(state)
                input.key in properties.submenus -> openSubmenu(state.copy(lastSequence = input.sequence), input.key, Edge.First)
                else -> Reduction(
                    state.copy(lastSequence = input.sequence, submenu = null, intent = null),
                    listOf(MenuEvent.Chosen(input.key), MenuEvent.ExpandedChange(false)),
                    listOf(FeedbackCue.Impact),
                    cancel(state.intent),
                )
            }
            is MenuInput.Stroke -> if (properties.expanded) stroke(properties, state, input.stroke) else Reduction(state)
            is MenuInput.Hover -> if (properties.expanded) hover(properties, state, input.key) else Reduction(state)
            is MenuInput.Focused -> rove(properties, state, RovingInput.Focused(input.key))
            MenuInput.Mounted -> focusOn(state.copy(mounted = true, opening = null), state.opening?.let(properties.items::edge))
            MenuInput.Unmounted -> Reduction(
                state.copy(mounted = false, submenu = null, intent = null, opening = resting(properties), roving = state.roving.copy(typed = "", typing = null)),
                commands = cancel(state.intent) + cancel(state.roving.typing) + LocalCommand.Focus(DisclosureKernel.Trigger),
            )
            MenuInput.Dismiss -> if (properties.expanded) close(state) else Reduction(state)
            is MenuInput.IntentElapsed -> if (input.ticket != state.intent) Reduction(state) else settle(properties, state.copy(intent = null))
            is MenuInput.TypingElapsed -> rove(properties, state, RovingInput.TypingElapsed(input.ticket))
            MenuInput.SubmenuClosed -> state.submenu?.let { key ->
                Reduction(
                    state.copy(submenu = null, intent = null, roving = state.roving.copy(active = key)),
                    listOf(MenuEvent.SubmenuChange(null)),
                    commands = cancel(state.intent) + LocalCommand.Focus(PartKey(key)),
                )
            } ?: Reduction(state)
        }

    override fun reconcile(properties: MenuProperties, state: MenuState): Reduction<MenuState, MenuEvent> {
        val roving = roving.reconcile(properties.roving(), state.roving).state
        val submenu = state.submenu?.takeIf { it in properties.submenus }
        val next = state.copy(roving = roving, submenu = submenu)
        return if (submenu == state.submenu) Reduction(next) else Reduction(next, listOf(MenuEvent.SubmenuChange(null)))
    }

    private fun stroke(properties: MenuProperties, state: MenuState, stroke: KeyStroke): Reduction<MenuState, MenuEvent> {
        if (stroke.meta || stroke.control || stroke.alt) return Reduction(state)
        val forward = if (properties.rightToLeft) KeyName.Left else KeyName.Right
        val back = if (properties.rightToLeft) KeyName.Right else KeyName.Left
        val active = state.roving.active
        return when {
            stroke.key == KeyName.Tab -> close(state)
            stroke.shift -> rove(properties, state, RovingInput.Stroke(stroke))
            stroke.key == KeyName.Escape -> close(state)
            stroke.key == forward && active != null && active in properties.submenus -> openSubmenu(state, active, Edge.First)
            stroke.key == back && properties.nested -> close(state)
            else -> rove(properties, state, RovingInput.Stroke(stroke))
        }
    }

    private fun hover(properties: MenuProperties, state: MenuState, key: String): Reduction<MenuState, MenuEvent> {
        val moved = rove(properties, state, RovingInput.Point(key, focus = true))
        val wanted = if (key in properties.submenus) key != state.submenu else state.submenu != null
        if (!wanted) return Reduction(moved.state.copy(intent = null), commands = moved.commands + cancel(state.intent))
        val ticket = Ticket(moved.state.roving.nextTicket)
        return Reduction(
            moved.state.copy(intent = ticket, roving = moved.state.roving.copy(nextTicket = ticket.value + 1)),
            commands = moved.commands + cancel(state.intent) + LocalCommand.Schedule(ticket, submenuDelay, MenuInput.IntentElapsed(ticket)),
        )
    }

    private fun settle(properties: MenuProperties, state: MenuState): Reduction<MenuState, MenuEvent> {
        val active = state.roving.active
        return when {
            active != null && active in properties.submenus && active != state.submenu ->
                Reduction(state.copy(submenu = active), listOf(MenuEvent.SubmenuChange(active)))
            active !in properties.submenus && state.submenu != null ->
                Reduction(state.copy(submenu = null), listOf(MenuEvent.SubmenuChange(null)))
            else -> Reduction(state)
        }
    }

    private fun openSubmenu(state: MenuState, key: String, edge: Edge): Reduction<MenuState, MenuEvent> = Reduction(
        state.copy(submenu = key, intent = null, roving = state.roving.copy(active = key)),
        listOf(MenuEvent.SubmenuChange(key, edge)),
        commands = cancel(state.intent),
    )

    private fun close(state: MenuState): Reduction<MenuState, MenuEvent> =
        Reduction(state.copy(intent = null), listOf(MenuEvent.ExpandedChange(false)), commands = cancel(state.intent))

    private fun focusOn(state: MenuState, key: String?): Reduction<MenuState, MenuEvent> =
        if (key == null) Reduction(state)
        else Reduction(
            state.copy(roving = state.roving.copy(active = key)),
            commands = listOf(LocalCommand.Focus(PartKey(key)), LocalCommand.Reveal(PartKey(key))),
        )

    private fun rove(properties: MenuProperties, state: MenuState, input: RovingInput): Reduction<MenuState, MenuEvent> {
        val moved = roving.reduce(properties.roving(), state.roving, input)
        return Reduction(state.copy(roving = moved.state), commands = moved.commands.map(::retarget))
    }

    private fun retarget(command: LocalCommand): LocalCommand =
        if (command is LocalCommand.Schedule<*>) LocalCommand.Schedule(command.ticket, command.after, MenuInput.TypingElapsed(command.ticket)) else command

    private fun cancel(ticket: Ticket?): List<LocalCommand> = listOfNotNull(ticket?.let(LocalCommand::Cancel))

    private fun resting(properties: MenuProperties): Edge? = if (properties.nested) null else Edge.First
}

private fun MenuProperties.roving() = RovingProperties(items, Axis.Vertical, wrap = true, rightToLeft = rightToLeft)

private fun RovingItems.edge(edge: Edge): String? {
    val indices = if (edge == Edge.First) 0 until size else size - 1 downTo 0
    return indices.firstOrNull(::enabled)?.let(::key)
}
