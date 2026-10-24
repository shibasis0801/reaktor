package dev.shibasis.reaktor.board

import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.contract.Served
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import dev.shibasis.reaktor.service.contract.Service

@Serializable
data class BoardSnapshot(
    val board: Board,
    val revision: Long = 0,
    val view: BoardView = BoardView.Dag,
    val active: String? = null,
    val layout: String = "",
    val empty: String = "",
    val theme: BoardTheme? = null,
    val places: Boolean = false,
    val hidden: Int = 0,
) : Response()

object BoardBudget {
    const val Limit = 56_000
    private val json = Json { encodeDefaults = true }

    fun size(snapshot: BoardSnapshot) = json.encodeToString(BoardSnapshot.serializer(), snapshot).length

    fun fit(snapshot: BoardSnapshot, limit: Int = Limit, rank: (BoardItem) -> Int = { 0 }): BoardSnapshot {
        if (size(snapshot) <= limit) return snapshot
        val items = snapshot.board.items
        val order = items.indices.sortedBy { rank(items[it]) }
        fun take(count: Int): BoardSnapshot {
            val kept = order.take(count).toSet()
            val shown = items.filterIndexed { index, _ -> index in kept }
            val keys = shown.mapTo(HashSet()) { it.key }
            return snapshot.copy(board = snapshot.board.copy(items = shown.map { item -> item.copy(after = item.after.filter { it in keys }) }),
                hidden = snapshot.hidden + items.size - count)
        }
        var low = 0
        var high = items.size - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (size(take(middle)) <= limit) low = middle else high = middle - 1
        }
        return take(low)
    }
}

@Serializable
sealed class BoardEdit {
    @Serializable @SerialName("move")
    data class Move(val key: String, val lane: String) : BoardEdit()

    @Serializable @SerialName("connect")
    data class Connect(val from: String, val to: String) : BoardEdit()

    @Serializable @SerialName("disconnect")
    data class Disconnect(val from: String, val to: String) : BoardEdit()

    @Serializable @SerialName("create")
    data class Create(val title: String, val lane: String, val after: String? = null, val at: BoardPoint? = null,
        val fields: Map<String, String> = emptyMap()) : BoardEdit()

    @Serializable @SerialName("rename")
    data class Rename(val key: String, val title: String) : BoardEdit()

    @Serializable @SerialName("place")
    data class Place(val key: String, val at: BoardPoint) : BoardEdit()

    @Serializable @SerialName("review")
    data class Review(val key: String) : BoardEdit()

    @Serializable @SerialName("sketch")
    data class Sketch(val lane: String, val cards: List<SketchCard>, val links: List<SketchLink> = emptyList(), val notes: List<SketchNote> = emptyList()) : BoardEdit() {
        fun prerequisites(key: String): List<String> = links.filter { it.to == key }.map { it.from }

        fun ordered(): List<SketchCard> {
            val refs = cards.mapTo(HashSet()) { it.ref }
            val waiting = cards.associate { card -> card.ref to prerequisites(card.ref).count(refs::contains) }.toMutableMap()
            val ready = ArrayDeque(cards.filter { waiting[it.ref] == 0 })
            val placed = ArrayList<SketchCard>(cards.size)
            while (ready.isNotEmpty()) {
                val card = ready.removeFirst()
                placed += card
                links.filter { it.from == card.ref && it.to in refs }.forEach { link ->
                    waiting[link.to] = waiting.getValue(link.to) - 1
                    if (waiting[link.to] == 0) ready.addLast(cards.first { it.ref == link.to })
                }
            }
            return placed + cards.filter { it !in placed }
        }
    }
}

@Serializable
class BoardRead : Request()

@Serializable
data class BoardChange(val edit: BoardEdit, val revision: Long? = null) : Request()

@Serializable
data class BoardSelect(val key: String? = null, val open: Boolean = false) : Request()

@Serializable
data class BoardReceipt(val revision: Long? = null, val error: String? = null, val key: String? = null, val created: Map<String, String> = emptyMap()) : Response()

interface BoardSource {
    val changes: Flow<BoardSnapshot>
    suspend fun read(): BoardSnapshot
    suspend fun change(request: BoardChange): BoardReceipt
    suspend fun select(request: BoardSelect)
}

open class BoardService : Service("reaktor.board") {
    open val read by post<BoardRead, BoardSnapshot>("/read", name = Read)
    open val change by post<BoardChange, BoardReceipt>("/change", name = Change)
    open val select by post<BoardSelect, BoardReceipt>("/select", name = Select)

    companion object {
        const val Read = "board.snapshot"
        const val Change = "board.change"
        const val Select = "board.select"
        const val Changed = "board.changed"
        val Operations = setOf(Read, Change, Select, Changed)
        operator fun invoke(source: BoardSource): Served<BoardService> = Served(object : BoardService() {
            override val read = super.read.handle { source.read() }
            override val change = super.change.handle { request -> source.change(request) }
            override val select = super.select.handle { request ->
                source.select(request)
                BoardReceipt()
            }
        })
    }
}
