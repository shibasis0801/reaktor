package dev.shibasis.reaktor.surface

interface CollectionItems : RovingItems {
    override fun indexOf(key: String): Int
    override fun enabled(index: Int): Boolean = true
    override fun text(index: Int): String? = null
    fun selectable(index: Int): Boolean = true
}

interface TreeItems : CollectionItems {
    fun depth(index: Int): Int
    fun parent(index: Int): Int
    fun expandable(index: Int): Boolean
    fun expanded(index: Int): Boolean
}

enum class SelectionMode { None, Single, Multiple }

data class CollectionProperties(
    val items: CollectionItems,
    val selection: Set<String>,
    val keys: KeyConvention,
    val mode: SelectionMode = SelectionMode.Single,
    val rightToLeft: Boolean = false,
    val enabled: Boolean = true,
)

data class CollectionState(
    val roving: RovingState = RovingState(),
    val activeIndex: Int = -1,
    val anchor: String? = null,
    val hovered: String? = null,
    val within: Boolean = false,
) {
    val active: String? get() = roving.active
}

sealed interface CollectionInput {
    data class Stroke(val stroke: KeyStroke, val page: Int) : CollectionInput
    data class Press(val key: String, val extend: Boolean, val toggle: Boolean, val clicks: Int) : CollectionInput
    data class Secondary(val key: String, val atPointer: Boolean) : CollectionInput
    data class Focused(val key: String) : CollectionInput
    data object Blurred : CollectionInput
    data class Hover(val key: String?) : CollectionInput
    data class Expand(val key: String, val expanded: Boolean) : CollectionInput
    data class TypingElapsed(val ticket: Ticket) : CollectionInput
}

sealed interface CollectionEvent {
    data class SelectionChange(val selection: Set<String>) : CollectionEvent
    data class Activate(val key: String) : CollectionEvent
    data class ExpansionChange(val key: String, val expanded: Boolean) : CollectionEvent
    data class MenuRequest(val key: String, val atPointer: Boolean) : CollectionEvent
}

