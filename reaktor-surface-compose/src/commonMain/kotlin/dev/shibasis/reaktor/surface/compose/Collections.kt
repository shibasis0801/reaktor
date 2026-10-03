package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.roundToIntRect
import dev.shibasis.reaktor.surface.BehaviorKernel
import dev.shibasis.reaktor.surface.CollectionEvent
import dev.shibasis.reaktor.surface.CollectionInput
import dev.shibasis.reaktor.surface.CollectionKernel
import dev.shibasis.reaktor.surface.CollectionProperties
import dev.shibasis.reaktor.surface.CollectionState
import dev.shibasis.reaktor.surface.CommandEntry
import dev.shibasis.reaktor.surface.CommandId
import dev.shibasis.reaktor.surface.CommandSet
import dev.shibasis.reaktor.surface.Edge
import dev.shibasis.reaktor.surface.ItemSource
import dev.shibasis.reaktor.surface.KeyConvention
import dev.shibasis.reaktor.surface.LocalCommand
import dev.shibasis.reaktor.surface.MenuInput
import dev.shibasis.reaktor.surface.MenuKernel
import dev.shibasis.reaktor.surface.SelectionMode
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.TreeItems
import dev.shibasis.reaktor.surface.TreeSource

typealias CollectionBehavior = BehaviorKernel<CollectionProperties, CollectionState, CollectionInput, CollectionEvent>

data class RowProperties(
    val selected: Boolean,
    val enabled: Boolean,
    val index: Int,
    val depth: Int,
    val expandable: Boolean,
    val expanded: Boolean,
)

data class RowState(val active: Boolean, val hovered: Boolean, val focusVisible: Boolean)

class RowSlots(val content: @Composable () -> Unit, val toggle: Modifier?)

typealias RowAppearance = ComposeAppearance<RowProperties, RowState, RowSlots>

@Stable
class ItemScope internal constructor(val key: String, val index: Int, private val flags: RowFlags, private val host: CollectionHost) {
    val selected: Boolean get() = flags.selected
    val active: Boolean get() = flags.active

