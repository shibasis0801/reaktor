package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import dev.shibasis.reaktor.surface.Availability
import dev.shibasis.reaktor.surface.BehaviorKernel
import dev.shibasis.reaktor.surface.Command
import dev.shibasis.reaktor.surface.CommandEntry
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.CommandSet
import dev.shibasis.reaktor.surface.DisclosureKernel
import dev.shibasis.reaktor.surface.DisclosureProperties
import dev.shibasis.reaktor.surface.DisclosureState
import dev.shibasis.reaktor.surface.Edge
import dev.shibasis.reaktor.surface.FeedbackCue
import dev.shibasis.reaktor.surface.KeyName
import dev.shibasis.reaktor.surface.KeyStroke
import dev.shibasis.reaktor.surface.Mark
import dev.shibasis.reaktor.surface.MenuEvent
import dev.shibasis.reaktor.surface.MenuInput
import dev.shibasis.reaktor.surface.MenuKernel
import dev.shibasis.reaktor.surface.MenuProperties
import dev.shibasis.reaktor.surface.MenuState
import dev.shibasis.reaktor.surface.PartKey
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.label
import kotlinx.coroutines.CoroutineScope
import kotlin.math.roundToInt

typealias MenuBehavior = BehaviorKernel<MenuProperties, MenuState, MenuInput, MenuEvent>

@Composable
fun Menu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    anchor: OverlayAnchor? = null,
    placement: Placement = Placement(),
    behavior: MenuBehavior = MenuKernel(),
    content: @Composable MenuScope.() -> Unit,
) {
    val level = rememberMenuLevel(expanded, enabled, behavior, null, onExpandedChange)
    val own = remember { mutableStateOf(IntRect.Zero) }
    Box(modifier.onGloballyPositioned { own.value = it.boundsInWindow().roundToIntRect() }, propagateMinConstraints = true) {
        MenuScope(level, expanded, enabled, { anchor?.window() ?: own.value }, placement).content()
    }
}

@Composable
fun Menu(
    state: ExpandedState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    anchor: OverlayAnchor? = null,
    placement: Placement = Placement(),
    behavior: MenuBehavior = MenuKernel(),
    content: @Composable MenuScope.() -> Unit,
) = Menu(state.expanded, { state.expanded = it }, modifier, enabled, anchor, placement, behavior, content)

@Stable
class MenuScope internal constructor(
    private val level: MenuLevel,
    private val expanded: Boolean,
    private val enabled: Boolean,
    private val anchor: () -> IntRect,
    private val placement: Placement,
) {
    @Composable
    fun Trigger(
        modifier: Modifier = Modifier,
        appearance: ButtonAppearance = LocalAppearances.current.button,
        content: @Composable () -> Unit,
    ) = PressPart(modifier.part(level.machine, DisclosureKernel.Trigger).onKeyEvent(level::openFromTrigger), enabled, appearance, content) {
        level.machine.send(MenuInput.Toggle(level.machine.nextSequence()))
    }

    @Composable
    fun Popup(
        appearance: PanelAppearance = LocalAppearances.current.menuPanel,
        content: @Composable MenuPopupScope.() -> Unit,
    ) = level.Popup(expanded, anchor, placement, appearance, content)
}

@Stable
class MenuPopupScope internal constructor(private val level: MenuLevel, private val panel: PanelAppearance) {
    @Composable
    fun Item(
        key: String,
        onActivate: () -> Unit,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        typeahead: String? = null,
        appearance: ButtonAppearance = LocalAppearances.current.menuItem,
        content: @Composable MenuItemScope.() -> Unit,
    ) = level.Item(key, modifier, enabled, typeahead, appearance, MenuItemScope(null, false), onActivate, content)

    @Composable
    fun CheckItem(
        key: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        typeahead: String? = null,
        appearance: ButtonAppearance = LocalAppearances.current.menuItem,
        content: @Composable MenuItemScope.() -> Unit,
    ) = level.Item(
        key,
        modifier.semantics { toggleableState = ToggleableState(checked) },
        enabled,
        typeahead,
        appearance,
        MenuItemScope(checked, false),
        { onCheckedChange(!checked) },
        content,
    )

