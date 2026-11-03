package dev.shibasis.reaktor.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoardLayoutTest {
    private fun card(key: String, within: String = "", after: List<String> = emptyList()) = BoardItem(key, key, "todo", within = within, after = after)
    private fun placed(items: List<BoardItem>) = BoardLayout.of(items).associateBy { it.key }

    @Test fun framesContainTheirChildrenAndFollowTheirDependenciesInReadingOrder() {
        val items = placed(listOf(card("store"), card("api"), card("model", "store"), card("checks", "store", listOf("model")), card("endpoint", "api", listOf("checks"))))
        val store = items.getValue("store").at!!
        val model = items.getValue("model").at!!
        val checks = items.getValue("checks").at!!
        val api = items.getValue("api").at!!
        assertTrue(model.x > store.x && model.y >= store.y + BoardGeometry.FrameHead)
        assertTrue(checks.x >= model.x + BoardGeometry.CardWidth)
        assertTrue(api.x >= checks.x + BoardGeometry.CardWidth || api.y >= checks.y + BoardGeometry.CardHeight + BoardGeometry.FramePad)
        assertTrue(items.getValue("endpoint").at!!.x > api.x)
    }

    @Test fun framesPackTowardTheAspectTheyAreGiven() {
        val frames = (0 until 12).flatMap { frame -> listOf(card("f$frame")) + (0 until (frame % 4 + 1) * 3).map { card("f$frame-$it", "f$frame") } }
        fun extent(aspect: Double): Double {
            val laid = BoardLayout.of(frames, aspect).filter { it.within.isNotEmpty() }
            val width = laid.maxOf { it.at!!.x } - laid.minOf { it.at!!.x } + BoardGeometry.CardWidth
            val height = laid.maxOf { it.at!!.y } - laid.minOf { it.at!!.y } + BoardGeometry.CardHeight
            return width / height
        }
        assertTrue(extent(1.0) < extent(2.5), "${extent(1.0)} vs ${extent(2.5)}")
        assertTrue(extent(2.5) in 1.25..5.0, "${extent(2.5)}")
    }

    @Test fun crossingReductionAlignsParallelPairs() {
        val items = placed(listOf(card("a"), card("b"), card("c", after = listOf("b")), card("d", after = listOf("a"))))
        val a = items.getValue("a").at!!
        val b = items.getValue("b").at!!
        val c = items.getValue("c").at!!
        val d = items.getValue("d").at!!
        assertTrue((a.y - b.y) * (d.y - c.y) >= 0)
        assertTrue(d.x > a.x && c.x > b.x)
    }

    @Test fun unrelatedCardsPackIntoBalancedRowsWithoutOverlap() {
        val items = BoardLayout.of((0 until 30).map { card("n$it") })
        val width = items.maxOf { it.at!!.x } - items.minOf { it.at!!.x } + BoardGeometry.CardWidth
        val height = items.maxOf { it.at!!.y } - items.minOf { it.at!!.y } + BoardGeometry.CardHeight
        assertTrue(width / height in 0.7..3.0, "$width x $height")
        items.forEachIndexed { index, item ->
            items.take(index).forEach { other ->
                val a = item.at!!
                val b = other.at!!
                assertTrue(a.x + BoardGeometry.CardWidth <= b.x || b.x + BoardGeometry.CardWidth <= a.x
                    || a.y + BoardGeometry.CardHeight <= b.y || b.y + BoardGeometry.CardHeight <= a.y, "${item.key} overlaps ${other.key}")
            }
        }
    }

    @Test fun aLongParallelRankWrapsWithinItsFrame() {
        val items = placed(listOf(card("frame"), card("start", "frame")) + (0 until 18).map { card("task$it", "frame", listOf("start")) })
        val tasks = items.values.filter { it.key.startsWith("task") }
        assertTrue(tasks.map { it.at!!.x }.distinct().size > 1)
        assertTrue(tasks.maxOf { it.at!!.y } - tasks.minOf { it.at!!.y } <= 4 * BoardGeometry.RowPitch)
        assertTrue(tasks.all { it.at!!.x > items.getValue("start").at!!.x })
    }

    @Test fun contentAndInputOrderDoNotMoveAutomaticPlaces() {
        val original = listOf(card("frame"), card("one", "frame"), card("two", "frame", listOf("one")), card("loose"))
        val changed = original.reversed().map { it.copy(title = "Renamed ${it.title}", lane = "done", footer = "Working: Codex", note = "New detail") }
        assertEquals(placed(original).mapValues { it.value.at }, placed(changed).mapValues { it.value.at })
        assertEquals(BoardPoint(42.0, 64.0), placed(original.map { if (it.key == "loose") it.copy(at = BoardPoint(42.0, 64.0)) else it }).getValue("loose").at)
    }
}