    @Composable
    fun MenuTrigger(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
        Box(modifier.then(MenuTriggerElement(host, key)), propagateMinConstraints = true) { content() }
}

class RowActions(
    val commands: (Set<String>) -> CommandSet,
    val onInvoke: (CommandId, Set<String>) -> Unit,
) {
    companion object {
        val None = RowActions({ NoCommands }, { _, _ -> })
    }
}

@Composable
fun <T> ListBox(
    source: ItemSource<T>,
    selection: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    mode: SelectionMode = SelectionMode.Single,
    onActivate: (String) -> Unit = {},
    actions: RowActions = RowActions.None,
    state: LazyListState = rememberLazyListState(),
    behavior: CollectionBehavior = CollectionKernel(),
    appearance: RowAppearance = LocalAppearances.current[Appearance.Row],
    row: @Composable ItemScope.(T) -> Unit,
) = Collection(source, selection, onSelectionChange, { _, _ -> }, modifier, mode, onActivate, actions, state, behavior, appearance, true, row)

@Composable
fun <T> Tree(
    source: TreeSource<T>,
    selection: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onExpandedChange: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    mode: SelectionMode = SelectionMode.Single,
    onActivate: (String) -> Unit = {},
    actions: RowActions = RowActions.None,
    state: LazyListState = rememberLazyListState(),
    behavior: CollectionBehavior = CollectionKernel(),
    appearance: RowAppearance = LocalAppearances.current[Appearance.Row],
    row: @Composable ItemScope.(T) -> Unit,
) = Collection(source, selection, onSelectionChange, onExpandedChange, modifier, mode, onActivate, actions, state, behavior, appearance, true, row)

@Composable
internal fun <T> Collection(
    source: ItemSource<T>,
    selection: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onExpandedChange: (String, Boolean) -> Unit,
    modifier: Modifier,
    mode: SelectionMode,
    onActivate: (String) -> Unit,
    actions: RowActions,
    state: LazyListState,
    behavior: CollectionBehavior,
    appearance: RowAppearance,
    scrollbar: Boolean,
    row: @Composable ItemScope.(T) -> Unit,
) {
    val properties = CollectionProperties(source, selection, LocalSurfaceEnvironment.current.keys, mode, LocalLayoutDirection.current == LayoutDirection.Rtl)
    val selected = rememberUpdatedState(selection)
    val host = remember { CollectionHost(selected) }
    val changed by rememberUpdatedState(onSelectionChange)
    val expanded by rememberUpdatedState(onExpandedChange)
    val activated by rememberUpdatedState(onActivate)
    host.machine = rememberMachine(behavior, properties, host::execute) { event ->
        when (event) {
            is CollectionEvent.SelectionChange -> changed(event.selection)
            is CollectionEvent.Activate -> activated(event.key)
            is CollectionEvent.ExpansionChange -> expanded(event.key, event.expanded)
            is CollectionEvent.MenuRequest -> host.menu(event)
        }
    }
    host.list = state
    val inputModes = LocalInputModeManager.current
    SideEffect { host.update(properties, behavior, inputModes) }
    val menus = actions !== RowActions.None
    val commands = if (menus) actions.commands(selection) else NoCommands
    val invoke: (CommandId) -> Unit = { id -> actions.onInvoke(id, selected.value) }
    if (menus) RowMenu(host, commands, invoke)
    Box(
        modifier
            .semantics {
                collectionInfo = CollectionInfo(source.size, 1)
                selectableGroup()
            }
            .then(if (menus) Modifier.commands(commands, invoke) else Modifier)
            .onPreviewKeyEvent(host::onKey)
            .onFocusChanged(host::onFocus)
            .focusProperties { canFocus = host.waiting() }
            .focusTarget(),
    ) {
        LazyColumn(Modifier.fillMaxSize().then(CollectionPointerElement(host)), state = state) {
            items(source.size, key = source::key, contentType = { RowContent }) { index -> CollectionRow(host, source, index, appearance, row) }
        }
        if (scrollbar) CollectionScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@Composable
private fun RowMenu(host: CollectionHost, commands: CommandSet, invoke: (CommandId) -> Unit) {
    val open = remember { mutableStateOf(false) }
    val level = rememberMenuLevel(open.value, true, RowMenuKernel, null) { expanded ->
        open.value = expanded
        if (!expanded) host.returnFocus()
    }
    DisposableEffect(host, level) {
        host.openMenu = { anchor ->
            host.menuAnchor = anchor
            level.machine.send(MenuInput.Open(level.machine.nextSequence(), Edge.First))
        }
        onDispose { host.openMenu = null }
    }
    val empty = commands.commands.isEmpty()
    SideEffect { if (open.value && empty) level.machine.send(MenuInput.Dismiss) }
    val entries = commands.menus.firstOrNull()?.entries ?: commands.commands.map { CommandEntry.Item(it.id) }
    val scope = LocalAutomationScope.current
    level.Popup(open.value && !empty, { host.menuAnchor }, ContextPlacement, LocalAppearances.current.menuPanel) {
        if (scope == null) Commands(commands, entries, invoke) else AutomationScope(MenuPart) { Commands(commands, entries, invoke) }
    }
}

@Composable
private fun <T> CollectionRow(host: CollectionHost, source: ItemSource<T>, index: Int, appearance: RowAppearance, row: @Composable ItemScope.(T) -> Unit) {
    val key = source.key(index)
    val flags = remember(host, key) { RowFlags(host, key) }
    val focus = remember { FocusRequester() }
    DisposableEffect(host, key) {
        host.register(key, focus)
        onDispose { host.unregister(key, focus) }
    }
    val tree = source as? TreeItems
    val enabled = source.enabled(index)
    val expandable = tree?.expandable(index) == true
    val expanded = tree?.expanded(index) == true
    val selected = flags.selected
    val state = RowState(flags.active, flags.hovered, flags.focusVisible)
    val scope = remember(flags, index) { ItemScope(key, index, flags, host) }
    val item = source[index]
    val automation = LocalAutomationScope.current
    Box(
        Modifier
            .then(if (automation == null) Modifier else Modifier.testId(automationId(automation, "row/$key")))
            .focusRequester(focus)
            .onFocusChanged { host.onRowFocus(key, it.isFocused) }
            .focusProperties { canFocus = host.canFocus(key) }
            .focusable()
            .semantics(mergeDescendants = true) {
                this.selected = selected
                collectionItemInfo = CollectionItemInfo(index, 1, 0, 1)
                if (enabled) {
                    onClick { host.select(key); true }
                    customActions = listOf(CustomAccessibilityAction(OpenLabel) { host.open(key); true })
                } else {
                    disabled()
                }
                if (expandable) {
                    if (expanded) collapse { host.expand(key, false); true } else expand { host.expand(key, true); true }
                }
            },
        propagateMinConstraints = true,
    ) {
        appearance.Content(
            RowProperties(selected, enabled, index, tree?.depth(index) ?: 0, expandable, expanded),
            state,
            LocalThemeSnapshot.current,
            rememberFeedback(pressed = false, focusVisible = state.focusVisible),
            RowSlots({ Unenterable { scope.row(item) } }, if (tree == null) null else host.toggle(key, expandable, expanded)),
        )
    }
}

@Composable
private fun Unenterable(content: @Composable () -> Unit) =
    Box(Modifier.focusProperties { onEnter = { cancelFocusChange() } }.focusGroup(), propagateMinConstraints = true) { content() }

@Stable
internal class RowFlags(host: CollectionHost, key: String) {
    private val isSelected = derivedStateOf { key in host.selected }
    private val isActive = derivedStateOf { host.active == key }
    private val isHovered = derivedStateOf { host.hovered == key }
    private val showsFocus = derivedStateOf { host.focused == key && host.keyboard }

    val selected: Boolean get() = isSelected.value
    val active: Boolean get() = isActive.value
    val hovered: Boolean get() = isHovered.value
    val focusVisible: Boolean get() = showsFocus.value
}

@Stable
internal class CollectionHost(private val selection: State<Set<String>>) {
    lateinit var machine: Machine<CollectionProperties, CollectionState, CollectionInput, CollectionEvent>
    lateinit var list: LazyListState
    private var properties: CollectionProperties? = null
    private var behavior: CollectionBehavior = DefaultKernel
    private var inputModes: InputModeManager? = null
    private val rows = HashMap<String, FocusRequester>()
    private var pending: String? = null
    private var pointing = false

    var openMenu: ((IntRect) -> Unit)? = null
    var menuAnchor = IntRect.Zero
    var coordinates: LayoutCoordinates? = null
    private var pointerAnchor = IntRect.Zero
    private var nextAnchor: IntRect? = null

    var focused by mutableStateOf<String?>(null)
        private set
    var keyboard by mutableStateOf(false)
        private set

    val selected: Set<String> get() = selection.value
    val active: String? get() = machine.state.active
    val hovered: String? get() = machine.state.hovered

    fun update(properties: CollectionProperties, behavior: CollectionBehavior, inputModes: InputModeManager) {
        this.properties = properties
        this.behavior = behavior
        this.inputModes = inputModes
        val gone = focused?.takeIf { properties.items.indexOf(it) < 0 } ?: return
        machine.state.active?.takeIf { it != gone }?.let {
            focus(it)
            reveal(it)
        }
    }

    fun execute(command: LocalCommand): Boolean = when (command) {
        is LocalCommand.Focus -> {
            focus(command.part.value)
            true
        }
        is LocalCommand.Reveal -> {
            reveal(command.part.value)
            true
        }
        else -> false
    }

    fun register(key: String, focus: FocusRequester) {
        rows[key] = focus
        if (pending == key) {
            pending = null
            focus.requestFocus()
        }
    }

    fun unregister(key: String, focus: FocusRequester) {
        if (rows[key] === focus) rows.remove(key)
    }

    fun canFocus(key: String): Boolean = machine.state.active == key

    fun waiting(): Boolean = machine.state.active?.let { it !in rows } ?: false

    fun onFocus(state: FocusState) {
        if (!state.isFocused) return
        val active = machine.state.active ?: return
        if (inputModes?.inputMode == InputMode.Keyboard) keyboard = true
        focus(active)
        reveal(active)
    }

    fun onRowFocus(key: String, isFocused: Boolean) {
        if (isFocused) {
            focused = key
            if (!pointing && inputModes?.inputMode == InputMode.Keyboard) keyboard = true
            machine.send(CollectionInput.Focused(key))
        } else if (focused == key) {
            focused = null
        }
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val stroke = event.stroke() ?: return false
        val properties = properties ?: return false
        if (!(behavior as? CollectionKernel ?: DefaultKernel).handles(properties, stroke)) return false
        keyboard = true
        machine.send(CollectionInput.Stroke(stroke, page()))
        return true
    }

    fun press(key: String, modifiers: PointerKeyboardModifiers, clicks: Int) = pointed {
        val primary = if (properties?.keys == KeyConvention.Mac) modifiers.isMetaPressed else modifiers.isCtrlPressed
        machine.send(CollectionInput.Press(key, extend = modifiers.isShiftPressed, toggle = primary, clicks = clicks))
    }

    fun secondary(key: String, at: Offset) {
        pointerAnchor = coordinates?.takeIf { it.isAttached }?.let { IntRect(it.localToWindow(at).round(), IntSize.Zero) } ?: IntRect.Zero
        pointed { machine.send(CollectionInput.Secondary(key, atPointer = true)) }
    }

    fun menuFrom(key: String, anchor: IntRect) {
        nextAnchor = anchor
        pointed { machine.send(CollectionInput.Secondary(key, atPointer = true)) }
        nextAnchor = null
    }

    fun menu(request: CollectionEvent.MenuRequest) {
        val anchor = nextAnchor ?: if (request.atPointer) pointerAnchor else rowAnchor(request.key)
        nextAnchor = null
        openMenu?.invoke(anchor)
    }

    fun returnFocus() {
        machine.state.active?.let(::focus)
    }

    private fun rowAnchor(key: String): IntRect {
        val layout = coordinates?.takeIf { it.isAttached } ?: return IntRect.Zero
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return IntRect(layout.localToWindow(Offset.Zero).round(), IntSize.Zero)
        val origin = layout.localToWindow(Offset(0f, item.offset.toFloat())).round()
        return IntRect(origin, IntSize(layout.size.width, item.size))
    }

    fun select(key: String) = machine.send(CollectionInput.Press(key, extend = false, toggle = false, clicks = 1))

    fun open(key: String) = machine.send(CollectionInput.Press(key, extend = false, toggle = false, clicks = 2))

    fun expand(key: String, expanded: Boolean) = machine.send(CollectionInput.Expand(key, expanded))

    fun hover(key: String?) {
        if (machine.state.hovered != key) machine.send(CollectionInput.Hover(key))
    }

    fun keyAt(position: Offset): String? {
        val y = position.y.toInt()
        return list.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }?.key as? String
    }

    fun toggle(key: String, expandable: Boolean, expanded: Boolean): Modifier =
        Modifier.clearAndSetSemantics {}.then(if (expandable) ToggleElement(this, key, expanded) else Modifier)

    private inline fun pointed(send: () -> Unit) {
        keyboard = false
        pointing = true
        try {
            send()
        } finally {
            pointing = false
        }
    }

    private fun focus(key: String) {
        val requester = rows[key]
        if (requester == null) {
            pending = key
        } else {
            pending = null
            requester.requestFocus()
        }
    }

    private fun reveal(key: String) {
        val index = properties?.items?.indexOf(key)?.takeIf { it >= 0 } ?: return
        val visible = list.layoutInfo.visibleItemsInfo
        if (visible.any { it.index == index }) return
        val first = visible.firstOrNull()?.index ?: 0
        list.requestScrollToItem(if (index < first) index else (index - page() + 1).coerceAtLeast(0))
    }

    private fun page(): Int {
        val info = list.layoutInfo
        return info.visibleItemsInfo.count { it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }.coerceAtLeast(1)
    }
}

private data class CollectionPointerElement(val host: CollectionHost) : ModifierNodeElement<CollectionPointerNode>() {
    override fun create() = CollectionPointerNode(host)

    override fun update(node: CollectionPointerNode) {
        node.host = host
    }
}

private class CollectionPointerNode(var host: CollectionHost) :
    Modifier.Node(), PointerInputModifierNode, CompositionLocalConsumerModifierNode, LayoutAwareModifierNode {
    private var last: String? = null
    private var lastTime = 0L
    private var lastPosition = Offset.Zero
    private var clicks = 0

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Main) return
        val change = pointerEvent.changes.firstOrNull() ?: return
        when (pointerEvent.type) {
            PointerEventType.Enter, PointerEventType.Move -> host.hover(host.keyAt(change.position))
            PointerEventType.Exit -> host.hover(null)
            PointerEventType.Press -> if (!change.isConsumed) press(pointerEvent, change)
        }
    }

