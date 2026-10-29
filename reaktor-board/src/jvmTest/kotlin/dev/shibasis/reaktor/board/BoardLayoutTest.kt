package dev.shibasis.reaktor.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoardLayoutTest {
    private fun card(key: String, within: String = "", after: List<String> = emptyList()) = BoardItem(key, key, "todo", within = within, after = after)
    private fun placed(items: List<BoardItem>) = BoardLayout.of(items).associateBy { it.key }

    @Test fun framesContainTheirChildrenAndDependenciesPointForward() {
        val items = placed(listOf(card("store"), card("api"), card("model", "store"), card("checks", "store", listOf("model")), card("endpoint", "api", listOf("checks"))))
        val store = items.getValue("store").at!!
        val model = items.getValue("model").at!!
        val checks = items.getValue("checks").at!!
        val api = items.getValue("api").at!!
        assertTrue(model.x > store.x && model.y >= store.y + BoardGeometry.FrameHead)
        assertTrue(checks.x >= model.x + BoardGeometry.CardWidth)
        assertTrue(api.x >= checks.x + BoardGeometry.CardWidth)
        assertTrue(items.getValue("endpoint").at!!.x > api.x)
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
