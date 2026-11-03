package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.Axis
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.TreemapValue
import dev.shibasis.reaktor.surface.treemap

@Composable
fun Treemap(
    values: List<TreemapValue>,
    selection: String?,
    onSelectionChange: (String) -> Unit,
    colors: (String) -> Color,
    modifier: Modifier = Modifier,
    id: String = "treemap",
    description: (String) -> String = { it },
    label: @Composable (String) -> Unit,
) {
    val roving = rememberRoving(Axis.Horizontal, onSelectionChange)
    SideEffect { roving.prefer(selection) }
    BoxWithConstraints(modifier.roving(roving).testTag(id)) {
        val cells = remember(values, maxWidth, maxHeight) { treemap(values, maxWidth.value.toDouble(), maxHeight.value.toDouble()) }
        cells.filter { it.width > 0 && it.height > 0 }.forEach { cell ->
            val chosen = cell.key == selection
            val color = colors(cell.key)
            val appearance = remember(color, chosen) { object : ButtonAppearance {
                @Composable
                override fun Content(properties: PressProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ButtonSlots) {
                    Box(Modifier.fillMaxSize().background(color).border(if (chosen || state.focusVisible) 2.dp else .5.dp, color.copy(alpha = if (chosen || state.focusVisible) 1f else .35f)).padding(3.dp)) { slots.content() }
                }
            } }
            Tooltip(tip = { Text(description(cell.key)) }) {
                Button({ roving.point(cell.key, true); onSelectionChange(cell.key) },
                    Modifier.offset(cell.x.dp, cell.y.dp).size(cell.width.dp, cell.height.dp)
                        .testTag("$id-cell-${cell.key}").rovingItem(roving, cell.key, true, description(cell.key))
                        .semantics { selected = chosen; contentDescription = description(cell.key) }, appearance = appearance) {
                    if (cell.width >= 72 && cell.height >= 28) label(cell.key)
                }
            }
        }
    }
}