    private fun press(event: PointerEvent, change: PointerInputChange) {
        val key = host.keyAt(change.position) ?: return
        if (event.buttons.isSecondaryPressed) {
            host.secondary(key, change.position)
        } else {
            val configuration = currentValueOf(LocalViewConfiguration)
            val again = key == last && change.uptimeMillis - lastTime <= configuration.doubleTapTimeoutMillis &&
                (change.position - lastPosition).getDistance() <= configuration.touchSlop
            clicks = if (again) clicks + 1 else 1
            last = key
            lastTime = change.uptimeMillis
            lastPosition = change.position
            host.press(key, event.keyboardModifiers, clicks)
        }
        change.consume()
    }

    override fun onCancelPointerInput() = Unit

    override fun onPlaced(coordinates: LayoutCoordinates) {
        host.coordinates = coordinates
    }
}

private data class ToggleElement(val host: CollectionHost, val key: String, val expanded: Boolean) : ModifierNodeElement<ToggleNode>() {
    override fun create() = ToggleNode(host, key, expanded)

    override fun update(node: ToggleNode) {
        node.host = host
        node.key = key
        node.expanded = expanded
    }
}

private class ToggleNode(var host: CollectionHost, var key: String, var expanded: Boolean) : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Main || pointerEvent.type != PointerEventType.Press || pointerEvent.buttons.isSecondaryPressed) return
        val change = pointerEvent.changes.firstOrNull()?.takeUnless { it.isConsumed } ?: return
        change.consume()
        host.expand(key, !expanded)
    }

    override fun onCancelPointerInput() = Unit
}

