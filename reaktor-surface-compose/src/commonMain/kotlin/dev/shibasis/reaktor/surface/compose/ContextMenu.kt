package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusTargetModifierNode
import androidx.compose.ui.focus.Focusability
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.getFocusedRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.unit.toSize
import dev.shibasis.reaktor.surface.DisclosureKernel
import dev.shibasis.reaktor.surface.Edge
import dev.shibasis.reaktor.surface.KeyName
import dev.shibasis.reaktor.surface.KeyStroke
import dev.shibasis.reaktor.surface.MenuInput
import dev.shibasis.reaktor.surface.MenuKernel

@Composable
fun ContextMenu(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    behavior: MenuBehavior = MenuKernel(),
    content: @Composable ContextMenuScope.() -> Unit,
) {
    val expanded = remember { mutableStateOf(false) }
    val level = rememberMenuLevel(expanded.value, enabled, behavior, null) { expanded.value = it }
    val scope = remember(level) { ContextMenuScope(level, expanded) }
    Box(modifier, propagateMinConstraints = true) { scope.content() }
}

@Stable
class ContextMenuScope internal constructor(private val level: MenuLevel, private val expanded: MutableState<Boolean>) {
    private var anchor by mutableStateOf(IntRect.Zero)
    internal var area: LayoutCoordinates? = null

    @Composable
    fun Area(modifier: Modifier = Modifier, content: @Composable () -> Unit) = Box(
        modifier.part(level.machine, DisclosureKernel.Trigger).focusRestorer().then(ContextAreaElement(this)),
        propagateMinConstraints = true,
    ) { content() }

    @Composable
    fun Popup(
        appearance: PanelAppearance = LocalAppearances.current.menuPanel,
        content: @Composable MenuPopupScope.() -> Unit,
    ) = level.Popup(expanded.value, { anchor }, ContextPlacement, appearance, content)

    fun openAt(point: Offset) {
        val coordinates = area?.takeIf { it.isAttached } ?: return
        open(IntRect(coordinates.localToWindow(point).round(), IntSize.Zero))
    }

    internal fun open(at: IntRect) {
        anchor = at
        level.machine.send(MenuInput.Open(level.machine.nextSequence(), Edge.First))
    }
}

private class ContextAreaNode(var scope: ContextMenuScope) :
    DelegatingNode(), PointerInputModifierNode, KeyInputModifierNode, LayoutAwareModifierNode {
    private val group = delegate(FocusTargetModifierNode(Focusability.Never))

    override fun onPlaced(coordinates: LayoutCoordinates) {
        scope.area = coordinates
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Main || pointerEvent.type != PointerEventType.Press || !pointerEvent.buttons.isSecondaryPressed) return
        pointerEvent.changes.firstOrNull()?.let { scope.openAt(it.position) }
    }

    override fun onCancelPointerInput() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val stroke = event.stroke()
        if (stroke != ShiftF10 && stroke != MenuKey) return false
        val coordinates = scope.area?.takeIf { it.isAttached } ?: return false
        val focused = group.getFocusedRect() ?: Rect(Offset.Zero, coordinates.size.toSize())
        scope.open(focused.translate(coordinates.localToWindow(Offset.Zero)).roundToIntRect())
        return true
    }

    override fun onPreKeyEvent(event: KeyEvent): Boolean = false
}

private class ContextAreaElement(val scope: ContextMenuScope) : ModifierNodeElement<ContextAreaNode>() {
    override fun create() = ContextAreaNode(scope)

    override fun update(node: ContextAreaNode) {
        node.scope = scope
    }

    override fun equals(other: Any?): Boolean = other is ContextAreaElement && other.scope === scope

    override fun hashCode(): Int = scope.hashCode()
}

private val ShiftF10 = KeyStroke(KeyName.F10, shift = true)

private val MenuKey = KeyStroke(KeyName.ContextMenu)

internal val ContextPlacement = Placement(Side.Below, Align.Start, 0.dp)