data class CollectionKernel(val roving: RovingKernel = RovingKernel()) :
    BehaviorKernel<CollectionProperties, CollectionState, CollectionInput, CollectionEvent> {

    override fun initial(properties: CollectionProperties) = reconcile(properties, CollectionState()).state

    override fun reduce(
        properties: CollectionProperties,
        state: CollectionState,
        input: CollectionInput,
    ): Reduction<CollectionState, CollectionEvent> {
        val reduced: Reduction<CollectionState, CollectionEvent> = when (input) {
            is CollectionInput.Stroke -> stroke(properties, state, input)
            is CollectionInput.Press -> when {
                !properties.reachable(input.key) -> Reduction(state)
                input.clicks == 1 -> point(properties, state, input.key, if (input.extend) Pick.Range else if (input.toggle) Pick.Toggle else Pick.Only)
                input.clicks == 2 -> Reduction(state, listOf(CollectionEvent.Activate(input.key)))
                else -> Reduction(state)
            }
            is CollectionInput.Secondary -> when {
                !properties.reachable(input.key) -> Reduction(state)
                !properties.selectable(input.key) -> rove(properties, state, RovingInput.Point(input.key, focus = true))
                else -> {
                    val pointed =
                        if (input.key in properties.selection) rove(properties, state, RovingInput.Point(input.key, focus = true))
                        else point(properties, state, input.key, Pick.Only)
                    pointed.copy(events = pointed.events + CollectionEvent.MenuRequest(input.key, input.atPointer))
                }
            }
            is CollectionInput.Focused -> rove(properties, state.copy(within = true), RovingInput.Focused(input.key))
            CollectionInput.Blurred -> reconcile(properties, state.copy(within = false))
            is CollectionInput.Hover -> Reduction(state.copy(hovered = input.key?.takeIf { properties.items.indexOf(it) >= 0 }))
            is CollectionInput.Expand -> if (properties.enabled) gather(properties, expand(properties, state, input.key) { input.expanded }, input) else Reduction(state)
            is CollectionInput.TypingElapsed -> rove(properties, state, RovingInput.TypingElapsed(input.ticket))
        }
        return reduced.copy(state = reduced.state.copy(activeIndex = properties.items.indexOf(reduced.state.active)))
    }

    override fun reconcile(properties: CollectionProperties, state: CollectionState): Reduction<CollectionState, CollectionEvent> {
        val items = properties.items
        val index = items.indexOf(state.active)
        val followed = if (state.within && state.active != null) null else properties.firstSelected()
        val active = followed
            ?: if (state.active == null) items.firstEnabled()
            else nearest(items, if (index >= 0) index else state.activeIndex.coerceAtMost(items.size - 1))
        return Reduction(
            state.copy(
                roving = state.roving.copy(active = active),
                activeIndex = items.indexOf(active),
                anchor = state.anchor?.takeIf { items.indexOf(it) >= 0 },
                hovered = state.hovered?.takeIf { items.indexOf(it) >= 0 },
            ),
        )
    }

    fun handles(properties: CollectionProperties, stroke: KeyStroke): Boolean = action(properties, stroke) != null

    private fun stroke(properties: CollectionProperties, state: CollectionState, input: CollectionInput.Stroke): Reduction<CollectionState, CollectionEvent> {
        val active = state.active
        return when (val action = action(properties, input.stroke)) {
            null -> Reduction(state)
            is Action.Move -> move(properties, state, input, action.pick)
            is Action.Branch -> branch(properties, state, action.open)
            Action.Open -> Reduction(state, listOfNotNull(active?.let(CollectionEvent::Activate)))
            Action.Menu -> Reduction(state, listOfNotNull(active?.takeIf(properties::selectable)?.let { CollectionEvent.MenuRequest(it, atPointer = false) }))
            Action.Clear -> Reduction(state, properties.request(emptySet()))
            Action.All -> Reduction(state, properties.request(properties.items.keys(0 until properties.items.size)))
            Action.Toggle -> when {
                properties.mode != SelectionMode.Multiple || active == null || !properties.selectable(active) -> expand(properties, state, active) { !it }
                else -> land(properties, state, active, roving.moveTo(state.roving, active), Pick.Toggle)
            }
        }
    }

    private fun move(properties: CollectionProperties, state: CollectionState, input: CollectionInput.Stroke, pick: Pick): Reduction<CollectionState, CollectionEvent> {
        val items = properties.items
        val page = input.page.coerceAtLeast(1)
        val moved = when (input.stroke.key) {
            KeyName.PageUp -> roving.moveTo(state.roving, items.page(items.indexOf(state.active) - page))
            KeyName.PageDown -> roving.moveTo(state.roving, items.page(items.indexOf(state.active) + page))
            else -> roving.reduce(properties.roving(), state.roving, RovingInput.Stroke(input.stroke.copy(meta = false, control = false, shift = false)))
        }
        val target = moved.state.active
        return if (target == null || target == state.active) Reduction(state.copy(roving = moved.state), commands = moved.commands.map(::retarget))
        else land(properties, state, target, moved, pick)
    }

    private fun branch(properties: CollectionProperties, state: CollectionState, open: Boolean): Reduction<CollectionState, CollectionEvent> {
        val disclosed = expand(properties, state, state.active) { open }
        val items = properties.items as? TreeItems ?: return disclosed
        val index = items.indexOf(state.active)
        if (disclosed.events.isNotEmpty() || index < 0) return disclosed
        val next = if (open) (index + 1).takeIf { it < items.size && items.parent(it) == index } else items.parent(index).takeIf { it >= 0 }
        val target = next?.takeIf(items::enabled)?.let(items::key) ?: return disclosed
        return land(properties, state, target, roving.moveTo(state.roving, target), Pick.Only)
    }

    private fun expand(properties: CollectionProperties, state: CollectionState, key: String?, wanted: (Boolean) -> Boolean): Reduction<CollectionState, CollectionEvent> {
        val items = properties.items as? TreeItems ?: return Reduction(state)
        val index = items.indexOf(key)
        if (index < 0 || !items.expandable(index)) return Reduction(state)
        val now = items.expanded(index)
        val expanded = wanted(now)
        return if (expanded == now) Reduction(state) else Reduction(state, listOf(CollectionEvent.ExpansionChange(items.key(index), expanded)))
    }

    private fun gather(
        properties: CollectionProperties,
        expanded: Reduction<CollectionState, CollectionEvent>,
        input: CollectionInput.Expand,
    ): Reduction<CollectionState, CollectionEvent> {
        val items = properties.items as? TreeItems ?: return expanded
        if (input.expanded || expanded.events.isEmpty() || !items.holds(items.indexOf(input.key), items.indexOf(expanded.state.active))) return expanded
        val moved = roving.moveTo(expanded.state.roving, input.key)
        return expanded.copy(state = expanded.state.copy(roving = moved.state), commands = moved.commands.map(::retarget))
    }

    private fun point(properties: CollectionProperties, state: CollectionState, key: String, pick: Pick) =
        land(properties, state, key, roving.reduce(properties.roving(), state.roving, RovingInput.Point(key, focus = true)), pick)

    private fun land(
        properties: CollectionProperties,
        state: CollectionState,
        target: String,
        moved: Reduction<RovingState, *>,
        pick: Pick,
    ): Reduction<CollectionState, CollectionEvent> {
        val anchor = if (pick == Pick.Range) state.anchor ?: state.active ?: target else target
        return Reduction(
            state.copy(roving = moved.state, anchor = anchor),
            properties.request(choose(properties, anchor, target, pick)),
            commands = moved.commands.map(::retarget),
        )
    }

    private fun choose(properties: CollectionProperties, anchor: String, target: String, pick: Pick): Set<String> {
        val selection = properties.selection
        if (!properties.selectable(target) && (properties.mode != SelectionMode.Multiple || pick != Pick.Range)) return selection
        return when (properties.mode) {
            SelectionMode.None -> selection
            SelectionMode.Single -> setOf(target)
            SelectionMode.Multiple -> when (pick) {
                Pick.Only -> setOf(target)
                Pick.Range -> properties.items.span(anchor, target)
                Pick.Keep -> selection
                Pick.Toggle -> if (target in selection) selection - target else selection + target
            }
        }
    }

    private fun rove(properties: CollectionProperties, state: CollectionState, input: RovingInput): Reduction<CollectionState, CollectionEvent> {
        val roved = roving.reduce(properties.roving(), state.roving, input)
        return Reduction(state.copy(roving = roved.state), commands = roved.commands.map(::retarget))
    }

    private fun action(properties: CollectionProperties, stroke: KeyStroke): Action? {
        val mac = properties.keys == KeyConvention.Mac
        val primary = if (mac) stroke.meta else stroke.control
        val foreign = stroke.alt || (if (mac) stroke.control else stroke.meta)
        if (!properties.enabled || foreign || primary && stroke.shift) return null
        val plain = !primary && !stroke.shift
        val tree = properties.items is TreeItems
        val mode = properties.mode
        return when (stroke.key) {
            KeyName.Up, KeyName.Down, KeyName.Home, KeyName.End, KeyName.PageUp, KeyName.PageDown ->
                Action.Move(if (primary) Pick.Keep else if (stroke.shift) Pick.Range else Pick.Only)
            KeyName.Left, KeyName.Right -> if (tree && plain) Action.Branch((stroke.key == KeyName.Right) != properties.rightToLeft) else null
            KeyName.Enter -> if (plain) Action.Open else null
            KeyName.Escape -> if (plain && mode != SelectionMode.None && properties.selection.isNotEmpty()) Action.Clear else null
            KeyName.Space -> if (plain && (mode == SelectionMode.Multiple || tree && mode == SelectionMode.Single)) Action.Toggle else null
            KeyName.F10 -> if (stroke.shift) Action.Menu else null
            KeyName.ContextMenu -> if (plain) Action.Menu else null
            KeyName.A -> if (primary && mode == SelectionMode.Multiple) Action.All else typed(properties, stroke)
            else -> typed(properties, stroke)
        }
    }

    private fun typed(properties: CollectionProperties, stroke: KeyStroke): Action? =
        if (roving.handles(properties.roving(), stroke)) Action.Move(Pick.Only) else null

    private enum class Pick { Only, Range, Keep, Toggle }

    private sealed interface Action {
        data class Move(val pick: Pick) : Action
        data class Branch(val open: Boolean) : Action
        data object Open : Action
        data object Clear : Action
        data object Toggle : Action
        data object All : Action
        data object Menu : Action
    }
}