private data class MenuTriggerElement(val host: CollectionHost, val key: String) : ModifierNodeElement<MenuTriggerNode>() {
    override fun create() = MenuTriggerNode(host, key)

    override fun update(node: MenuTriggerNode) {
        node.host = host
        node.key = key
    }
}

private class MenuTriggerNode(var host: CollectionHost, var key: String) :
    Modifier.Node(), PointerInputModifierNode, LayoutAwareModifierNode, SemanticsModifierNode {
    private var placed: LayoutCoordinates? = null

    override val shouldMergeDescendantSemantics: Boolean get() = true

    override fun onPlaced(coordinates: LayoutCoordinates) {
        placed = coordinates
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Main || pointerEvent.type != PointerEventType.Press || pointerEvent.buttons.isSecondaryPressed) return
        val change = pointerEvent.changes.firstOrNull()?.takeUnless { it.isConsumed } ?: return
        change.consume()
        open()
    }

    override fun onCancelPointerInput() = Unit

    override fun SemanticsPropertyReceiver.applySemantics() {
        role = Role.Button
        onClick {
            open()
            true
        }
    }

    private fun open() = host.menuFrom(key, placed?.takeIf { it.isAttached }?.boundsInWindow()?.roundToIntRect() ?: IntRect.Zero)
}

val BareRow: RowAppearance = object : RowAppearance {
    @Composable
    override fun Content(properties: RowProperties, state: RowState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: RowSlots) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 32.dp)
                .drawBehind { if (properties.selected) drawRect(Color.LightGray) }
                .focusFrame(state.focusVisible)
                .padding(start = 16.dp * properties.depth),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            slots.toggle?.let { toggle ->
                Box(toggle.size(24.dp), contentAlignment = Alignment.Center) {
                    if (properties.expandable) BasicText(if (properties.expanded) "▾" else "▸")
                }
            }
            Box(Modifier.weight(1f)) { slots.content() }
        }
    }
}

internal fun Modifier.focusFrame(shown: Boolean): Modifier = drawWithContent {
    drawContent()
    if (shown) drawRect(Color.Gray, style = Stroke(1.dp.toPx()))
}

private val DefaultKernel = CollectionKernel()

private val RowMenuKernel = MenuKernel()

private val NoCommands = CommandSet(emptyList())

private const val MenuPart = "menu"

private object RowContent

private const val OpenLabel = "Open"
