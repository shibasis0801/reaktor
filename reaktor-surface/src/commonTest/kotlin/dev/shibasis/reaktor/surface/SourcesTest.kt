package dev.shibasis.reaktor.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourcesTest {
    private class Node(val key: String, val children: List<Node> = emptyList())

    private val tree = listOf(
        Node("a", listOf(Node("a1", listOf(Node("a1x"))), Node("a2"))),
        Node("b", listOf(Node("b1"))),
        Node("c"),
    )

    @Test
    fun aListSourceBuildsItsKeyIndexOnTheFirstLookupAndKeepsIt() {
        var keyed = 0
        val source = listSource((0 until 1000).toList(), { "item-$it".also { keyed++ } })
        assertEquals(1000, source.size)
        assertEquals(42, source[42])
        assertEquals("item-7", source.key(7))
        assertEquals(1, keyed)
        assertEquals(500, source.indexOf("item-500"))
        assertEquals(1001, keyed)
        assertEquals(3, source.indexOf("item-3"))
        assertEquals(-1, source.indexOf("missing"))
        assertEquals(1001, keyed)
        assertTrue(source.enabled(0))
        assertNull(source.text(0))
    }

    @Test
    fun aListSourceReadsTextAndEnabledFromItsItems() {
        val source = listSource(listOf("Alpha", "beta"), { it.lowercase() }, text = { it }, enabled = { it.first().isUpperCase() })
        assertEquals(listOf("alpha", "beta"), (0 until source.size).map(source::key))
        assertEquals("Alpha", source.text(0))
        assertEquals(listOf(true, false), (0 until source.size).map(source::enabled))
        assertEquals(listOf(true, true), (0 until source.size).map(source::selectable))
        val headed = treeSource(tree, { it.key }, { it.children }, setOf("a"), selectable = { it.children.isEmpty() })
        assertEquals(listOf(false, false, true, false, true), (0 until headed.size).map(headed::selectable))
    }

    @Test
    fun aTreeSourceFlattensOnlyTheExpandedBranches() {
        val visited = mutableListOf<String>()
        val source = treeSource(tree, { it.key }, { node -> node.children.also { visited += node.key } }, expanded = setOf("a"))
        assertEquals(listOf("a"), visited)
        val rows = 0 until source.size
        assertEquals(listOf("a", "a1", "a2", "b", "c"), rows.map(source::key))
        assertEquals(listOf(0, 1, 1, 0, 0), rows.map(source::depth))
        assertEquals(listOf(-1, 0, 0, -1, -1), rows.map(source::parent))
        assertEquals(listOf(true, false, false, false, false), rows.map(source::expanded))
        assertEquals(listOf(true, true, false, true, false), rows.map(source::expandable))
        assertEquals("a2", source[2].key)
        assertEquals(3, source.indexOf("b"))
        assertEquals(-1, source.indexOf("a1x"))
    }

    @Test
    fun expandingABranchAddsItsRowsAndAHiddenExpandedBranchStaysHidden() {
        val deeper = treeSource(tree, { it.key }, { it.children }, setOf("a", "a1", "b"))
        val rows = 0 until deeper.size
        assertEquals(listOf("a", "a1", "a1x", "a2", "b", "b1", "c"), rows.map(deeper::key))
        assertEquals(listOf(0, 1, 2, 1, 0, 1, 0), rows.map(deeper::depth))
        assertEquals(listOf(-1, 0, 1, 0, -1, 4, -1), rows.map(deeper::parent))
        val hidden = treeSource(tree, { it.key }, { it.children }, setOf("a1"))
        assertEquals(listOf("a", "b", "c"), (0 until hidden.size).map(hidden::key))
    }
}
