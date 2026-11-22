package dev.shibasis.reaktor.widget

import dev.shibasis.reaktor.core.framework.Feature
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The slot is empty until a platform fills it, and an app reads back what it published. */
class WidgetBoardTest {
    private class Memory : WidgetBoard {
        val kept = mutableMapOf<String, String>()
        override fun publish(snapshot: String, key: String) { kept[key] = snapshot }
        override fun read(key: String) = kept[key]
    }

    @AfterTest
    fun empty() {
        Feature.Widgets = null
    }

    @Test
    fun emptyWhereThereAreNoWidgets() {
        assertNull(Feature.Widgets)
    }

    @Test
    fun publishedUnderTheDefaultKeyUnlessToldOtherwise() {
        val board = Memory()
        Feature.Widgets = board
        Feature.Widgets?.publish("""{"figure":"₹1,32,454"}""")
        Feature.Widgets?.publish("{}", key = "week")
        assertEquals("""{"figure":"₹1,32,454"}""", board.read())
        assertEquals("{}", board.read("week"))
        assertNull(board.read("other"))
    }
}
