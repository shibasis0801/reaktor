package dev.shibasis.reaktor.surface

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TreemapTest {
    @Test fun areasAreProportionalAndZeroEntriesHaveNoArea() {
        val input = listOf(TreemapValue("one", 1), TreemapValue("two", 2), TreemapValue("three", 3), TreemapValue("zero", 0))
        treemap(input, 600.0, 400.0).forEach { assertTrue(abs(it.width * it.height - 240000.0 * it.bytes / 6) < 0.000001) }
    }

    @Test fun rectanglesStayInsideBoundsAndDoNotOverlap() {
        val cells = treemap((1..80).map { TreemapValue("$it", (it * it).toLong()) }, 900.0, 240.0)
        cells.forEach { assertTrue(it.x >= 0 && it.y >= 0 && it.width >= 0 && it.height >= 0 && it.x + it.width <= 900.000001 && it.y + it.height <= 240.000001) }
        cells.forEachIndexed { index, a -> cells.drop(index + 1).forEach { b ->
            assertTrue(a.x + a.width <= b.x + 0.000001 || b.x + b.width <= a.x + 0.000001 || a.y + a.height <= b.y + 0.000001 || b.y + b.height <= a.y + 0.000001)
        } }
    }

    @Test fun placementAndInputOrderAreStable() {
        val values = listOf(TreemapValue("c", 3), TreemapValue("a", 5), TreemapValue("b", 5))
        assertEquals(treemap(values, 640.0, 320.0), treemap(values, 640.0, 320.0))
        assertEquals(values.map { it.key }, treemap(values, 640.0, 320.0).map { it.key })
    }

    @Test fun emptyAndSingleInputsHaveDefinedGeometry() {
        assertTrue(treemap(emptyList(), 10.0, 10.0).isEmpty())
        assertEquals(listOf(TreemapCell("only", 7, 0.0, 0.0, 90.0, 40.0)), treemap(listOf(TreemapValue("only", 7)), 90.0, 40.0))
        assertEquals(0.0, treemap(listOf(TreemapValue("zero", 0)), 90.0, 40.0).single().width)
        assertEquals(0.0, treemap(listOf(TreemapValue("hidden", 7)), 0.0, 40.0).single().height)
    }
}
