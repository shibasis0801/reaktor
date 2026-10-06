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
    fun collapsingTheBranchThatHoldsTheActiveRowMakesTheBranchActive() {
        val collapsed = kernel.reduce(properties(), at("Main.kt"), CollectionInput.Expand("main", false))
        assertEquals(expansion("main", false), collapsed.events)
        assertEquals("main", collapsed.state.active)
        assertEquals(1, collapsed.state.activeIndex)
        assertEquals(movedTo("main"), collapsed.commands)
        assertEquals("src", kernel.reduce(properties(), at("App.kt"), CollectionInput.Expand("src", false)).state.active)
        val sibling = kernel.reduce(properties(), at("test"), CollectionInput.Expand("main", false))
        assertEquals(expansion("main", false), sibling.events)
        assertEquals(at("test"), sibling.state)
        assertTrue(sibling.commands.isEmpty())
        assertEquals(at("main"), kernel.reduce(properties(), at("main"), CollectionInput.Expand("main", false)).state)
    }

    @Test
    fun aRowThatCannotBeSelectedTakesFocusButNeverTheSelectionOrAMenu() {
        val headed = treeSource(tree, { it.key }, { it.children }, setOf("src", "main", "kotlin"), text = { it.key }, selectable = { it.children.isEmpty() })
        val single = CollectionProperties(headed, setOf("App.kt"), KeyConvention.Mac, SelectionMode.Single)
        val at = { key: String -> CollectionState(RovingState(active = key), headed.indexOf(key), key, within = true) }
        val up = kernel.reduce(single, at("App.kt"), press(KeyName.Up))
        assertEquals("kotlin", up.state.active)
        assertTrue(up.events.isEmpty())
        assertEquals(movedTo("kotlin"), up.commands)
        val left = kernel.reduce(single, at("App.kt"), press(KeyName.Left))
        assertEquals("kotlin", left.state.active)
        assertTrue(left.events.isEmpty())
        val clicked = kernel.reduce(single, at("App.kt"), CollectionInput.Press("main", extend = false, toggle = false, clicks = 1))
        assertEquals("main", clicked.state.active)
        assertTrue(clicked.events.isEmpty())
        assertEquals(listOf<CollectionEvent>(CollectionEvent.Activate("main")), kernel.reduce(single, at("main"), CollectionInput.Press("main", extend = false, toggle = false, clicks = 2)).events)
        assertEquals(listOf<CollectionEvent>(CollectionEvent.Activate("main")), kernel.reduce(single, at("main"), press(KeyName.Enter)).events)
        val secondary = kernel.reduce(single, at("App.kt"), CollectionInput.Secondary("main", atPointer = true))
        assertEquals("main", secondary.state.active)
        assertTrue(secondary.events.isEmpty())
        assertTrue(kernel.reduce(single, at("main"), CollectionInput.Stroke(KeyStroke(KeyName.F10, shift = true), 10)).events.isEmpty())
        assertEquals(expansion("main", false), kernel.reduce(single, at("main"), press(KeyName.Space, ' ')).events)
        assertEquals(listOf<CollectionEvent>(CollectionEvent.MenuRequest("Main.kt", atPointer = false)),
            kernel.reduce(single, at("Main.kt"), CollectionInput.Stroke(KeyStroke(KeyName.F10, shift = true), 10)).events)
        val multiple = single.copy(mode = SelectionMode.Multiple, selection = setOf("App.kt"))
        assertEquals(expansion("kotlin", false), kernel.reduce(multiple, at("kotlin"), press(KeyName.Space, ' ')).events)
        val range = kernel.reduce(multiple, at("App.kt"), CollectionInput.Press("resources", extend = true, toggle = false, clicks = 1))
        assertEquals(setOf("App.kt", "Main.kt"), range.events.filterIsInstance<CollectionEvent.SelectionChange>().single().selection)
        val all = kernel.reduce(multiple, at("App.kt"), CollectionInput.Stroke(KeyStroke(KeyName.A, meta = true, character = 'a'), 10))
        assertEquals(setOf("App.kt", "Main.kt", "README.md"), all.events.filterIsInstance<CollectionEvent.SelectionChange>().single().selection)
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
