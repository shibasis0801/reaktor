package dev.shibasis.reaktor.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoardDetailTest {
    private val item = BoardItem("task", "Implement storage", "doing", badge = "In progress", eyebrow = "Task", mark = BoardInk.Busy, initials = "CO", footer = "Working: Codex")
    private val board = Board(listOf(BoardLane("doing", "In progress")), listOf(item))
    private val positions = mapOf(item.key to BoardPoint(0.0, 0.0))

    @Test fun overviewKeepsIdentityStatusAndWorkerWithFewerElements() {
        val full = board.scene(BoardFrame(board.items, positions))
        val small = board.scene(BoardFrame(board.items, positions, detail = false))
        assertTrue(small.size < full.size)
        assertTrue(small.any { it.id == BoardIds.card(item.key) })
        val labels = small.filterIsInstance<BoardLabel>().map { it.text }
        assertTrue(item.title in labels)
        assertTrue(item.badge in labels)
        assertTrue(item.footer in labels)
    }

    @Test fun selectedCardsKeepTheirFullDetailAtOverviewZoom() {
        assertEquals(board.scene(BoardFrame(board.items, positions, selected = item.key)), board.scene(BoardFrame(board.items, positions, selected = item.key, detail = false)))
    }

    @Test fun overviewKeepsEveryDependencyAndRelation() {
        val next = BoardItem("next", "Read storage", "doing", after = listOf(item.key), relations = listOf(BoardRelation(item.key, "reads")))
        val linked = board.copy(items = board.items + next)
        val placed = positions + (next.key to BoardPoint(400.0, 0.0))
        val full = linked.scene(BoardFrame(linked.items, placed)).filterIsInstance<BoardArrow>()
        assertEquals(full, linked.scene(BoardFrame(linked.items, placed, detail = false)).filterIsInstance<BoardArrow>())
    }
}
