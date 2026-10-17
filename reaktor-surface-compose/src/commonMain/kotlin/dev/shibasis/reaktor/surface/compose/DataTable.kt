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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
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
import dev.shibasis.reaktor.surface.GridCell
import dev.shibasis.reaktor.surface.GridProperties
import dev.shibasis.reaktor.surface.GridState
import dev.shibasis.reaktor.surface.GridInput
import dev.shibasis.reaktor.surface.GridEvent
import dev.shibasis.reaktor.surface.GridKernel
import dev.shibasis.reaktor.surface.Activated
import dev.shibasis.reaktor.surface.ActiveChange
import dev.shibasis.reaktor.surface.Edge
import dev.shibasis.reaktor.surface.LocalCommand
import dev.shibasis.reaktor.surface.RovingInput
import dev.shibasis.reaktor.surface.RovingItem
import dev.shibasis.reaktor.surface.RovingKernel
import dev.shibasis.reaktor.surface.RovingList
import dev.shibasis.reaktor.surface.RovingProperties
import dev.shibasis.reaktor.surface.RovingState
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
    data class Content(val min: Dp, val max: Dp, val sample: Int = 200) : ColumnWidth {
        init { require(min > 0.dp && max >= min && sample > 0) }
    }
}

class TableColumn<T>(
    val key: String,
    val width: ColumnWidth,
    val sortable: Boolean = false,
    val cellAlign: Alignment.Horizontal = Alignment.Start,
    val cellPadding: Dp = 0.dp,
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
    var cursor: GridState by mutableStateOf(GridState())
}

@Composable
fun rememberTableState(layout: TableLayout = TableLayout()): TableState = remember { TableState(layout) }

data class HeaderProperties(val sortable: Boolean, val descending: Boolean?)

class HeaderSlots(val content: @Composable () -> Unit)

typealias TableHeaderAppearance = ComposeAppearance<HeaderProperties, PressState, HeaderSlots>

