package dev.shibasis.reaktor.board

import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.contract.Contract
import dev.shibasis.reaktor.service.contract.Link
import dev.shibasis.reaktor.service.contract.Post
import dev.shibasis.reaktor.service.contract.Served
import dev.shibasis.reaktor.service.contract.serve
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
}

@Serializable
class BoardRead : Request()

@Serializable
data class BoardChange(val edit: BoardEdit, val revision: Long? = null) : Request()

@Serializable
data class BoardSelect(val key: String? = null, val open: Boolean = false) : Request()

@Serializable
data class BoardReceipt(val revision: Long? = null, val error: String? = null, val key: String? = null) : Response()

interface BoardSource {
    val changes: Flow<BoardSnapshot>
    suspend fun read(): BoardSnapshot
    suspend fun change(request: BoardChange): BoardReceipt
    suspend fun select(request: BoardSelect)
}

interface BoardService {
    suspend fun read(request: BoardRead): BoardSnapshot
    suspend fun change(request: BoardChange): BoardReceipt
    suspend fun select(request: BoardSelect): BoardReceipt

    companion object : Contract<BoardService>("reaktor.board") {
        const val Read = "board.snapshot"
        const val Change = "board.change"
        const val Select = "board.select"
        const val Changed = "board.changed"
        val Operations = setOf(Read, Change, Select, Changed)

        val read = call(BoardService::read, Post("/read"), name = Read)
        val change = call(BoardService::change, Post("/change"), name = Change)
        val select = call(BoardService::select, Post("/select"), name = Select)

        operator fun invoke(source: BoardSource): Served<BoardService> = serve(object : BoardService {
            override suspend fun read(request: BoardRead) = source.read()
            override suspend fun change(request: BoardChange) = source.change(request)
            override suspend fun select(request: BoardSelect): BoardReceipt {
                source.select(request)
                return BoardReceipt()
            }
        })

        override fun client(link: Link): BoardService = object : BoardService {
            override suspend fun read(request: BoardRead) = link(read, request)
            override suspend fun change(request: BoardChange) = link(change, request)
            override suspend fun select(request: BoardSelect) = link(select, request)
        }
    }
}
