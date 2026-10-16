@file:OptIn(kotlin.js.ExperimentalJsExport::class)
package dev.shibasis.reaktor.surface.react

import dev.shibasis.reaktor.surface.*
import react.*

@JsExport
object SurfaceData {
    /** Caller owns row ordering and layout; the common grid kernel owns keyboard focus/ranges/copy. */
    fun DataTable(props: dynamic): dynamic {
        val environment = useSurfaceEnvironment()
        val rightToLeft = props.dir == "rtl" || (props.dir == null && environment.rightToLeft)
        val rows = choices(props)
        val columns = props.columns as Array<dynamic>
        val rowItems = useMemo(rows) { ReactItems(rows) }
        val columnItems = useMemo(columns) { ReactItems(columns) }
        val properties = GridProperties(rowItems, columnItems, useSurfaceEnvironment().keys, rightToLeft)
        val machine = useSurfaceMachine(GridKernel, properties) { event -> when (event) {
            is GridEvent.Activate -> if (props.onActivate != null) props.onActivate(event.cell.row, event.cell.column)
            is GridEvent.Copy -> if (props.onCopy != null) props.onCopy(event.rows.toTypedArray(), event.columns.toTypedArray())
        } }
        val layout = props.layout.unsafeCast<TableLayout?>() ?: TableLayout()
        val sort = layout.sort ?: (props.sort?.column as String?)?.let { Sort(it, props.sort.descending == true) }
        val window = useRowWindow(rows.size, (props.height as Number?)?.toDouble() ?: 0.0, (props.rowHeight as Number?)?.toDouble() ?: 0.0)
        machine.reveal = { key ->
            val cell = machine.state.active
            if (cell != null && cell.part == key) window.reveal(rowItems.indexOf(cell.row))
        }
        val range = machine.state.range(properties)
        fun style(index: Int): dynamic {
            val column = columns[index]
            val key = column.id as String
            val result = attrs("width" to layout.widths[key], "minWidth" to layout.widths[key])
            val pin = layout.pins[key]
            if (pin != null) {
                result.position = "sticky"; result.zIndex = 1
                val preceding = if (pin == Edge.First) 0 until index else index + 1 until columns.size
                val offset = preceding.filter { layout.pins[columns[it].id as String] == pin }.sumOf { (layout.widths[columns[it].id as String] ?: 160f).toDouble() }
                result[if (pin == Edge.First) "insetInlineStart" else "insetInlineEnd"] = offset
            }
            return result
        }
        val attributes = attrs("role" to "grid", "aria-label" to props.label, "aria-rowcount" to rows.size + 1,
            "aria-colcount" to columns.size, "className" to "surface-data-table", "dir" to props.dir)
        attributes.onKeyDown = { event: dynamic -> keyStroke(event)?.let { stroke -> if (GridKernel.handles(properties, stroke)) {
            event.preventDefault(); machine.send(GridInput.Stroke(stroke, (props.pageSize as Int?) ?: 10))
        } } }
        val table = element("table", attributes,
            element("thead", attrs("style" to if (window.enabled) attrs("position" to "sticky", "top" to 0, "zIndex" to 2) else null), element("tr", attrs("role" to "row"), *columns.mapIndexed { index, column ->
                val id = column.id as String
                element("th", attrs("key" to id, "role" to "columnheader", "scope" to "col", "style" to style(index), "aria-sort" to if (sort?.column != id) "none" else if (sort.descending) "descending" else "ascending"),
                    if (column.sortable == true) element("button", attrs("type" to "button", "onClick" to { _: dynamic ->
                        val next = sort.next(id)
                        if (props.onLayoutChange != null) props.onLayoutChange(layout.copy(sort = next))
                        if (props.onSort != null) props.onSort(if (next == null) null else attrs("column" to next.column, "descending" to next.descending))
                    }), column.label) else column.label,
                    if (column.resizable != true || props.onLayoutChange == null) null else element(SurfaceReact.asDynamic().Splitter, attrs(
                        "label" to "Resize ${column.label}", "value" to (layout.widths[id] ?: 160f), "min" to 48, "max" to 2000,
                        "onChange" to { width: Float, _: Boolean -> props.onLayoutChange(layout.copy(widths = layout.widths + (id to width))) })),
                    if (column.pinnable != true || props.onLayoutChange == null) null else element("button", attrs("type" to "button",
                        "aria-label" to "Pin ${column.label}", "aria-pressed" to (id in layout.pins), "onClick" to { _: dynamic ->
                            props.onLayoutChange(layout.copy(pins = if (id in layout.pins) layout.pins - id else layout.pins + (id to Edge.First))) }), "Pin"))
            }.toTypedArray())),
            element("tbody", null,
                if (window.enabled) element("tr", attrs("aria-hidden" to true), element("td", attrs("colSpan" to columns.size, "style" to attrs("height" to window.before, "padding" to 0)))) else null,
                *rows.sliceArray(window.first until window.end).mapIndexed { offset, row ->
                val rowIndex = window.first + offset
                element("tr", attrs("key" to row.id, "role" to "row", "aria-rowindex" to rowIndex + 2, "style" to if (window.enabled) attrs("height" to window.rowHeight) else null),
                *columns.mapIndexed { columnIndex, column ->
                    val cell = GridCell(row.id as String, column.id as String)
                    element("td", attrs("key" to column.id, "role" to "gridcell", "aria-colindex" to columnIndex + 1,
                        "style" to style(columnIndex),
                        "aria-selected" to (range != null && rowIndex in range.first && columnIndex in range.second),
                        "tabIndex" to if (machine.state.active == cell) 0 else -1, "ref" to machine.part(cell.part),
                        "onFocus" to { _: dynamic -> machine.send(GridInput.Focused(cell)) },
                        "onClick" to { _: dynamic -> machine.send(GridInput.Point(cell)) }),
                        if (jsTypeOf(props.renderCell) == "function") props.renderCell(row, column) else row[column.id as String])
                }.toTypedArray()) }.toTypedArray(),
                if (window.enabled) element("tr", attrs("aria-hidden" to true), element("td", attrs("colSpan" to columns.size, "style" to attrs("height" to window.after(rows.size), "padding" to 0)))) else null))
        return surfaceAppearance("DataTable", props, if (window.enabled) element("div", attrs("ref" to window.reference,
            "onScroll" to window.onScroll, "style" to attrs("height" to window.viewport, "overflow" to "auto")), table) else table)
    }
    fun DocumentTabs(props: dynamic): dynamic = surfaceAppearance("DocumentTabs", props, element(SurfaceWeb.asDynamic().Tabs, props))
}
