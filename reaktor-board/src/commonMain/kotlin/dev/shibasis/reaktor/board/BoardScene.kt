package dev.shibasis.reaktor.board

import kotlinx.serialization.Serializable

@Serializable
data class BoardTheme(
    val laneFill: String = "#f6f7fb",
    val laneLine: String = "#e6eaf1",
    val laneLitFill: String = "#f0edff",
    val laneLit: String = "#aaa0ef",
    val laneTitle: String = "#34415a",
    val laneBadge: String = "#e9edf4",
    val laneCount: String = "#8591a6",
    val arrow: String = "#b4bad0",
    val draft: String = "#9683e5",
    val shadow: String = "#edf0f6",
    val halo: String = "#e7e2ff",
    val card: String = "#ffffff",
    val cardLine: String = "#dfe5f0",
    val active: String = "#8370e7",
    val eyebrow: String = "#93a0b5",
    val badgeLine: String = "#e8ecf3",
    val badgeFill: String = "#f7f8fb",
    val badgeText: String = "#8b98ac",
    val title: String = "#3c4861",
    val footer: String = "#94a0b5",
    val owner: String = "#eeeafa",
    val ownerText: String = "#7868b1",
    val inks: Map<BoardInk, String> = mapOf(
        BoardInk.Muted to "#a7b3c3", BoardInk.Busy to "#eda63d", BoardInk.Ok to "#4bb88a", BoardInk.Warn to "#d9822b",
        BoardInk.Danger to "#c36b66", BoardInk.Accent to "#7865d9", BoardInk.Topic to "#9683e5"),
    val chrome: Map<String, String> = emptyMap(),
) {
    fun ink(value: BoardInk): String = inks[value] ?: inks.getValue(BoardInk.Muted)
}

data class BoardDraft(val at: BoardPoint, val after: String?, val lane: String, val editing: String? = null)

data class BoardFrame(
    val shown: List<BoardItem>,
    val positions: Map<String, BoardPoint>,
    val selected: String? = null,
    val source: String? = null,
    val draft: BoardDraft? = null,
)

sealed interface BoardShape { val id: String }
data class BoardBox(override val id: String, val x: Double, val y: Double, val width: Double, val height: Double, val stroke: String, val fill: String, val line: Double = 1.0) : BoardShape
data class BoardDot(override val id: String, val x: Double, val y: Double, val size: Double, val stroke: String, val fill: String, val line: Double = 1.0) : BoardShape
data class BoardRule(override val id: String, val x: Double, val y: Double, val width: Double, val stroke: String) : BoardShape
data class BoardLabel(override val id: String, val text: String, val x: Double, val y: Double, val size: Int, val color: String) : BoardShape
data class BoardEnds(val from: String, val to: String)
data class BoardArrow(override val id: String, val x: Double, val y: Double, val dx: Double, val dy: Double, val stroke: String, val dashed: Boolean = false, val ends: BoardEnds? = null) : BoardShape

object BoardIds {
    const val Draft = "draft"
    private const val Card = "card:"
    fun card(key: String) = "$Card${escape(key)}"
    fun cardOf(id: String): String? = if (id.startsWith(Card)) Card + id.removePrefix(Card).substringBefore(':') else null
    fun lane(id: String) = "lane:$id"
    fun edge(from: String, to: String) = "dependency:${card(from)}:${card(to)}"
    fun generated(id: String) = id == Draft || ':' in id
    private const val Plain = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
    fun escape(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val char = (byte.toInt() and 0xff).toChar()
            if (char in Plain) append(char) else append('%').append((byte.toInt() and 0xff).toString(16).uppercase().padStart(2, '0'))
        }
    }
}

fun wrap(value: String, width: Int, lines: Int): String {
    val words = value.replace(Regex("\\s+"), " ").trim().split(" ")
    val rows = mutableListOf("")
    words.forEach { word ->
        if (rows.last().isNotEmpty() && rows.last().length + word.length + 1 > width) rows.add("")
        rows[rows.lastIndex] = (rows.last() + " " + word).trim()
    }
    return rows.take(lines).mapIndexed { i, row -> if (i == lines - 1 && (rows.size > lines || row.length > width)) row.take(width - 1).trimEnd() + "…" else row.take(width) }.joinToString("\n")
}

fun Board.positions(view: BoardView, shown: List<BoardItem>, saved: Map<String, BoardPoint>): Map<String, BoardPoint> {
    if (view == BoardView.Kanban) return buildMap {
        lanes.forEach { lane -> order(lane.id, shown, saved).forEachIndexed { index, item -> put(item.key, slot(lane.id, index)) } }
    }
    val defaults = shown.ranked()
    return shown.associate { it.key to (saved[it.key] ?: it.at ?: defaults.getValue(it.key)) }
}

data class BoardGroup(val id: String, val shapes: List<BoardShape>)

fun Board.scene(frame: BoardFrame, theme: BoardTheme = BoardTheme()): List<BoardShape> = groups(frame, theme).flatMap { it.shapes }