class TableGrid(val columns: Set<String>, val onEvent: (GridEvent) -> Unit)

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
    grid: TableGrid? = null,
    empty: @Composable () -> Unit = {},
) {
    val header = LocalAppearances.current[Appearance.TableHeader]
    val step = with(LocalDensity.current) { ColumnStep.toPx() }
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    val automation = LocalAutomationScope.current
    val measured = remember(columns, LocalDensity.current, LocalThemeSnapshot.current) { mutableStateMapOf<String, Float>() }
    val cachedWidths = remember(measured, state) { derivedStateOf { measured + state.layout.widths } }
    val widths = { cachedWidths.value }
    val record: (TableColumn<T>, Float) -> Unit = { column, width ->
        val content = column.width as? ColumnWidth.Content
        if (content != null && column.key !in state.layout.widths) {
            val next = width.coerceIn(content.min.value, content.max.value)
            if (next > (measured[column.key] ?: content.min.value)) measured[column.key] = next
        }
    }
    BoxWithConstraints(modifier.busy(busy).onKeyEvent { state.scrollColumns(it, if (mirrored) -step else step) }) {
        val tableWidth = maxOf(maxWidth, columns.narrowest(widths()))
        val density = LocalDensity.current
        val viewport = with(density) { maxWidth.roundToPx() }
        val layoutWidth = with(density) { tableWidth.roundToPx() }
        val pins = remember(state) { derivedStateOf { state.layout.pins } }
        val geometryState = remember(columns, state, measured, density, viewport, layoutWidth) {
            derivedStateOf { ColumnGeometry(columnSpans(columns, widths(), layoutWidth, density), columns.map { pins.value[it.key] }, viewport) }
        }
        val geometry = { geometryState.value }
        val gridBinding = grid?.let { cells ->
            val gridColumns = remember(columns, cells.columns) { RovingList(columns.filter { it.key in cells.columns }.map { RovingItem(it.key) }) }
            val properties = GridProperties(source, gridColumns, LocalSurfaceEnvironment.current.keys, mirrored)
            val binding = remember(state) { GridBinding(state, properties) }
            binding.onEvent = cells.onEvent
            binding.revealColumn = { key ->
                val index = columns.indexOfFirst { it.key == key }
                if (index >= 0) state.horizontal.dispatchRawDelta((geometry().reveal(index, state.horizontal.value, LocalCommand.Reveal(dev.shibasis.reaktor.surface.PartKey(key))) - state.horizontal.value).toFloat())
            }
            SideEffect {
                binding.properties = properties
                state.cursor = GridKernel.reconcile(properties, state.cursor).state
            }
            binding
        }
        if (gridBinding != null) {
            val focusedCell = gridBinding.state.cursor.active?.takeIf { gridBinding.host?.focusTarget == it.part.value }
            DisposableEffect(geometry(), state.horizontal.maxValue, gridBinding) {
                if (focusedCell != null) {
                    gridBinding.host?.execute(LocalCommand.Focus(focusedCell.part))
                    gridBinding.revealColumn(focusedCell.column)
                }
                onDispose { }
            }
        }
        val visible by remember(geometryState, state) { derivedStateOf { geometry().visible(state.horizontal.value) } }
        var rowsTop by remember { mutableIntStateOf(0) }
        Column(Modifier.fillMaxSize().horizontalScroll(state.horizontal)) {
            HeaderRow(columns, state, Modifier.width(tableWidth), header, geometry, visible, record)
            if (busy) Progress(null, Modifier.width(tableWidth))
            val body = Modifier.width(tableWidth).weight(1f).onPlaced { rowsTop = it.positionInParent().y.roundToInt() }
            if (source.size == 0) {
                Box(body) { empty() }
            } else {
                val row = remember(columns, state, geometryState, visible, gridBinding) {
                    val content: @Composable ItemScope.(T) -> Unit = { item ->
                        val scope = this
                        ColumnBands(geometry, state.horizontal, Modifier) { band ->
                            val shown = visible[band]
                            val cells = remember(columns, geometryState, index, shown, band) { CellsPolicy(columns, geometry, state.horizontal, band, index, record, shown) }
                            Layout(shown.map { column -> @Composable { key(columns[column].key) {
                                if (gridBinding != null && gridBinding.properties.columns.indexOf(columns[column].key) >= 0)
                                    GridCellContent(scope, columns[column].key, appearance) { columns[column].cell(scope, item) }
                                else columns[column].cell(scope, item)
                            } } }, measurePolicy = cells)
                        }
                    }
                    content
                }
                Collection(source, selection, onSelectionChange, { _, _ -> }, body, mode, onActivate, { _, _ -> }, actions, state.list, behavior, appearance, false, true, row, gridBinding)
            }
        }
        CollectionScrollbar(state.list, Modifier.align(Alignment.TopEnd).padding(top = with(LocalDensity.current) { rowsTop.toDp() }).fillMaxHeight())
        if (state.horizontal.maxValue > 0) TableScrollbar(state.horizontal, Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .then(if (automation == null) Modifier else Modifier.testId(automationId(automation, "scrollbar/horizontal"))))
    }
}

internal class GridBinding(val state: TableState, properties: GridProperties) {
    var host: CollectionHost? = null
    var properties by mutableStateOf(properties)
    var onEvent: (GridEvent) -> Unit = {}
    var revealColumn: (String) -> Unit = {}

    fun send(input: GridInput) {
        val result = GridKernel.reduce(properties, state.cursor, input)
        state.cursor = result.state
        result.events.forEach(onEvent)
        result.commands.forEach {
            if (it !is LocalCommand.Focus || host?.focused != it.part.value) host?.execute(it)
        }
    }
}