private fun CollectionProperties.roving() = RovingProperties(items, Axis.Vertical, wrap = false)

private fun CollectionProperties.reachable(key: String): Boolean = enabled && items.indexOf(key).let { it >= 0 && items.enabled(it) }

private fun CollectionProperties.selectable(key: String): Boolean = reachable(key) && items.selectable(items.indexOf(key))

private fun CollectionProperties.request(next: Set<String>): List<CollectionEvent> =
    if (next == selection) emptyList() else listOf(CollectionEvent.SelectionChange(next))

private fun CollectionProperties.firstSelected(): String? =
    selection.map { items.indexOf(it) }.filter { it >= 0 && items.enabled(it) }.minOrNull()?.let(items::key)

private fun CollectionItems.keys(indices: IntProgression): Set<String> = indices.filter { enabled(it) && selectable(it) }.map(::key).toSet()

private fun CollectionItems.span(from: String, to: String): Set<String> {
    val end = indexOf(to)
    val start = indexOf(from).takeIf { it >= 0 } ?: end
    return keys(minOf(start, end)..maxOf(start, end))
}

private fun TreeItems.holds(branch: Int, index: Int): Boolean {
    var at = if (index >= 0) parent(index) else -1
    while (at >= 0) {
        if (at == branch) return true
        at = parent(at)
    }
    return false
}

private fun CollectionItems.page(target: Int): String? = if (size == 0) null else nearest(this, target.coerceIn(0, size - 1))

private fun retarget(command: LocalCommand): LocalCommand =
    if (command is LocalCommand.Schedule<*>) LocalCommand.Schedule(command.ticket, command.after, CollectionInput.TypingElapsed(command.ticket)) else command