fun Board.groups(frame: BoardFrame, theme: BoardTheme = BoardTheme()): List<BoardGroup> = buildList {
    val shown = frame.shown
    val at = frame.positions
    shown.forEach { item ->
        item.after.forEach dependency@{ prerequisite ->
            val from = at[prerequisite] ?: return@dependency
            val to = at[item.key] ?: return@dependency
            val x = from.x + BoardGeometry.CardWidth
            val id = BoardIds.edge(prerequisite, item.key)
            add(BoardGroup(id, listOf(BoardArrow(id, x, from.y + BoardGeometry.CardHeight / 2, to.x - x, to.y - from.y, theme.arrow, ends = BoardEnds(prerequisite, item.key)))))
        }
    }
    shown.forEach card@{ item ->
        val point = at[item.key] ?: return@card
        add(BoardGroup(BoardIds.card(item.key), card(item, point, frame, theme)))
    }
    frame.draft?.takeIf { it.editing == null }?.let { draft ->
        val point = draft.at
        add(BoardGroup(BoardIds.Draft, listOfNotNull(
            draft.after?.let(at::get)?.let { from ->
                BoardArrow("${BoardIds.Draft}:edge", from.x + BoardGeometry.CardWidth, from.y + BoardGeometry.CardHeight / 2,
                    point.x - from.x - BoardGeometry.CardWidth, point.y - from.y, theme.draft, dashed = true)
            },
            BoardBox("${BoardIds.Draft}:halo", point.x - 4, point.y - 4, 308.0, 188.0, theme.halo, theme.halo, 0.0),
            BoardBox(BoardIds.Draft, point.x, point.y, BoardGeometry.CardWidth, BoardGeometry.CardHeight, theme.active, theme.card, 1.8))))
    }
}

private fun Board.card(item: BoardItem, point: BoardPoint, frame: BoardFrame, theme: BoardTheme): List<BoardShape> = buildList {
    val id = BoardIds.card(item.key)
    val active = frame.selected == item.key || frame.source == item.key || frame.draft?.editing == item.key
    add(BoardBox("$id:shadow", point.x, point.y + 3, BoardGeometry.CardWidth, BoardGeometry.CardHeight, theme.shadow, theme.shadow, 0.0))
    if (active) add(BoardBox("$id:halo", point.x - 4, point.y - 4, 308.0, 188.0, theme.halo, theme.halo, 0.0))
    add(BoardBox(id, point.x, point.y, BoardGeometry.CardWidth, BoardGeometry.CardHeight, if (active) theme.active else theme.cardLine, theme.card, if (active) 1.8 else 1.2))
    item.mark?.let { mark -> add(BoardBox("$id:mark", point.x, point.y + 18, 4.0, 144.0, theme.ink(mark), theme.ink(mark), 0.0)) }
    if (item.eyebrow.isNotBlank()) add(BoardLabel("$id:code", wrap(item.eyebrow.uppercase(), 22, 1), point.x + 22, point.y + 23, 12, theme.eyebrow))
    if (item.badge.isNotBlank()) {
        add(BoardBox("$id:priority", point.x + 234, point.y + 18, 44.0, 27.0, theme.badgeLine, theme.badgeFill))
        add(BoardLabel("$id:priority-text", item.badge.take(5), point.x + 246, point.y + 24, 12, theme.badgeText))
    }
    if (frame.draft?.editing != item.key) add(BoardLabel("$id:title", wrap(item.title, 27, 3), point.x + 22, point.y + 61, 18, theme.title))
    add(BoardLabel("$id:dependencies", footer(item), point.x + 22, point.y + 151, 12, theme.footer))
    if (item.initials.isNotBlank()) {
        add(BoardDot("$id:owner", point.x + 245, point.y + 137, 34.0, theme.owner, theme.owner, 0.0))
        add(BoardLabel("$id:initials", item.initials.take(2).uppercase(), point.x + 253, point.y + 148, 11, theme.ownerText))
    }
}

private fun Board.footer(item: BoardItem): String =
    item.footer.takeIf(String::isNotBlank)?.let { wrap(it, 34, 1) } ?: "${lane(item.lane)?.title ?: item.lane} · ${words.count(item.after.size)}"

fun Board.creationPoint(view: BoardView, shown: List<BoardItem>, saved: Map<String, BoardPoint>, laid: Map<String, BoardPoint>, at: BoardPoint?, after: String?, lane: String, centre: BoardPoint): BoardPoint {
    if (view == BoardView.Kanban) return slot(lane, shown.count { it.lane == lane })
    val defaults = items.ranked()
    val occupied = items.map { saved[it.key] ?: laid[it.key] ?: it.at ?: defaults.getValue(it.key) }
    var place = at ?: after?.let(laid::get)?.let { BoardPoint(it.x + BoardGeometry.RankPitch, it.y) } ?: centre
    repeat(occupied.size + 1) {
        if (occupied.any { kotlin.math.abs(it.x - place.x) < 340 && kotlin.math.abs(it.y - place.y) < 210 }) place = place.copy(y = place.y + BoardGeometry.RowPitch)
    }
    return place
}

fun Board.order(lane: String, shown: List<BoardItem>, saved: Map<String, BoardPoint>): List<BoardItem> =
    shown.filter { it.lane == lane }.sortedBy { saved[it.key]?.y ?: Double.MAX_VALUE }

fun Board.reorder(lane: String, shown: List<BoardItem>, saved: Map<String, BoardPoint>, moving: BoardItem, index: Int): Map<String, BoardPoint> {
    val others = order(lane, shown, saved).filter { it.key != moving.key }.toMutableList()
    others.add(index.coerceIn(0, others.size), moving)
    return others.withIndex().associate { (position, item) -> item.key to slot(lane, position) }
}