    @Composable
    fun RadioItem(
        key: String,
        selected: Boolean,
        onSelect: () -> Unit,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        typeahead: String? = null,
        appearance: ButtonAppearance = LocalAppearances.current.menuItem,
        content: @Composable MenuItemScope.() -> Unit,
    ) = level.Item(key, modifier.semantics { this.selected = selected }, enabled, typeahead, appearance, MenuItemScope(selected, false), onSelect, content)

    @Composable
    fun Submenu(
        key: String,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
        typeahead: String? = null,
        appearance: ButtonAppearance = LocalAppearances.current.menuItem,
        trigger: @Composable MenuItemScope.() -> Unit,
        content: @Composable MenuPopupScope.() -> Unit,
    ) = level.Submenu(key, modifier, enabled, typeahead, appearance, panel, trigger, content)

    @Composable
    fun Separator(
        modifier: Modifier = Modifier,
        appearance: SeparatorAppearance = LocalAppearances.current[Appearance.Separator],
    ) = Box(modifier, propagateMinConstraints = true) {
        appearance.Content(Unit, Unit, LocalThemeSnapshot.current, rememberFeedback(pressed = false, focusVisible = false), Unit)
    }

    @Composable
    fun Label(modifier: Modifier = Modifier, content: @Composable () -> Unit) = Box(modifier, propagateMinConstraints = true) { content() }
}

@Stable
class MenuItemScope internal constructor(val checked: Boolean?, val opensSubmenu: Boolean) {
    @Composable
    fun Indicator(content: @Composable () -> Unit) {
        if (checked == true) content()
    }
}

sealed interface CommandLine {
    data class Item(val label: String, val chord: String?, val enabled: Boolean, val checked: Boolean?, val opensSubmenu: Boolean) : CommandLine
    data class Caption(val text: String) : CommandLine
}

typealias CommandAppearance = ComposeAppearance<CommandLine, Unit, Unit>

val BareCommand: CommandAppearance = object : CommandAppearance {
    @Composable
    override fun Content(properties: CommandLine, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: Unit) {
        when (properties) {
            is CommandLine.Item -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(if (properties.checked == true) "✓" else "", Modifier.width(16.dp))
                BasicText(properties.label, Modifier.weight(1f))
                properties.chord?.let { BasicText(it) }
                if (properties.opensSubmenu) BasicText("›")
            }
            is CommandLine.Caption -> BasicText(properties.text)
        }
    }
}

@Composable
fun MenuPopupScope.Commands(
    set: CommandSet,
    entries: List<CommandEntry>,
    onInvoke: (CommandId) -> Unit,
    appearance: CommandAppearance = LocalAppearances.current[Appearance.Command],
) {
    val keys = LocalSurfaceEnvironment.current.keys
    entries.forEach { entry ->
        when (entry) {
            is CommandEntry.Item -> set.commands.firstOrNull { it.id == entry.id }?.let { command ->
                key(command.id.value) {
                    val line = CommandLine.Item(
                        command.label,
                        command.chord?.label(keys)?.takeIf(String::isNotEmpty)?.let(::isolated),
                        command.availability == Availability.Available,
                        when (val mark = command.mark) {
                            is Mark.Check -> mark.on
                            is Mark.Choice -> mark.on
                            null -> null
                        },
                        false,
                    )
                    CommandItem(command, line, onInvoke, appearance)
                }
            }
            is CommandEntry.Group -> key(entry.label) {
                Submenu(entry.label, typeahead = entry.label, trigger = { CommandLook(appearance, CommandLine.Item(entry.label, null, true, null, true)) }) {
                    Commands(set, entry.entries, onInvoke, appearance)
                }
            }
            is CommandEntry.Caption -> Label { CommandLook(appearance, CommandLine.Caption(entry.text)) }
            CommandEntry.Separator -> Separator()
        }
    }
}

