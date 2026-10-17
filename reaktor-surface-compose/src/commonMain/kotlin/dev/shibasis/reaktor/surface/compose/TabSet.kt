package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.PartKey
import dev.shibasis.reaktor.surface.PressInput
import dev.shibasis.reaktor.surface.PressKernel
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.TabSetBehavior
import dev.shibasis.reaktor.surface.TabSetEvent
import dev.shibasis.reaktor.surface.TabSetInput
import dev.shibasis.reaktor.surface.TabSetKernel
import dev.shibasis.reaktor.surface.TabSetProperties
import dev.shibasis.reaktor.surface.TabSetState
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.listSource

@Stable
class TabScope internal constructor(
    val key: String,
    val selected: Boolean,
    private val strip: TabStrip,
    private val close: () -> Unit,
) {
    @Composable
    fun Close(
        modifier: Modifier = Modifier,
        appearance: ButtonAppearance = LocalAppearances.current[Appearance.TabClose],
        content: @Composable () -> Unit,
    ) {
        DisposableEffect(strip, key) {
            strip.closable[key] = Unit
            onDispose { strip.closable.remove(key) }
        }
        val target = TabTarget(key, close = true)
        val source = rememberInteractions(strip, target) { strip.send(target, it) }
        Box(
            modifier.focusProperties { canFocus = false }.press(source, enabled = true, onHold = null, onActivate = close),
            propagateMinConstraints = true,
        ) {
            val state = strip.state(target)
            appearance.Content(Pressable, state, LocalThemeSnapshot.current, rememberFeedback(state.pressed, state.focusVisible), ButtonSlots(content))
        }
    }
}

@Composable
fun TabSet(
    tabs: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (String) -> Unit = {},
    onReselect: (String) -> Unit = {},
    behavior: TabSetBehavior = TabSetKernel(),
    appearance: ItemAppearance = LocalAppearances.current[Appearance.DocumentTab],
    text: (String) -> String = { it },
    tab: @Composable TabScope.(String) -> Unit,
) {
    val strip = remember { TabStrip() }
    val items = remember(tabs, text) { listSource(tabs, { it }, text = text) }
    val properties = TabSetProperties(items, selected, strip.closable.keys.toSet(), LocalLayoutDirection.current == LayoutDirection.Rtl)
    val chosen by rememberUpdatedState(onSelect)
    val closed by rememberUpdatedState(onClose)
    val reselected by rememberUpdatedState(onReselect)
    val machine = rememberMachine(behavior, properties) { event ->
        when (event) {
            is TabSetEvent.Select -> chosen(event.key)
            is TabSetEvent.Reselect -> reselected(event.key)
            is TabSetEvent.CloseRequest -> closed(event.key)
        }
    }
    Row(
        modifier
            .horizontalScroll(rememberScrollState())
            .selectableGroup()
            .onFocusChanged { if (!it.hasFocus) machine.send(TabSetInput.Blurred) }
            .onKeyEvent { event ->
                val stroke = event.stroke()
                val handled = event.type == KeyEventType.KeyDown && stroke != null && (behavior as? TabSetKernel ?: DefaultTabSet).handles(properties, stroke)
                if (handled && stroke != null) machine.send(TabSetInput.Stroke(stroke))
                handled
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { item ->
            key(item) { TabPart(item, selected == item, machine, strip, appearance, tab) }
        }
    }
}

@Composable
private fun TabPart(
    item: String,
    selected: Boolean,
    machine: Machine<TabSetProperties, TabSetState, TabSetInput, TabSetEvent>,
    strip: TabStrip,
    appearance: ItemAppearance,
    tab: @Composable TabScope.(String) -> Unit,
) {
    val target = TabTarget(item, close = false)
    val source = rememberInteractions(strip, target) { strip.send(target, it) }
    val scope = remember(item, selected, machine, strip) { TabScope(item, selected, strip) { machine.send(TabSetInput.Close(item)) } }
    val canClose = item in strip.closable
    Box(
        Modifier
            .part(machine, PartKey(item))
            .onFocusChanged { if (it.isFocused) machine.send(TabSetInput.Focused(item)) }
            .focusProperties { canFocus = machine.state.active == item }
            .selectable(selected, source, indication = null, role = Role.Tab) { machine.send(TabSetInput.Point(item)) }
            .semantics { if (canClose) customActions = listOf(CustomAccessibilityAction(CloseLabel) { machine.send(TabSetInput.Close(item)); true }) },
        propagateMinConstraints = true,
    ) {
        val state = strip.state(target)
        appearance.Content(ItemProperties(selected, enabled = true), state, LocalThemeSnapshot.current, rememberFeedback(state.pressed, state.focusVisible), ItemSlots(null) { scope.tab(item) })
    }
}

internal data class TabTarget(val key: String, val close: Boolean)

@Stable
internal class TabStrip {
    val closable = mutableStateMapOf<String, Unit>()
    private val presses = mutableStateMapOf<TabTarget, PressState>()

    fun send(target: TabTarget, input: PressInput) {
        val next = PressKernel.reduce(Pressable, presses[target] ?: Resting, input).state
        if (next == Resting) presses.remove(target) else presses[target] = next
    }

    @Composable
    fun state(target: TabTarget): PressState {
        val state by remember(this, target) { derivedStateOf { presses[target] ?: Resting } }
        return state
    }
}

val BareTabClose: ButtonAppearance = object : ButtonAppearance {
    @Composable
    override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ButtonSlots) {
        Box(Modifier.defaultMinSize(24.dp, 24.dp), contentAlignment = Alignment.Center) { slots.content() }
    }
}

private val DefaultTabSet = TabSetKernel()

private val Pressable = PressProperties(enabled = true)

private val Resting = PressState()

private const val CloseLabel = "Close"
