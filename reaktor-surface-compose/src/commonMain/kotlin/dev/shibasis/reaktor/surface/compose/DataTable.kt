package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.Activated
import dev.shibasis.reaktor.surface.Axis
import dev.shibasis.reaktor.surface.CollectionKernel
import dev.shibasis.reaktor.surface.ItemSource
import dev.shibasis.reaktor.surface.KeyName
import dev.shibasis.reaktor.surface.PressInput
import dev.shibasis.reaktor.surface.PressKernel
import dev.shibasis.reaktor.surface.PressProperties
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.SelectionMode
import dev.shibasis.reaktor.surface.TableLayout
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.fitSize
import dev.shibasis.reaktor.surface.next
import kotlin.math.roundToInt

sealed interface ColumnWidth {
    data class Fixed(val width: Dp) : ColumnWidth
    data class Share(val weight: Float) : ColumnWidth
}

class TableColumn<T>(
    val key: String,
    val width: ColumnWidth,
    val sortable: Boolean = false,
    val header: @Composable () -> Unit,
    val cell: @Composable ItemScope.(T) -> Unit,
)

@Stable
class TableState(
    layout: TableLayout = TableLayout(),
    val list: LazyListState = LazyListState(),
    val horizontal: ScrollState = ScrollState(0),
) {
    var layout: TableLayout by mutableStateOf(layout)
}

@Composable
fun rememberTableState(layout: TableLayout = TableLayout()): TableState = remember { TableState(layout) }

data class HeaderProperties(val sortable: Boolean, val descending: Boolean?)

class HeaderSlots(val content: @Composable () -> Unit)

typealias TableHeaderAppearance = ComposeAppearance<HeaderProperties, PressState, HeaderSlots>

