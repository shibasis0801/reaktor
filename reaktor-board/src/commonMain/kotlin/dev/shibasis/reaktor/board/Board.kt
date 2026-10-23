package dev.shibasis.reaktor.board

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class BoardPoint(val x: Double, val y: Double)

@Serializable
enum class BoardInk { Muted, Accent, Busy, Warn, Ok, Danger, Topic }

@Serializable
enum class BoardView(val label: String, val storage: String) { Dag("DAG", "graph"), Kanban("Kanban", "kanban"), Graph("Graph", "relations") }

@Serializable
data class BoardLane(
    val id: String,
    val title: String,
    val ink: BoardInk = BoardInk.Muted,
    val drop: Boolean = true,
    val create: Boolean = true,
    val review: Boolean = false,
)

@Serializable
data class BoardItem(
    val key: String,
    val title: String,
    val lane: String,
    val after: List<String> = emptyList(),
    val eyebrow: String = "",
    val badge: String = "",
    val initials: String = "",
    val mark: BoardInk? = null,
    val group: String = "",
    val editable: Boolean = true,
    val at: BoardPoint? = null,
    val note: String = "",
    val dag: Boolean = true,
    val footer: String = "",
)

@Serializable
data class BoardWords(
    val one: String = "item",
    val many: String = "items",
    val after: String = "prerequisite",
    val afters: String = "prerequisites",
    val lane: String = "status",
    val start: String = "Create one, then connect the next step from its + handle.",
    val review: String = "review",
    val reviewing: String = "review it",
) {
    val oneTitle: String get() = one.replaceFirstChar(Char::uppercaseChar)
    val manyTitle: String get() = many.replaceFirstChar(Char::uppercaseChar)
    val afterTitle: String get() = after.replaceFirstChar(Char::uppercaseChar)
    val anOne: String get() = indefinite(one)
    fun count(n: Int) = if (n == 0) "No $afters" else "$n ${if (n == 1) after else afters}"
}

fun indefinite(noun: String) = if (noun.firstOrNull()?.lowercaseChar() in "aeiou".toSet()) "an $noun" else "a $noun"

object BoardGeometry {
    const val CardWidth = 300.0
    const val CardHeight = 180.0
    const val LaneLeft = 48.0
    const val LanePitch = 364.0
    const val LaneWidth = 340.0
    const val LaneInset = 20.0
    const val CardTop = 126.0
    const val CardPitch = 200.0
    const val RankLeft = 48.0
    const val RankTop = 80.0
    const val RankPitch = 400.0
    const val RowPitch = 228.0
}

@Serializable
data class Board(
    val lanes: List<BoardLane>,
    val items: List<BoardItem>,
    val words: BoardWords = BoardWords(),
    val limit: Int = 50,
    val views: List<BoardView> = listOf(BoardView.Dag, BoardView.Kanban),
    val locked: String = "",
    val titleLimit: Int = 300,
) {
    @Transient private val keyed: Map<String, BoardItem> = items.associateBy { it.key }
    @Transient private val laneIndex: Map<String, Int> = lanes.withIndex().associate { it.value.id to it.index }

    fun item(key: String?): BoardItem? = key?.let(keyed::get)
    fun lane(id: String?): BoardLane? = id?.let(laneIndex::get)?.let(lanes::get)
    fun laneX(id: String): Double = BoardGeometry.LaneLeft + (laneIndex[id] ?: 0) * BoardGeometry.LanePitch
    fun laneAt(centerX: Double): BoardLane? = lanes.firstOrNull { centerX >= laneX(it.id) && centerX < laneX(it.id) + BoardGeometry.LaneWidth }
    fun slot(lane: String, index: Int) = BoardPoint(laneX(lane) + BoardGeometry.LaneInset, BoardGeometry.CardTop + index * BoardGeometry.CardPitch)
    val creatable: List<BoardLane> get() = if (locked.isNotBlank()) emptyList() else lanes.filter { it.create }
    val reviewLane: BoardLane? get() = lanes.firstOrNull { it.review }

    fun problem(view: BoardView, from: String, to: String, refer: (BoardItem, BoardItem) -> String? = { _, _ -> null }): String? {
        val prerequisite = item(from) ?: return "This ${words.after} is no longer available."
        val target = item(to) ?: return "This ${words.one} is no longer available."
        return when {
            locked.isNotBlank() -> locked
            view != BoardView.Dag ->"Switch to DAG to edit ${words.afters}."
            !target.editable -> "This ${words.one} is read only."
            from in target.after -> "This ${words.after} is already connected."
            target.after.size >= limit -> "A ${words.one} can have up to $limit ${if (limit == 1) words.after else words.afters}."
            else -> refer(prerequisite, target) ?: if (items.wouldCycle(to, from)) "That connection would create a cycle." else null
        }
    }

    val creatableTitles: String get() = creatable.joinToString(" or ") { it.title }

    fun creationProblem(lane: String): String? = when {
        locked.isNotBlank() -> locked
        lane(lane)?.create == true -> null
        else -> "Create ${words.anOne} in $creatableTitles${if (reviewLane != null) "; completing it requires ${words.review}" else ""}."
    }

    fun titleProblem(title: String): String? = if (title.trim().length in 1..titleLimit) null else "Use a title between 1 and $titleLimit characters."
}

fun List<BoardItem>.ranked(): Map<String, BoardPoint> {
    val byKey = associateBy { it.key }
    val degree = associate { item -> item.key to item.after.count(byKey::containsKey) }.toMutableMap()
    val children = mutableMapOf<String, MutableList<String>>()
    forEach { item -> item.after.filter(byKey::containsKey).forEach { children.getOrPut(it) { mutableListOf() }.add(item.key) } }
    val queue = ArrayDeque(filter { degree[it.key] == 0 }.map { it.key })
    val ranks = mutableMapOf<String, Int>()
    while (queue.isNotEmpty()) {
        val key = queue.removeFirst()
        children[key].orEmpty().forEach { child ->
            ranks[child] = maxOf(ranks[child] ?: 0, (ranks[key] ?: 0) + 1)
            degree[child] = degree.getValue(child) - 1
            if (degree[child] == 0) queue.addLast(child)
        }
    }
    val rows = mutableMapOf<Int, Int>()
    return associate { item ->
        val rank = ranks[item.key] ?: 0
        val row = rows[rank] ?: 0
        rows[rank] = row + 1
        item.key to BoardPoint(BoardGeometry.RankLeft + rank * BoardGeometry.RankPitch, BoardGeometry.RankTop + row * BoardGeometry.RowPitch)
    }
}

fun List<BoardItem>.wouldCycle(item: String, prerequisite: String): Boolean {
    val byKey = associateBy { it.key }
    val queue = ArrayDeque(listOf(prerequisite))
    val seen = mutableSetOf<String>()
    while (queue.isNotEmpty()) {
        val key = queue.removeFirst()
        if (key == item) return true
        if (seen.add(key)) queue.addAll(byKey[key]?.after.orEmpty())
    }
    return false
}
