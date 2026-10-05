package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.Axis
import dev.shibasis.reaktor.surface.KeyName
import dev.shibasis.reaktor.surface.KeyStroke
import dev.shibasis.reaktor.surface.PanePreferences
import dev.shibasis.reaktor.surface.PaneSpec
import dev.shibasis.reaktor.surface.Region
import dev.shibasis.reaktor.surface.RegionEdge
import dev.shibasis.reaktor.surface.SplitterBehavior
import dev.shibasis.reaktor.surface.SplitterKernel
import dev.shibasis.reaktor.surface.SplitterProperties
import dev.shibasis.reaktor.surface.plan

@Stable
class PaneScope internal constructor(val width: Dp, val height: Dp, val collapsed: Set<String>)

@Composable
fun PaneHost(
    spec: PaneSpec,
    preferences: PanePreferences,
    onPreferencesChange: (PanePreferences) -> Unit,
    modifier: Modifier = Modifier,
    behavior: SplitterBehavior = SplitterKernel(),
    appearance: SplitterAppearance = LocalAppearances.current[Appearance.Splitter],
    focus: PaneHostFocus = remember { PaneHostFocus() },
    splitterModifier: (Region) -> Modifier = { Modifier },
    main: @Composable PaneScope.() -> Unit,
    region: @Composable PaneScope.(Region) -> Unit,
) {
    val latest by rememberUpdatedState(preferences)
    val changed by rememberUpdatedState(onPreferencesChange)
    val parent = LocalPaneFocusGroup.current
    val groups = parent?.owner ?: focus
    val host = remember { Any() }
    DisposableEffect(groups, host) { onDispose { groups.remove(host) } }
    val scale = LocalDensity.current.fontScale
    val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
    BoxWithConstraints(modifier.onPreviewKeyEvent { event ->
        val stroke = event.stroke()
        event.type == KeyEventType.KeyDown && stroke != null && stroke.key == KeyName.F6 && !stroke.meta && !stroke.control && !stroke.alt && groups.cycle(stroke.shift)
    }) {
        val plan = spec.plan(maxWidth.value, maxHeight.value, scale, preferences)
        val shown = spec.regions.filter { it.id in plan.sizes }
        val starts = shown.filter { it.edge == RegionEdge.Start }
        val ends = shown.filter { it.edge == RegionEdge.End }
        val bottoms = shown.filter { it.edge == RegionEdge.Bottom }
        val roomAcross = plan.mainWidth - spec.mainMinWidth * scale
        val roomDown = plan.mainHeight - spec.mainMinHeight * scale
        val order = starts + listOf(null) + bottoms + ends
        val focusGroups = groups.register(host, parent, order.map { it?.id })
        val handle = @Composable { item: Region ->
            if (item.min < item.max) {
            val size = plan.sizes.getValue(item.id)
            val bottom = item.edge == RegionEdge.Bottom
            SplitterHandle(
                SplitterProperties(
                    size = size,
                    min = item.min * scale,
                    max = minOf(item.max, size + if (bottom) roomDown else roomAcross),
                    initial = item.preferred,
                    collapsible = item.collapsible,
                    reversed = item.edge != RegionEdge.Start,
                    axis = if (bottom) Axis.Vertical else Axis.Horizontal,
                    rightToLeft = rightToLeft,
                ),
                if (bottom) SplitAxis.Vertical else SplitAxis.Horizontal,
                behavior,
                appearance,
                "$SplitterPart/${item.id}",
                item.label,
                splitterModifier(item),
            ) { change ->
                changed(if (change.collapsed) latest.copy(hidden = latest.hidden + item.id)
                    else latest.copy(sizes = latest.sizes + (item.id to change.size), hidden = latest.hidden - item.id))
            }
            }
        }
        val pane = @Composable { item: Region?, content: @Composable PaneScope.() -> Unit ->
            val group = focusGroups[order.indexOf(item)]
            CompositionLocalProvider(LocalPaneFocusGroup provides group) {
                BoxWithConstraints(group.modifier(), propagateMinConstraints = true) { PaneScope(maxWidth, maxHeight, plan.collapsed).content() }
            }
        }
        Row(Modifier.fillMaxSize()) {
            starts.forEach { item ->
                key(item.id) {
                    Box(Modifier.width(plan.sizes.getValue(item.id).dp).fillMaxHeight(), propagateMinConstraints = true) {
                        pane(item) { region(item) }
                    }
                    handle(item)
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth(), propagateMinConstraints = true) { pane(null, main) }
                bottoms.forEach { item ->
                    key(item.id) {
                        handle(item)
                        Box(Modifier.height(plan.sizes.getValue(item.id).dp).fillMaxWidth(), propagateMinConstraints = true) {
                            pane(item) { region(item) }
                        }
                    }
                }
            }
            ends.forEach { item ->
                key(item.id) {
                    handle(item)
                    Box(Modifier.width(plan.sizes.getValue(item.id).dp).fillMaxHeight(), propagateMinConstraints = true) {
                        pane(item) { region(item) }
                    }
                }
            }
        }
    }
}

private val LocalPaneFocusGroup = staticCompositionLocalOf<PaneFocusGroup?> { null }

internal class PaneFocusGroup(val owner: PaneHostFocus) {
    private val requester = FocusRequester()
    private var focused = false
    var children by mutableStateOf<List<PaneFocusGroup>>(emptyList())

    fun modifier(): Modifier = Modifier.focusRequester(requester)
        .onFocusChanged { focused = it.hasFocus }
        .focusProperties { onExit = { requester.saveFocusedChild() } }.focusGroup()

    fun leaves(): List<PaneFocusGroup> = if (children.isEmpty()) listOf(this) else children.flatMap { it.leaves() }
    fun hasFocus(): Boolean = focused
    fun request(): Boolean = requester.restoreFocusedChild() || requester.requestFocus()
}

@Stable
class PaneHostFocus {
    private class Host(val parent: PaneFocusGroup?, val groups: MutableMap<String?, PaneFocusGroup> = linkedMapOf()) {
        var order: List<String?> = emptyList()
        fun entries() = order.mapNotNull(groups::get)
    }
    private val hosts = linkedMapOf<Any, Host>()

    internal fun register(key: Any, parent: PaneFocusGroup?, order: List<String?>): List<PaneFocusGroup> {
        val host = hosts.getOrPut(key) { Host(parent) }
        host.order = order
        order.forEach { host.groups.getOrPut(it) { PaneFocusGroup(this) } }
        parent?.children = hosts.values.filter { it.parent === parent }.flatMap { it.entries() }
        return host.entries()
    }

    internal fun remove(key: Any) {
        val host = hosts.remove(key) ?: return
        host.parent?.let { parent -> parent.children = hosts.values.filter { it.parent === parent }.flatMap { it.entries() } }
    }

    fun cycle(backward: Boolean = false): Boolean {
        val groups = hosts.values.filter { it.parent == null }.flatMap { it.entries() }.flatMap { it.leaves() }
        if (groups.isEmpty()) return false
        val step = if (backward) -1 else 1
        val focused = groups.indexOfFirst { it.hasFocus() }
        val start = if (focused < 0) (if (step > 0) -1 else 0) else focused
        for (offset in 1..groups.size) {
            if (groups[(start + step * offset).mod(groups.size)].request()) return true
        }
        return true
    }
}