@Composable
private fun MenuPopupScope.CommandItem(command: Command, line: CommandLine.Item, onInvoke: (CommandId) -> Unit, appearance: CommandAppearance) {
    val content: @Composable MenuItemScope.() -> Unit = { CommandLook(appearance, line) }
    val item: @Composable () -> Unit = {
        when (val mark = command.mark) {
            is Mark.Check -> CheckItem(command.id.value, mark.on, { onInvoke(command.id) }, enabled = line.enabled, typeahead = command.label, content = content)
            is Mark.Choice -> RadioItem(command.id.value, mark.on, { onInvoke(command.id) }, enabled = line.enabled, typeahead = command.label, content = content)
            null -> Item(command.id.value, { onInvoke(command.id) }, enabled = line.enabled, typeahead = command.label, content = content)
        }
    }
    val reason = (command.availability as? Availability.Unavailable)?.reason
    if (reason == null) item() else Tooltip(tip = { CommandLook(appearance, CommandLine.Caption(reason)) }, content = item)
}

@Composable
private fun CommandLook(appearance: CommandAppearance, line: CommandLine) =
    appearance.Content(line, Unit, LocalThemeSnapshot.current, rememberFeedback(pressed = false, focusVisible = false), Unit)

internal fun isolated(text: String): String = "⁦$text⁩"

@Stable
internal class MenuLevel(
    val behavior: MenuBehavior,
    scope: CoroutineScope,
    private var properties: MenuProperties,
    val parent: MenuLevel?,
    private val onExpandedChange: (Boolean) -> Unit,
    onCue: (FeedbackCue) -> Unit,
) {
    val entries = RovingEntries()
    val submenus = mutableSetOf<String>()
    val choices = mutableMapOf<String, () -> Unit>()
    val children = mutableMapOf<String, MenuLevel>()
    val machine = Machine(behavior, properties, scope, ::event, onCue)

    fun update(expanded: Boolean, enabled: Boolean, rightToLeft: Boolean) {
        properties = properties.copy(expanded = expanded, enabled = enabled, rightToLeft = rightToLeft)
        publish()
    }

    fun publish() = machine.reconcile(properties.copy(items = entries.list(properties.rightToLeft), submenus = submenus.toSet()))

    fun root(): MenuLevel = parent?.root() ?: this

    fun openFromTrigger(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val edge = when (event.stroke()) {
            KeyStroke(KeyName.Down) -> Edge.First
            KeyStroke(KeyName.Up) -> Edge.Last
            else -> return false
        }
        machine.send(MenuInput.Open(machine.nextSequence(), edge))
        return true
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val stroke = event.stroke() ?: return false
        if (stroke.meta || stroke.control || stroke.alt) return false
        val printable = stroke.character?.let { !it.isWhitespace() } == true
        if (stroke.key !in MenuKeys && !printable) return false
        if (stroke.key == KeyName.Tab) root().machine.send(MenuInput.Stroke(stroke)) else machine.send(MenuInput.Stroke(stroke))
        return true
    }

    private fun event(event: MenuEvent) {
        when (event) {
            is MenuEvent.ExpandedChange ->
                if (parent == null) onExpandedChange(event.expanded)
                else if (!event.expanded) parent.machine.send(MenuInput.SubmenuClosed)
            is MenuEvent.Chosen -> {
                choices[event.key]?.invoke()
                if (parent != null) root().machine.send(MenuInput.Dismiss)
            }
            is MenuEvent.SubmenuChange -> {
                val key = event.key ?: return
                val edge = event.edge ?: return
                children[key]?.machine?.let { child -> child.send(MenuInput.Open(child.nextSequence(), edge)) }
            }
        }
    }
}

private val MenuKeys = setOf(KeyName.Up, KeyName.Down, KeyName.Left, KeyName.Right, KeyName.Home, KeyName.End, KeyName.Escape, KeyName.Tab)

@Composable
internal fun rememberMenuLevel(
    expanded: Boolean,
    enabled: Boolean,
    behavior: MenuBehavior,
    parent: MenuLevel?,
    onExpandedChange: (Boolean) -> Unit,
): MenuLevel {
    val scope = rememberCoroutineScope()
    val changed by rememberUpdatedState(onExpandedChange)
    val cue by rememberUpdatedState(LocalCuePlayer.current)
    val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
    val level = remember(behavior, parent) {
        MenuLevel(behavior, scope, MenuProperties(expanded, enabled, rightToLeft = rightToLeft, nested = parent != null), parent, { changed(it) }, { cue(it) })
    }
    SideEffect { level.update(expanded, enabled, rightToLeft) }
    DisposableEffect(level) { onDispose(level.machine::retire) }
    return level
}