@Composable
private fun GridCellContent(scope: ItemScope, column: String, appearance: RowAppearance, content: @Composable () -> Unit) {
    val host = scope.host
    val binding = host.grid ?: return content()
    val cell = remember(scope.key, column) { GridCell(scope.key, column) }
    val focus = remember { FocusRequester() }
    val enabled = binding.properties.rows.indexOf(cell.row).let { it >= 0 && binding.properties.rows.enabled(it) }
    val flags by remember(binding, host, cell) { derivedStateOf {
        val properties = binding.properties
        val range = binding.state.cursor.range(properties)
        val row = properties.rows.indexOf(cell.row)
        val column = properties.columns.indexOf(cell.column)
        (range != null && row in range.first && column in range.second && properties.rows.enabled(row) && properties.columns.enabled(column)) to
            (host.focused == cell.part.value && host.keyboard)
    } }
    DisposableEffect(host, cell) {
        host.register(cell.part.value, focus)
        onDispose { host.unregister(cell.part.value, focus) }
    }
    Box(Modifier.fillMaxWidth().focusRequester(focus)
        .onFocusChanged { host.onCellFocus(cell, it.isFocused) }
        .onPlaced { host.positioned(cell.part.value) }
        .focusProperties { canFocus = binding.state.cursor.active == cell }
        .focusable()
        .gridCellPointer(host, cell)
        .then(if (LocalAutomationScope.current == null) Modifier else Modifier.testId(automationId(LocalAutomationScope.current, cell.part.value)))
        .semantics(mergeDescendants = true) {
            selected = flags.first
            collectionItemInfo = CollectionItemInfo(scope.index, 1, binding.properties.columns.indexOf(column), 1)
            if (enabled) onClick { host.pointCell(cell); true } else disabled()
        }, propagateMinConstraints = true) {
        appearance.Content(RowProperties(flags.first, enabled, 0, 0, false, false), RowState(flags.second, false, flags.second),
            LocalThemeSnapshot.current, rememberFeedback(false, flags.second), RowSlots(content, null))
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
    total + (widths[column.key]?.dp ?: when (val width = column.width) {
        is ColumnWidth.Fixed -> width.width
        is ColumnWidth.Content -> width.min
        is ColumnWidth.Share -> MinColumn
    })
}

private class ColumnGeometry(val spans: IntArray, pins: List<Edge?>, val viewport: Int) {
    val bands = listOf(pins.indices.filter { pins[it] == null }, pins.indices.filter { pins[it] == Edge.First }, pins.indices.filter { pins[it] == Edge.Last })
    val offsets = IntArray(spans.size)
    val rooms = IntArray(3).apply {
        val totals = IntArray(3) { band ->
            var total = 0
            bands[band].forEach { offsets[it] = total; total += spans[it] }
            total
        }
        this[2] = totals[2].coerceAtMost(viewport)
        this[1] = totals[1].coerceAtMost((viewport - this[2]).coerceAtLeast(0))
        this[0] = viewport - this[1] - this[2]
    }
    val edges = intArrayOf(rooms[1], 0, viewport - rooms[2])

    fun visible(scroll: Int): List<List<Int>> = listOf(
        bands[0].filter { rooms[0] > 0 && offsets[it] + spans[it] > scroll && offsets[it] < scroll + rooms[0] }, bands[1], bands[2],
    )

    fun reveal(column: Int, scroll: Int, command: LocalCommand): Int {
        val maximum = (spans.sum() - viewport).coerceAtLeast(0)
        return when {
            column in bands[1] -> if (command is LocalCommand.Reveal) 0 else scroll
            column in bands[2] -> if (command is LocalCommand.Reveal) maximum else scroll
            rooms[0] == 0 -> scroll
            spans[column] >= rooms[0] || offsets[column] < scroll -> offsets[column]
            offsets[column] + spans[column] > scroll + rooms[0] -> offsets[column] + spans[column] - rooms[0]
            else -> scroll
        }.coerceIn(0, maximum)
    }
}

@Composable
private fun ColumnBands(geometry: () -> ColumnGeometry, scroll: ScrollState, modifier: Modifier, content: @Composable (Int) -> Unit) {
    val bands = geometry().bands.indices.filter { geometry().bands[it].isNotEmpty() }
    Layout(content = { bands.forEach { band -> Box(Modifier.clipToBounds(), propagateMinConstraints = true) { content(band) } } }, modifier = modifier) { measurables, constraints ->
        val shape = geometry()
        val placeables = measurables.mapIndexed { index, measurable ->
            val band = bands[index]
            measurable.measure(constraints.copy(minWidth = shape.rooms[band], maxWidth = shape.rooms[band]))
        }
        val height = constraints.constrainHeight(placeables.maxOfOrNull { it.height } ?: 0)
        layout(constraints.constrainWidth(shape.spans.sum()), height) {
            placeables.forEachIndexed { index, placeable -> placeable.placeRelative(scroll.value + shape.edges[bands[index]], Alignment.CenterVertically.align(placeable.height, height)) }
        }
    }
}

@Composable
private fun <T> HeaderRow(columns: List<TableColumn<T>>, state: TableState, modifier: Modifier, appearance: TableHeaderAppearance,
    geometry: () -> ColumnGeometry, visible: List<List<Int>>, record: (TableColumn<T>, Float) -> Unit) {
    val kernel = remember { RovingKernel() }
    val direction = LocalLayoutDirection.current
    val items = remember(columns, state.layout.pins) {
        RovingList((geometry().bands[1] + geometry().bands[0] + geometry().bands[2]).map { RovingItem("$HeaderPart/${columns[it].key}") })
    }
    val liveGeometry by rememberUpdatedState(geometry)
    val liveColumns by rememberUpdatedState(columns)
    val mounted = remember(visible) { visible.flatten().toSet() }
    val liveMounted by rememberUpdatedState(mounted)
    var pending by remember { mutableStateOf<LocalCommand?>(null) }
    val machine = rememberMachine(kernel, RovingProperties(items, Axis.Horizontal, rightToLeft = direction == LayoutDirection.Rtl), { command ->
        if (command is LocalCommand.Focus && (pending as? LocalCommand.Reveal)?.part != command.part) pending = command
        val part = when (command) { is LocalCommand.Focus -> command.part; is LocalCommand.Reveal -> command.part; else -> null }
        val index = part?.value?.removePrefix("$HeaderPart/")?.let { key -> liveColumns.indexOfFirst { it.key == key } } ?: -1
        if (index < 0) false else if (command is LocalCommand.Reveal || index !in liveMounted) {
            if (command is LocalCommand.Reveal) pending = command
            state.horizontal.dispatchRawDelta((liveGeometry().reveal(index, state.horizontal.value, command) - state.horizontal.value).toFloat())
            true
        } else false
    }) {}
    val requested = when (val command = pending) { is LocalCommand.Focus -> command.part; is LocalCommand.Reveal -> command.part; else -> null }
    requested?.value?.takeIf { key -> columns.indexOfFirst { "$HeaderPart/${it.key}" == key } in mounted }?.let { key ->
        SideEffect { machine.send(RovingInput.Point(key, focus = true)) }
    }
    ColumnBands(geometry, state.horizontal, modifier.onKeyEvent { event ->
        val stroke = event.stroke()
        if (event.type != KeyEventType.KeyDown || stroke == null || !kernel.handles(RovingProperties(items, Axis.Horizontal, rightToLeft = direction == LayoutDirection.Rtl), stroke)) false
        else {
            machine.send(RovingInput.Stroke(stroke))
            if (stroke.key == KeyName.Home || stroke.key == KeyName.End) machine.state.active?.let { pending = LocalCommand.Reveal(dev.shibasis.reaktor.surface.PartKey(it)) }
            true
        }
    }) { band ->
        val shown = visible[band]
        Layout(content = { shown.forEach { index -> key(columns[index].key) { HeaderCell(columns[index], state, machine, appearance) } } }) { measurables, constraints ->
            shown.forEachIndexed { index, column ->
                if (columns[column].width is ColumnWidth.Content) record(columns[column], measurables[index].maxIntrinsicWidth(Constraints.Infinity).toDp().value)
            }
            val shape = geometry()
            val placeables = measurables.mapIndexed { index, measurable -> measurable.measure(Constraints(shape.spans[shown[index]], shape.spans[shown[index]], 0, constraints.maxHeight)) }
            val height = constraints.constrainHeight(placeables.maxOfOrNull { it.height } ?: 0)
            layout(constraints.maxWidth, height) {
                pending?.let { command ->
                    val part = when (command) { is LocalCommand.Focus -> command.part; is LocalCommand.Reveal -> command.part; else -> null }
                    val active = columns.indexOfFirst { "$HeaderPart/${it.key}" == part?.value }
                    if (active >= 0) {
                        val wanted = geometry().reveal(active, state.horizontal.value, command)
                        state.horizontal.dispatchRawDelta((wanted - state.horizontal.value).toFloat())
                        if (active in shown && state.horizontal.value == wanted) pending = null
                    }
                }
                val scroll = if (band == 0) state.horizontal.value else 0
                placeables.forEachIndexed { index, placeable -> placeable.placeRelative(shape.offsets[shown[index]] - scroll, Alignment.CenterVertically.align(placeable.height, height)) }
            }
        }
    }
}

private class CellsPolicy<T>(private val columns: List<TableColumn<T>>, private val geometry: () -> ColumnGeometry,
    private val scroll: ScrollState, private val band: Int, private val row: Int, private val record: (TableColumn<T>, Float) -> Unit, private val visible: List<Int>) : MultiContentMeasurePolicy {
    override fun MeasureScope.measure(measurables: List<List<Measurable>>, constraints: Constraints): MeasureResult {
        visible.forEachIndexed { index, columnIndex ->
            val column = columns[columnIndex]
            val content = column.width as? ColumnWidth.Content
            if (content != null && row < content.sample) record(column,
                (measurables[index].maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0).toDp().value + 2 * column.cellPadding.value)
        }
        val shape = geometry()
        val insets = IntArray(visible.size) { columns[visible[it]].cellPadding.roundToPx() }
        val rooms = IntArray(visible.size) { (shape.spans[visible[it]] - 2 * insets[it]).coerceAtLeast(0) }
        val placeables = measurables.mapIndexed { index, cell -> cell.map { it.measure(Constraints(0, rooms[index], 0, constraints.maxHeight)) } }
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else (placeables.maxOfOrNull { cell -> cell.maxOfOrNull { it.height } ?: 0 } ?: 0).coerceAtLeast(constraints.minHeight)
        return layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { index, cell ->
                val align = columns[visible[index]].cellAlign
                cell.forEach { it.placeRelative(shape.offsets[visible[index]] - (if (band == 0) scroll.value else 0) + insets[index] + align.align(it.width, rooms[index], LayoutDirection.Ltr), Alignment.CenterVertically.align(it.height, height)) }
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
            is ColumnWidth.Content -> spans[index] = with(density) { (chosen?.dp ?: declared.min).roundToPx() }
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
private fun <T> HeaderCell(column: TableColumn<T>, state: TableState, roving: Machine<RovingProperties, RovingState, RovingInput, ActiveChange>, appearance: TableHeaderAppearance) {
    val descending = state.layout.sort?.takeIf { it.column == column.key }?.descending
    val press = rememberMachine(PressKernel, PressProperties(enabled = column.sortable)) {
        if (it == Activated) {
            roving.send(RovingInput.Point("$HeaderPart/${column.key}", focus = true))
            state.layout = state.layout.copy(sort = state.layout.sort.next(column.key))
        }
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
            .part(roving, dev.shibasis.reaktor.surface.PartKey("$HeaderPart/${column.key}"))
            .onFocusChanged { if (it.isFocused) roving.send(RovingInput.Focused("$HeaderPart/${column.key}")) }
            .focusProperties { canFocus = roving.state.active == "$HeaderPart/${column.key}" }
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
        Row(Modifier.defaultMinSize(minHeight = 32.dp).focusFrame(state.focusVisible).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
