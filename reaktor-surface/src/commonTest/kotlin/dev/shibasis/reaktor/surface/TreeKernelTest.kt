package dev.shibasis.reaktor.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TreeKernelTest {
    private class Node(val key: String, val children: List<Node> = emptyList())

    private val kernel = CollectionKernel()
    private val tree = listOf(
        Node(
            "src",
            listOf(
                Node("main", listOf(Node("kotlin", listOf(Node("App.kt"), Node("Main.kt"))), Node("resources", listOf(Node("icon.png"))))),
                Node("test", listOf(Node("AppTest.kt"))),
            ),
        ),
        Node("README.md"),
    )
    private val files = treeSource(tree, { it.key }, { it.children }, setOf("src", "main", "kotlin"), text = { it.key })

    private fun properties(mode: SelectionMode = SelectionMode.Single, rightToLeft: Boolean = false) =
        CollectionProperties(files, emptySet(), KeyConvention.Mac, mode, rightToLeft)

    private fun at(active: String) = CollectionState(RovingState(active = active), files.indexOf(active), active)

    private fun press(key: KeyName, character: Char? = null) = CollectionInput.Stroke(KeyStroke(key, character = character), 10)

    private fun expansion(key: String, expanded: Boolean) = listOf<CollectionEvent>(CollectionEvent.ExpansionChange(key, expanded))

    private fun movedTo(key: String) = listOf<LocalCommand>(LocalCommand.Focus(PartKey(key)), LocalCommand.Reveal(PartKey(key)))

    @Test
    fun rightOpensAClosedRowThenMovesToItsFirstChild() {
        val opened = kernel.reduce(properties(), at("resources"), press(KeyName.Right))
        assertEquals(expansion("resources", true), opened.events)
        assertEquals(at("resources"), opened.state)
        assertTrue(opened.commands.isEmpty())
        val child = kernel.reduce(properties(), at("kotlin"), press(KeyName.Right))
        assertEquals("App.kt", child.state.active)
        assertEquals(listOf<CollectionEvent>(CollectionEvent.SelectionChange(setOf("App.kt"))), child.events)
        assertEquals(movedTo("App.kt"), child.commands)
        assertEquals(Reduction(at("App.kt")), kernel.reduce(properties(), at("App.kt"), press(KeyName.Right)))
    }

    @Test
    fun leftClosesAnOpenRowThenMovesToItsParent() {
        val closed = kernel.reduce(properties(), at("kotlin"), press(KeyName.Left))
        assertEquals(expansion("kotlin", false), closed.events)
        assertEquals(at("kotlin"), closed.state)
        assertEquals("main", kernel.reduce(properties(), at("resources"), press(KeyName.Left)).state.active)
        assertEquals(expansion("src", false), kernel.reduce(properties(), at("src"), press(KeyName.Left)).events)
        assertEquals(Reduction(at("README.md")), kernel.reduce(properties(), at("README.md"), press(KeyName.Left)))
    }

    @Test
    fun aDeepRowMovesToItsParentAndNotToThePreviousRow() {
        val up = kernel.reduce(properties(), at("Main.kt"), press(KeyName.Left))
        assertEquals("kotlin", up.state.active)
        assertEquals(2, up.state.activeIndex)
        assertEquals(movedTo("kotlin"), up.commands)
        assertEquals("src", kernel.reduce(properties(), at("test"), press(KeyName.Left)).state.active)
        assertEquals(3, files.depth(files.indexOf("Main.kt")))
    }

    @Test
    fun rightToLeftSwapsLeftAndRight() {
        val rtl = properties(rightToLeft = true)
        assertEquals(expansion("resources", true), kernel.reduce(rtl, at("resources"), press(KeyName.Left)).events)
        assertEquals("App.kt", kernel.reduce(rtl, at("kotlin"), press(KeyName.Left)).state.active)
        assertEquals(expansion("kotlin", false), kernel.reduce(rtl, at("kotlin"), press(KeyName.Right)).events)
        assertEquals("kotlin", kernel.reduce(rtl, at("Main.kt"), press(KeyName.Right)).state.active)
    }

    @Test
    fun theTreeKeysWorkTheSameInEveryMode() {
        SelectionMode.entries.forEach { mode ->
            assertEquals(expansion("resources", true), kernel.reduce(properties(mode), at("resources"), press(KeyName.Right)).events)
            assertEquals(expansion("kotlin", false), kernel.reduce(properties(mode), at("kotlin"), press(KeyName.Left)).events)
            val child = kernel.reduce(properties(mode), at("kotlin"), press(KeyName.Right))
            assertEquals("App.kt", child.state.active)
            val selected = child.events.filterIsInstance<CollectionEvent.SelectionChange>().singleOrNull()?.selection
            assertEquals(if (mode == SelectionMode.None) null else setOf("App.kt"), selected)
        }
    }

    @Test
    fun spaceInASingleSelectionTreeOpensOrClosesTheActiveRow() {
        val space = press(KeyName.Space, ' ')
        assertTrue(kernel.handles(properties(), space.stroke))
        assertEquals(expansion("resources", true), kernel.reduce(properties(), at("resources"), space).events)
        assertEquals(expansion("kotlin", false), kernel.reduce(properties(), at("kotlin"), space).events)
        assertEquals(Reduction(at("App.kt")), kernel.reduce(properties(), at("App.kt"), space))
        assertFalse(kernel.handles(properties(SelectionMode.None), space.stroke))
        val marked = kernel.reduce(properties(SelectionMode.Multiple), at("resources"), space)
        assertEquals(listOf<CollectionEvent>(CollectionEvent.SelectionChange(setOf("resources"))), marked.events)
    }

    @Test
    fun theExpandInputTakesTheSamePathAsTheKeys() {
        val opened = kernel.reduce(properties(), at("App.kt"), CollectionInput.Expand("resources", true))
        assertEquals(expansion("resources", true), opened.events)
        assertEquals(at("App.kt"), opened.state)
        assertEquals(expansion("kotlin", false), kernel.reduce(properties(), at("App.kt"), CollectionInput.Expand("kotlin", false)).events)
        listOf(
            CollectionInput.Expand("kotlin", true),
            CollectionInput.Expand("test", false),
            CollectionInput.Expand("App.kt", true),
            CollectionInput.Expand("gone", true),
        ).forEach { assertEquals(Reduction(at("App.kt")), kernel.reduce(properties(), at("App.kt"), it)) }
        val off = properties().copy(enabled = false)
        assertTrue(kernel.reduce(off, at("App.kt"), CollectionInput.Expand("resources", true)).events.isEmpty())
        val list = properties().copy(items = listSource(listOf("resources"), { it }))
        assertTrue(kernel.reduce(list, CollectionState(RovingState(active = "resources"), 0), CollectionInput.Expand("resources", true)).events.isEmpty())
    }

    @Test
    fun leftAndRightBelongToTreesAndTakeNoModifiers() {
        val list = properties().copy(items = listSource(listOf("a", "b"), { it }))
        assertFalse(kernel.handles(list, KeyStroke(KeyName.Left)))
        assertFalse(kernel.handles(list, KeyStroke(KeyName.Right)))
        assertTrue(kernel.handles(properties(), KeyStroke(KeyName.Left)))
        assertTrue(kernel.handles(properties(), KeyStroke(KeyName.Right)))
        assertFalse(kernel.handles(properties(), KeyStroke(KeyName.Right, shift = true)))
        assertFalse(kernel.handles(properties(), KeyStroke(KeyName.Right, meta = true)))
        assertFalse(kernel.handles(properties(), KeyStroke(KeyName.Left, alt = true)))
    }
}
