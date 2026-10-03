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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
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
    main: @Composable PaneScope.() -> Unit,
    region: @Composable PaneScope.(Region) -> Unit,
) {
    val latest by rememberUpdatedState(preferences)
    val changed by rememberUpdatedState(onPreferencesChange)
    val groups = remember { PaneGroups() }
    val scale = LocalDensity.current.fontScale
    val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
    BoxWithConstraints(modifier.onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && groups.cycle(event.stroke()) }) {
        val plan = spec.plan(maxWidth.value, maxHeight.value, scale, preferences)
        val shown = spec.regions.filter { it.id in plan.sizes }
        val starts = shown.filter { it.edge == RegionEdge.Start }
        val ends = shown.filter { it.edge == RegionEdge.End }
        val bottoms = shown.filter { it.edge == RegionEdge.Bottom }
        val roomAcross = plan.mainWidth - spec.mainMinWidth * scale
        val roomDown = plan.mainHeight - spec.mainMinHeight * scale
        val mainWidth = (plan.mainWidth.dp - HandleSize * (starts.size + ends.size)).coerceAtLeast(0.dp)
        val mainHeight = (plan.mainHeight.dp - HandleSize * bottoms.size).coerceAtLeast(0.dp)
        val order = starts + listOf(null) + bottoms + ends
        val fullHeight = maxHeight
        val handle = @Composable { item: Region ->
            val size = plan.sizes.getValue(item.id)
            val bottom = item.edge == RegionEdge.Bottom
            SplitterHandle(
                SplitterProperties(
                    size = size,
                    min = item.min * scale,
                    max = minOf(item.max, size + if (bottom) roomDown else roomAcross),
                    initial = item.preferred,
                    reversed = item.edge != RegionEdge.Start,
                    axis = if (bottom) Axis.Vertical else Axis.Horizontal,
                    rightToLeft = rightToLeft,
                ),
                if (bottom) SplitAxis.Vertical else SplitAxis.Horizontal,
                behavior,
                appearance,
                "$SplitterPart/${item.id}",
            ) { change -> changed(latest.copy(sizes = latest.sizes + (item.id to change.size))) }
        }
        val pane = @Composable { item: Region?, width: Dp, height: Dp, content: @Composable PaneScope.() -> Unit ->
            val scope = PaneScope(width, height, plan.collapsed)
            Box(groups.group(order.indexOf(item)), propagateMinConstraints = true) { scope.content() }
        }
        Row(Modifier.fillMaxSize()) {
            starts.forEach { item ->
                key(item.id) {
                    Box(Modifier.width(plan.sizes.getValue(item.id).dp).fillMaxHeight(), propagateMinConstraints = true) {
                        pane(item, plan.sizes.getValue(item.id).dp, fullHeight) { region(item) }
                    }
                    handle(item)
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth(), propagateMinConstraints = true) { pane(null, mainWidth, mainHeight, main) }
                bottoms.forEach { item ->
                    key(item.id) {
                        handle(item)
                        Box(Modifier.height(plan.sizes.getValue(item.id).dp).fillMaxWidth(), propagateMinConstraints = true) {
                            pane(item, mainWidth, plan.sizes.getValue(item.id).dp) { region(item) }
                        }
                    }
                }
            }
            ends.forEach { item ->
                key(item.id) {
                    handle(item)
                    Box(Modifier.width(plan.sizes.getValue(item.id).dp).fillMaxHeight(), propagateMinConstraints = true) {
                        pane(item, plan.sizes.getValue(item.id).dp, fullHeight) { region(item) }
                    }
                }
            }
        }
        groups.size = order.size
    }
}

@Stable
private class PaneGroups {
    private val requesters = mutableListOf<FocusRequester>()
    private var focused = -1
    var size = 0

    fun group(index: Int): Modifier {
        while (requesters.size <= index) requesters += FocusRequester()
        return Modifier
            .focusRequester(requesters[index])
            .onFocusChanged { if (it.hasFocus) focused = index else if (focused == index) focused = -1 }
            .focusRestorer()
            .focusGroup()
    }

    fun cycle(stroke: KeyStroke?): Boolean {
        if (stroke == null || stroke.key != KeyName.F6 || stroke.meta || stroke.control || stroke.alt || size == 0) return false
        val step = if (stroke.shift) -1 else 1
        val start = if (focused < 0) (if (step > 0) -1 else 0) else focused
        for (offset in 1..size) {
            val next = (start + step * offset).mod(size)
            if (requesters[next].requestFocus()) return true
        }
        return true
    }
}
