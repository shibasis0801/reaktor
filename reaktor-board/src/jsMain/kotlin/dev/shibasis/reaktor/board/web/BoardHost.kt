package dev.shibasis.reaktor.board.web

import dev.shibasis.reaktor.board.Board
import dev.shibasis.reaktor.board.BoardEdit
import dev.shibasis.reaktor.board.BoardItem
import dev.shibasis.reaktor.board.BoardPoint
import dev.shibasis.reaktor.board.BoardTheme

class BoardKit(val canvas: dynamic, val convert: dynamic, val neverCapture: dynamic)

data class BoardOutcome(val problem: String? = null, val key: String? = null)

data class BoardOption(val value: String, val label: String)

data class BoardField(
    val id: String,
    val label: String,
    val value: String = "",
    val options: List<BoardOption> = emptyList(),
    val placeholder: String = "",
    val limit: Int = 120,
    val caption: Boolean = false,
    val fixed: Boolean = false,
)

data class BoardSync(val errors: List<String> = emptyList(), val pending: Boolean = false, val local: Boolean = false)

interface BoardHost {
    val places: Boolean get() = false
    fun canLeave(): Boolean = true
    fun select(key: String) {}
    fun open(key: String) = select(key)
    fun refer(prerequisite: BoardItem, item: BoardItem): String? = null
    fun edit(edit: BoardEdit, before: BoardItem?): BoardOutcome
    fun reveal(key: String) {}
    fun defaults(after: BoardItem?): Map<String, String> = emptyMap()
    fun fields(after: BoardItem?, values: Map<String, String>): List<BoardField> = emptyList()
}

class BoardDetails(
    val item: BoardItem,
    val canvas: BoardCanvas,
    val focus: Int,
    val guard: dynamic,
    val activity: dynamic,
    val dirty: (Boolean) -> Unit,
    val edit: () -> Unit,
)

class BoardSlots(
    val details: ((BoardDetails) -> dynamic)? = null,
    val detailsClass: String = "",
    val closeDetails: () -> Unit = {},
    val detailsRequest: Int = 0,
    val detailsPinned: Boolean = false,
    val index: ((BoardItem) -> dynamic)? = null,
    val indexClass: String = "",
    val tagClass: String = "",
    val graph: (((String) -> Unit) -> dynamic)? = null,
    val tabs: Boolean = true,
    val empty: String = "",
)

class BoardModel(
    val board: Board,
    val shown: List<BoardItem>,
    val host: BoardHost,
    val kit: BoardKit,
    val layout: String,
    val active: String? = null,
    val view: dev.shibasis.reaktor.board.BoardView? = null,
    val sync: BoardSync = BoardSync(),
    val theme: BoardTheme = BoardTheme(),
    val slots: BoardSlots = BoardSlots(),
    val label: String = "",
    val onView: (dev.shibasis.reaktor.board.BoardView) -> Unit = {},
    val canvas: BoardCanvas? = null,
)

data class BoardCreation(val id: Int, val at: BoardPoint, val after: String?, val lane: String, val fields: Map<String, String>, val editing: BoardItem? = null)
