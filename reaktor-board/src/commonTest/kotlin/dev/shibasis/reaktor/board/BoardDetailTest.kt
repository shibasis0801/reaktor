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
        val small = board.scene(BoardFrame(board.items, positions, detail = BoardDetail.Titles))
        assertTrue(small.size < full.size)
        assertTrue(small.any { it.id == BoardIds.card(item.key) })
        val labels = small.filterIsInstance<BoardLabel>().map { it.text }
        assertTrue(item.title in labels)
        assertTrue(item.badge in labels)
        assertTrue(item.footer in labels)
    }

    @Test fun selectedCardsKeepTheirFullDetailAtOverviewZoom() {
        BoardDetail.entries.forEach { detail ->
            assertEquals(board.scene(BoardFrame(board.items, positions, selected = item.key)), board.scene(BoardFrame(board.items, positions, selected = item.key, detail = detail)))
        }
    }

    @Test fun overviewKeepsEveryDependencyAndRelation() {
        val next = BoardItem("next", "Read storage", "doing", after = listOf(item.key), relations = listOf(BoardRelation(item.key, "reads")))
        val linked = board.copy(items = board.items + next)
        val placed = positions + (next.key to BoardPoint(400.0, 0.0))
        val full = linked.scene(BoardFrame(linked.items, placed)).filterIsInstance<BoardArrow>()
        BoardDetail.entries.forEach { detail -> assertEquals(full, linked.scene(BoardFrame(linked.items, placed, detail = detail)).filterIsInstance<BoardArrow>()) }
    }

    @Test fun aDistantViewDrawsEachCardAsOneBlockInItsStatusColour() {
        val theme = BoardTheme()
        val card = board.scene(BoardFrame(board.items, positions, detail = BoardDetail.Marks), theme).single() as BoardBox
        assertEquals(BoardIds.card(item.key), card.id)
        assertEquals(theme.ink(BoardInk.Busy), card.fill)
    }

    @Test fun zoomPicksTheTierThatStillReads() {
        assertEquals(listOf(BoardDetail.Marks, BoardDetail.Marks, BoardDetail.Titles, BoardDetail.Full, BoardDetail.Full),
            listOf(0.05, 0.39, 0.4, 0.85, 2.0).map(BoardDetail::at))
    }

    @Test fun aWindowLeavesOutWhatLiesBeyondItAndKeepsArrowsThatCrossIt() {
        val near = BoardItem("near", "Near", "doing")
        val far = BoardItem("far", "Far", "doing", after = listOf(near.key))
        val beyond = BoardItem("beyond", "Beyond", "doing")
        val wide = board.copy(items = listOf(near, far, beyond))
        val at = mapOf(near.key to BoardPoint(0.0, 0.0), far.key to BoardPoint(3000.0, 0.0), beyond.key to BoardPoint(9000.0, 9000.0))
        val ids = wide.groups(BoardFrame(wide.items, at, window = BoardRect(0.0, 0.0, 1000.0, 1000.0))).map { it.id }
        assertEquals(listOf(BoardIds.edge(near.key, far.key), BoardIds.card(near.key)), ids)
        assertEquals(wide.groups(BoardFrame(wide.items, at)).size, wide.groups(BoardFrame(wide.items, at, window = BoardRect(-1.0, -1.0, 10000.0, 10000.0))).size)
    }
}
