package dev.shibasis.reaktor.surface

data class GridCell(val row: String, val column: String) {
    val part: PartKey get() = PartKey("cell/${row.length}:$row$column")
}

data class GridProperties(
    val rows: RovingItems,
    val columns: RovingItems,
    val keys: KeyConvention,
    val rightToLeft: Boolean = false,
)

data class GridState(val active: GridCell? = null, val anchor: GridCell? = active) {
    fun range(properties: GridProperties): Pair<IntRange, IntRange>? {
        val active = active?.takeIf(properties::reachable) ?: return null
        val anchor = anchor?.takeIf(properties::reachable) ?: active
        val aRow = properties.rows.indexOf(active.row)
        val bRow = properties.rows.indexOf(anchor.row)
        val aColumn = properties.columns.indexOf(active.column)
        val bColumn = properties.columns.indexOf(anchor.column)
        return minOf(aRow, bRow)..maxOf(aRow, bRow) to minOf(aColumn, bColumn)..maxOf(aColumn, bColumn)
    }
}

sealed interface GridInput {
    data class Stroke(val stroke: KeyStroke, val page: Int = 1) : GridInput
    data class Point(val cell: GridCell) : GridInput
    data class Focused(val cell: GridCell) : GridInput
}

sealed interface GridEvent {
    data class Activate(val cell: GridCell) : GridEvent
    data class Copy(val rows: List<String>, val columns: List<String>) : GridEvent
}

object GridKernel : BehaviorKernel<GridProperties, GridState, GridInput, GridEvent> {
    override fun initial(properties: GridProperties): GridState {
        val row = properties.rows.firstEnabled() ?: return GridState()
        val column = properties.columns.firstEnabled() ?: return GridState()
        return GridState(GridCell(row, column))
    }

    override fun reconcile(properties: GridProperties, state: GridState): Reduction<GridState, GridEvent> {
        val cell = state.active?.takeIf(properties::reachable) ?: return Reduction(initial(properties))
        return Reduction(state.copy(anchor = state.anchor?.takeIf(properties::reachable) ?: cell))
    }

    override fun reduce(properties: GridProperties, state: GridState, input: GridInput): Reduction<GridState, GridEvent> = when (input) {
        is GridInput.Point -> if (!properties.reachable(input.cell)) Reduction(state)
            else move(state, input.cell, false).copy(events = listOf(GridEvent.Activate(input.cell)))
        is GridInput.Focused -> if (!properties.reachable(input.cell)) Reduction(state)
            else Reduction(state.copy(active = input.cell, anchor = if (state.active == input.cell) state.anchor else input.cell))
        is GridInput.Stroke -> stroke(properties, state, input)
    }

    fun handles(properties: GridProperties, stroke: KeyStroke): Boolean {
        if (stroke.alt) return false
        val primary = if (properties.keys == KeyConvention.Mac) stroke.meta && !stroke.control else stroke.control && !stroke.meta
        if (stroke.meta || stroke.control) return primary &&
            (stroke.key in listOf(KeyName.Home, KeyName.End) || stroke.key == KeyName.C && !stroke.shift)
        return stroke.key in listOf(KeyName.Up, KeyName.Down, KeyName.Left, KeyName.Right, KeyName.Home, KeyName.End, KeyName.PageUp, KeyName.PageDown) ||
            stroke.key == KeyName.Enter && !stroke.shift
    }

    private fun stroke(properties: GridProperties, state: GridState, input: GridInput.Stroke): Reduction<GridState, GridEvent> {
        if (!handles(properties, input.stroke)) return Reduction(state)
        val current = reconcile(properties, state).state
        val active = current.active ?: return Reduction(current)
        val key = input.stroke.key
        if (key == KeyName.C) {
            val range = current.range(properties) ?: return Reduction(current)
            return Reduction(current, listOf(GridEvent.Copy(
                range.first.filter(properties.rows::enabled).map(properties.rows::key),
                range.second.filter(properties.columns::enabled).map(properties.columns::key),
            )))
        }
        if (key == KeyName.Enter) return Reduction(current, listOf(GridEvent.Activate(active)))
        val row = properties.rows.indexOf(active.row)
        val column = properties.columns.indexOf(active.column)
        val primary = input.stroke.meta || input.stroke.control
        val forward = if (properties.rightToLeft) -1 else 1
        val nextRow = when (key) {
            KeyName.Home -> if (primary) properties.rows.firstEnabled()?.let(properties.rows::indexOf) ?: row else row
            KeyName.End -> if (primary) properties.rows.moveIndex(row, properties.rows.size) else row
            KeyName.Up -> properties.rows.moveIndex(row, -1)
            KeyName.Down -> properties.rows.moveIndex(row, 1)
            KeyName.PageUp -> properties.rows.moveIndex(row, -input.page.coerceAtLeast(1))
            KeyName.PageDown -> properties.rows.moveIndex(row, input.page.coerceAtLeast(1))
            else -> row
        }
        val nextColumn = when (key) {
            KeyName.Home -> properties.columns.firstEnabled()?.let(properties.columns::indexOf) ?: column
            KeyName.End -> properties.columns.moveIndex(column, properties.columns.size)
            KeyName.Left -> properties.columns.moveIndex(column, -forward)
            KeyName.Right -> properties.columns.moveIndex(column, forward)
            else -> column
        }
        return move(current, GridCell(properties.rows.key(nextRow), properties.columns.key(nextColumn)), input.stroke.shift)
    }

    private fun move(state: GridState, cell: GridCell, extend: Boolean): Reduction<GridState, GridEvent> = Reduction(
        GridState(cell, if (extend) state.anchor ?: state.active ?: cell else cell),
        commands = listOf(LocalCommand.Focus(cell.part), LocalCommand.Reveal(cell.part)),
    )
}

private fun GridProperties.reachable(cell: GridCell): Boolean =
    rows.indexOf(cell.row).let { it >= 0 && rows.enabled(it) } && columns.indexOf(cell.column).let { it >= 0 && columns.enabled(it) }

private fun RovingItems.moveIndex(from: Int, delta: Int): Int {
    val target = (from.toLong() + delta).coerceIn(0, (size - 1).toLong()).toInt()
    val ahead = if (delta > 0) target until size else target downTo 0
    val behind = if (delta > 0) target downTo from else target..from
    return ahead.firstOrNull(::enabled) ?: behind.firstOrNull(::enabled) ?: from
}