@Composable
internal fun MenuLevel.Popup(
    expanded: Boolean,
    anchor: () -> IntRect,
    placement: Placement,
    appearance: PanelAppearance,
    content: @Composable MenuPopupScope.() -> Unit,
) {
    if (!expanded) return
    val gap = with(LocalDensity.current) { placement.gap.roundToPx() }
    val margin = with(LocalDensity.current) { placement.margin.roundToPx() }
    val root = parent == null
    val dismiss = { machine.send(MenuInput.Dismiss) }
    Overlay(modal = false) {
        if (root) OverlayBack(onBack = dismiss)
        val origin = LocalOverlayOrigin.current
        Layout(
            content = {
                if (root) Box(Modifier.pointerInput(Unit) { detectTapGestures { dismiss() } })
                Box(Modifier.pointerInput(Unit) { detectTapGestures { } }.onKeyEvent(::onKey), propagateMinConstraints = true) {
                    Panel(appearance) { MenuPopupScope(this@Popup, appearance).content() }
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val canvas = IntSize(constraints.maxWidth, constraints.maxHeight)
            val scrim = if (root) measurables.first().measure(Constraints.fixed(canvas.width, canvas.height)) else null
            val panel = measurables.last().measure(constraints.copy(minWidth = 0, minHeight = 0))
            val target = anchor().translate(-origin.x.roundToInt(), -origin.y.roundToInt())
            val position = place(target, IntSize(panel.width, panel.height), canvas, placement.side, placement.align, gap, layoutDirection, margin)
            layout(canvas.width, canvas.height) {
                scrim?.place(0, 0)
                panel.place(position)
            }
        }
    }
}

@Composable
private fun MenuLevel.Panel(appearance: PanelAppearance, content: @Composable () -> Unit) {
    val state by remember(this) { derivedStateOf { DisclosureState(machine.state.mounted, machine.state.lastSequence) } }
    appearance.Content(DisclosureProperties(true), state, LocalThemeSnapshot.current, rememberFeedback(pressed = false, focusVisible = false), PanelSlots(content))
    DisposableEffect(machine) {
        machine.send(MenuInput.Mounted)
        onDispose { machine.send(MenuInput.Unmounted) }
    }
}

@Composable
internal fun MenuLevel.Item(
    key: String,
    modifier: Modifier,
    enabled: Boolean,
    typeahead: String?,
    appearance: ButtonAppearance,
    scope: MenuItemScope,
    onActivate: (() -> Unit)?,
    content: @Composable MenuItemScope.() -> Unit,
) {
    val latest by rememberUpdatedState(onActivate)
    DisposableEffect(this, key) {
        val choice = { latest?.invoke(); Unit }
        choices[key] = choice
        entries.put(key, enabled, typeahead)
        publish()
        onDispose {
            if (choices[key] === choice) choices.remove(key)
            entries.remove(key)
            publish()
        }
    }
    SideEffect {
        entries.put(key, enabled, typeahead)
        publish()
    }
    PressPart(
        modifier
            .part(machine, PartKey(key))
            .onPlaced {
                entries.place(key, it)
                publish()
            }
            .onFocusChanged { if (it.isFocused) machine.send(MenuInput.Focused(key)) }
            .focusProperties { if (machine.state.roving.active != key) canFocus = false }
            .then(PointerEnterElement { machine.send(MenuInput.Hover(key)) }),
        enabled,
        appearance,
        { scope.content() },
    ) { machine.send(MenuInput.Choose(key, machine.nextSequence())) }
}

private class PointerEnterNode(var onEnter: () -> Unit) : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass == PointerEventPass.Main && pointerEvent.type == PointerEventType.Enter) onEnter()
    }

    override fun onCancelPointerInput() = Unit
}

private class PointerEnterElement(val onEnter: () -> Unit) : ModifierNodeElement<PointerEnterNode>() {
    override fun create() = PointerEnterNode(onEnter)

    override fun update(node: PointerEnterNode) {
        node.onEnter = onEnter
    }

    override fun equals(other: Any?): Boolean = other is PointerEnterElement && other.onEnter === onEnter

    override fun hashCode(): Int = onEnter.hashCode()
}
