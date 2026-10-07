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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
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
    val coordinator = parent?.owner ?: focus
    val host = remember(coordinator, parent) { PaneFocusHost(parent) }
    DisposableEffect(coordinator, host) {
        coordinator.add(host)
        onDispose { coordinator.remove(host) }
    }
    val scale = LocalDensity.current.fontScale
    val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
    BoxWithConstraints(modifier.onPlaced { host.coordinates = it }.onPreviewKeyEvent { event ->
        val stroke = event.stroke()
        event.type == KeyEventType.KeyDown && stroke != null && stroke.key == KeyName.F6 && !stroke.meta && !stroke.control && !stroke.alt && coordinator.cycle(stroke.shift)
    }) {
        val plan = spec.plan(maxWidth.value, maxHeight.value, scale, preferences)
        val shown = spec.regions.filter { it.id in plan.sizes }
        val starts = shown.filter { it.edge == RegionEdge.Start }
        val ends = shown.filter { it.edge == RegionEdge.End }
        val bottoms = shown.filter { it.edge == RegionEdge.Bottom }
        val roomAcross = plan.mainWidth - spec.mainMinWidth * scale
        val roomDown = plan.mainHeight - spec.mainMinHeight * scale
        val order = (starts + listOf(null) + bottoms + ends).map { it?.id }
        SideEffect {
            host.order = order
            host.rightToLeft = rightToLeft
        }
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
                    item.label?.let { label -> splitterModifier(item).semantics { contentDescription = label } } ?: splitterModifier(item),
                ) { change ->
                    changed(if (change.collapsed) latest.copy(hidden = latest.hidden + item.id)
                        else latest.copy(sizes = latest.sizes + (item.id to change.size), hidden = latest.hidden - item.id))
                }
            }
        }
        val pane = @Composable { item: Region?, content: @Composable PaneScope.() -> Unit ->
            val group = remember(host) { PaneFocusGroup(coordinator) }
            DisposableEffect(host, group) {
                host.groups[item?.id] = group
                onDispose { if (host.groups[item?.id] === group) host.groups.remove(item?.id) }
            }
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

@Composable
fun Modifier.paneFocus(focus: PaneHostFocus): Modifier {
    val parent = LocalPaneFocusGroup.current
    val coordinator = parent?.owner ?: focus
    val host = remember(coordinator, parent) { PaneFocusHost(parent) }
    val group = remember(host) { PaneFocusGroup(coordinator) }
    val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
    DisposableEffect(coordinator, host, group) {
        host.groups[null] = group
        host.order = listOf(null)
        coordinator.add(host)
        onDispose { coordinator.remove(host) }
    }
    SideEffect { host.rightToLeft = rightToLeft }
    return this.then(group.modifier()).onPlaced { host.coordinates = it }.onPreviewKeyEvent { event ->
        val stroke = event.stroke()
        event.type == KeyEventType.KeyDown && stroke != null && stroke.key == KeyName.F6 && !stroke.meta && !stroke.control && !stroke.alt && coordinator.cycle(stroke.shift)
    }
}

internal class PaneFocusGroup(val owner: PaneHostFocus) {
    private val requester = FocusRequester()
    private var focused = false

    fun modifier(): Modifier = Modifier.focusRequester(requester)
        .onFocusChanged { focused = it.hasFocus }
        .focusProperties { onExit = { requester.saveFocusedChild() } }.focusGroup()

    fun hasFocus(): Boolean = focused
    fun request(): Boolean = requester.restoreFocusedChild() || requester.requestFocus()
}

internal class PaneFocusHost(val parent: PaneFocusGroup?) {
    val groups = mutableMapOf<String?, PaneFocusGroup>()
    var order: List<String?> = emptyList()
    var rightToLeft = false
    var coordinates: LayoutCoordinates? = null

    fun entries(): List<PaneFocusGroup> = order.mapNotNull(groups::get)
    fun bounds(): Rect? = coordinates?.takeIf { it.isAttached }?.boundsInWindow()
}

@Stable
class PaneHostFocus {
    private val hosts = mutableListOf<PaneFocusHost>()

    internal fun add(host: PaneFocusHost) {
        hosts += host
    }

    internal fun remove(host: PaneFocusHost) {
        hosts -= host
    }

    fun cycle(backward: Boolean = false): Boolean {
        val groups = leaves(null)
        if (groups.isEmpty()) return false
        val step = if (backward) -1 else 1
        val focused = groups.indexOfFirst { it.hasFocus() }
        val start = if (focused < 0) (if (step > 0) -1 else 0) else focused
        for (offset in 1..groups.size) {
            if (groups[(start + step * offset).mod(groups.size)].request()) return true
        }
        return true
    }

    private fun leaves(parent: PaneFocusGroup?): List<PaneFocusGroup> {
        val siblings = hosts.filter { it.parent === parent }
        val placed = siblings.mapNotNull { host -> host.bounds()?.let { host to it } }
        val ordered = readingOrder(placed, siblings.any { it.rightToLeft }) + siblings.filter { it.bounds() == null }
        return ordered.flatMap { it.entries() }.flatMap { group -> leaves(group).ifEmpty { listOf(group) } }
    }
}