@Composable
fun <T> DataTable(
    source: ItemSource<T>,
    columns: List<TableColumn<T>>,
    selection: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    mode: SelectionMode = SelectionMode.Single,
    onActivate: (String) -> Unit = {},
    actions: RowActions = RowActions.None,
    state: TableState = rememberTableState(),
    busy: Boolean = false,
    behavior: CollectionBehavior = CollectionKernel(),
    appearance: RowAppearance = LocalAppearances.current[Appearance.TableRow],
    empty: @Composable () -> Unit = {},
) {
    val header = LocalAppearances.current[Appearance.TableHeader]
    val step = with(LocalDensity.current) { ColumnStep.toPx() }
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    BoxWithConstraints(modifier.busy(busy).onKeyEvent { state.scrollColumns(it, if (mirrored) -step else step) }) {
        val widths = state.layout.widths
        val tableWidth = maxOf(maxWidth, columns.narrowest(widths))
        Column(Modifier.fillMaxSize().horizontalScroll(state.horizontal)) {
            HeaderRow(columns, state, Modifier.width(tableWidth), header)
            if (busy) Progress(null, Modifier.width(tableWidth))
            val body = Modifier.width(tableWidth).weight(1f)
            if (source.size == 0) {
                Box(body) { empty() }
            } else {
                val cells = remember(columns, state) { CellsPolicy(columns) { state.layout.widths } }
                Collection(source, selection, onSelectionChange, { _, _ -> }, body, mode, onActivate, actions, state.list, behavior, appearance, false) { item ->
                    val scope = this
                    Layout(columns.map { column -> @Composable { column.cell(scope, item) } }, measurePolicy = cells)
                }
            }
        }
        CollectionScrollbar(state.list, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

private fun TableState.scrollColumns(event: KeyEvent, step: Float): Boolean {
    val stroke = event.stroke() ?: return false
    if (event.type != KeyEventType.KeyDown || stroke.meta || stroke.control || stroke.alt || stroke.shift) return false
    val delta = when (stroke.key) {
        KeyName.Right -> step
        KeyName.Left -> -step
        else -> return false
    }
    return horizontal.dispatchRawDelta(delta) != 0f
}

private fun <T> List<TableColumn<T>>.narrowest(widths: Map<String, Float>): Dp = fold(0.dp) { total, column ->
    total + (widths[column.key]?.dp ?: (column.width as? ColumnWidth.Fixed)?.width ?: MinColumn)
}

@Composable
private fun <T> HeaderRow(columns: List<TableColumn<T>>, state: TableState, modifier: Modifier, appearance: TableHeaderAppearance) {
    val roving = rememberRoving(Axis.Horizontal) {}
    Layout(
        content = { columns.forEach { column -> key(column.key) { HeaderCell(column, state, roving, appearance) } } },
        modifier = modifier.roving(roving),
    ) { measurables, constraints ->
        val spans = columnSpans(columns, state.layout.widths, constraints.maxWidth, this)
        val placeables = measurables.mapIndexed { index, measurable -> measurable.measure(Constraints(spans[index], spans[index], 0, constraints.maxHeight)) }
        val height = constraints.constrainHeight(placeables.maxOfOrNull { it.height } ?: 0)
        layout(constraints.constrainWidth(spans.sum()), height) {
            var x = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(x, (height - placeable.height) / 2)
                x += placeable.width
            }
        }
    }
}

private class CellsPolicy<T>(private val columns: List<TableColumn<T>>, private val widths: () -> Map<String, Float>) : MultiContentMeasurePolicy {
    override fun MeasureScope.measure(measurables: List<List<Measurable>>, constraints: Constraints): MeasureResult {
        val spans = columnSpans(columns, widths(), if (constraints.hasBoundedWidth) constraints.maxWidth else 0, this)
        val placeables = measurables.mapIndexed { index, cell -> cell.map { it.measure(Constraints(0, spans[index], 0, constraints.maxHeight)) } }
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else placeables.maxOf { cell -> cell.maxOfOrNull { it.height } ?: 0 }.coerceAtLeast(constraints.minHeight)
        return layout(constraints.constrainWidth(spans.sum()), height) {
            var x = 0
            placeables.forEachIndexed { index, cell ->
                cell.forEach { it.placeRelative(x, (height - it.height) / 2) }
                x += spans[index]
            }
        }
    }
}

private fun <T> columnSpans(columns: List<TableColumn<T>>, widths: Map<String, Float>, total: Int, density: Density): IntArray {
    val spans = IntArray(columns.size)
    var weights = 0f
    columns.forEachIndexed { index, column ->
        val chosen = widths[column.key]
        when (val declared = column.width) {
            is ColumnWidth.Fixed -> spans[index] = with(density) { (chosen?.dp ?: declared.width).roundToPx() }
            is ColumnWidth.Share -> if (chosen == null) weights += declared.weight.coerceAtLeast(MinWeight) else spans[index] = with(density) { chosen.dp.roundToPx() }
        }
    }
    var left = (total - spans.sum()).coerceAtLeast(0)
    columns.forEachIndexed { index, column ->
        val declared = column.width
        if (declared is ColumnWidth.Share && column.key !in widths) {
            val weight = declared.weight.coerceAtLeast(MinWeight)
            spans[index] = if (weights <= weight) left else (left * weight / weights).roundToInt()
            left -= spans[index]
            weights -= weight
        }
    }
    return spans
}

@Composable
private fun <T> HeaderCell(column: TableColumn<T>, state: TableState, roving: Roving, appearance: TableHeaderAppearance) {
    val descending = state.layout.sort?.takeIf { it.column == column.key }?.descending
    val press = rememberMachine(PressKernel, PressProperties(enabled = column.sortable)) {
        if (it == Activated) state.layout = state.layout.copy(sort = state.layout.sort.next(column.key))
    }
    val source = rememberInteractions(press)
    val density = LocalDensity.current
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    val measured = remember { floatArrayOf(MinColumn.value) }
    val current = { state.layout.widths[column.key] ?: measured[0] }
    val resize = { size: Float -> state.layout = state.layout.copy(widths = state.layout.widths + (column.key to fitSize(size, MinColumn.value, Float.MAX_VALUE))) }
    val reset = { state.layout = state.layout.copy(widths = state.layout.widths - column.key) }
    val drag = rememberDraggableState { delta -> resize(current() + with(density) { (if (mirrored) -delta else delta).toDp().value }) }
    Layout(
        content = {
            Box(propagateMinConstraints = true) {
                val pressState = press.state
                appearance.Content(
                    HeaderProperties(column.sortable, descending),
                    pressState,
                    LocalThemeSnapshot.current,
                    rememberFeedback(pressState.pressed, pressState.focusVisible),
                    HeaderSlots(column.header),
                )
            }
            Box(Modifier.pointerHoverIcon(PointerIcon.Hand).then(HandlePointerElement({}, reset)).draggable(drag, Orientation.Horizontal))
        },
        modifier = Modifier
            .onSizeChanged { measured[0] = with(density) { it.width.toDp().value } }
            .rovingItem(roving, "$HeaderPart/${column.key}", enabled = true, typeahead = null)
            .then(if (column.sortable) Modifier.press(source, enabled = true, onHold = null) { press.send(PressInput.Activate(press.nextSequence())) } else Modifier.focusable(interactionSource = source))
            .semantics(mergeDescendants = true) {
                if (column.sortable) {
                    stateDescription = when (descending) {
                        null -> NotSortedLabel
                        false -> AscendingLabel
                        true -> DescendingLabel
                    }
                }
                customActions = listOf(
                    CustomAccessibilityAction(WiderLabel) { resize(current() + ColumnStep.value); true },
                    CustomAccessibilityAction(NarrowerLabel) { resize(current() - ColumnStep.value); true },
                    CustomAccessibilityAction(ResetWidthLabel) { reset(); true },
                )
            },
    ) { measurables, constraints ->
        val cell = measurables[0].measure(constraints)
        val edge = measurables[1].measure(Constraints.fixed(EdgeWidth.roundToPx().coerceAtMost(cell.width), cell.height))
        layout(cell.width, cell.height) {
            cell.placeRelative(0, 0)
            edge.placeRelative(cell.width - edge.width, 0)
        }
    }
}

val BareTableHeader: TableHeaderAppearance = object : TableHeaderAppearance {
    @Composable
    override fun Content(properties: HeaderProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: HeaderSlots) {
        Row(Modifier.defaultMinSize(minHeight = 32.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f, fill = false)) { slots.content() }
            properties.descending?.let { BasicText(if (it) " ▼" else " ▲") }
        }
    }
}

internal val MinColumn = 40.dp

private val ColumnStep = 16.dp

private val EdgeWidth = 6.dp

private const val MinWeight = 0.01f

private const val HeaderPart = "header"

private const val NotSortedLabel = "Not sorted"

private const val AscendingLabel = "Sorted ascending"

private const val DescendingLabel = "Sorted descending"

private const val WiderLabel = "Wider"

private const val NarrowerLabel = "Narrower"

private const val ResetWidthLabel = "Reset width"
