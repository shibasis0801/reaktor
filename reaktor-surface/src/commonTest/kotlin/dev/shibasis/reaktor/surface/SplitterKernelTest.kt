package dev.shibasis.reaktor.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SplitterKernelTest {
    private val kernel = SplitterKernel()
    private val side = SplitterProperties(size = 300f, min = 200f, max = 460f, initial = 280f)

    private fun press(key: KeyName, shift: Boolean = false) = SplitterInput.Stroke(KeyStroke(key, shift = shift))

    private fun sizes(properties: SplitterProperties, input: SplitterInput, state: SplitterState = SplitterState()) =
        kernel.reduce(properties, state, input).events

    private fun resized(size: Float) = listOf(SizeChange(size, collapsed = false))

    @Test
    fun theArrowsOnTheSplittersAxisMoveItByOneStep() {
        assertEquals(resized(316f), sizes(side, press(KeyName.Right)))
        assertEquals(resized(284f), sizes(side, press(KeyName.Left)))
        assertEquals(emptyList(), sizes(side, press(KeyName.Up)))
        assertEquals(emptyList(), sizes(side, press(KeyName.Down)))
        val bottom = side.copy(axis = Axis.Vertical)
        assertEquals(resized(316f), sizes(bottom, press(KeyName.Down)))
        assertEquals(resized(284f), sizes(bottom, press(KeyName.Up)))
        assertEquals(emptyList(), sizes(bottom, press(KeyName.Left)))
        assertFalse(kernel.handles(bottom, KeyStroke(KeyName.Right)))
    }

    @Test
    fun anEndRegionAndRightToLeftTurnTheMovementAround() {
        val end = side.copy(reversed = true)
        assertEquals(resized(284f), sizes(end, press(KeyName.Right)))
        assertEquals(resized(316f), sizes(end, SplitterInput.Drag(-16f)))
        val mirrored = side.copy(rightToLeft = true)
        assertEquals(resized(284f), sizes(mirrored, press(KeyName.Right)))
        assertEquals(resized(316f), sizes(mirrored, SplitterInput.Drag(-16f)))
        assertEquals(resized(316f), sizes(mirrored.copy(reversed = true), press(KeyName.Right)))
        assertEquals(resized(316f), sizes(side.copy(axis = Axis.Vertical, rightToLeft = true), press(KeyName.Down)))
    }

    @Test
    fun homeAndEndGoToTheLimitsAndResetGoesBackToTheInitialSize() {
        assertEquals(resized(200f), sizes(side, press(KeyName.Home)))
        assertEquals(resized(460f), sizes(side, press(KeyName.End)))
        assertEquals(resized(280f), sizes(side, SplitterInput.Reset))
        assertEquals(emptyList(), sizes(side.copy(size = 280f), SplitterInput.Reset))
    }

    @Test
    fun movesClampToTheLimitsAndSayNothingWhenTheSizeStays() {
        assertEquals(resized(460f), sizes(side, SplitterInput.Drag(400f)))
        assertEquals(resized(200f), sizes(side, SplitterInput.Drag(-400f)))
        assertEquals(emptyList(), sizes(side.copy(size = 460f), press(KeyName.Right)))
        assertEquals(emptyList(), sizes(side.copy(size = 200f), press(KeyName.Home)))
        assertEquals(resized(200f), sizes(side.copy(min = 200f, max = 100f), SplitterInput.Drag(40f)))
    }

    @Test
    fun movesAddUpBeforeTheOwnerAnswersAndTheOwnersSizeLeadsAfterward() {
        val first = kernel.reduce(side, SplitterState(), SplitterInput.Drag(10f))
        assertEquals(resized(310f), first.events)
        val second = kernel.reduce(side, first.state, SplitterInput.Drag(10f))
        assertEquals(resized(320f), second.events)
        assertEquals(resized(304f), sizes(side, press(KeyName.Left), second.state))
        assertEquals(resized(300f), sizes(side, SplitterInput.Drag(-10f), first.state))
        val answered = kernel.reconcile(side.copy(size = 250f), second.state).state
        assertEquals(resized(266f), sizes(side.copy(size = 250f), press(KeyName.Right), answered))
    }

    @Test
    fun enterCollapsesACollapsibleRegionAndRestoresItsSize() {
        val collapsible = side.copy(collapsible = true)
        val collapsed = kernel.reduce(collapsible, SplitterState(), press(KeyName.Enter))
        assertEquals(listOf(SizeChange(0f, collapsed = true)), collapsed.events)
        assertTrue(collapsed.state.collapsed)
        val restored = kernel.reduce(collapsible.copy(size = 0f), collapsed.state, press(KeyName.Enter))
        assertEquals(resized(300f), restored.events)
        assertFalse(restored.state.collapsed)
        assertEquals(resized(216f), sizes(collapsible.copy(size = 0f), SplitterInput.Drag(216f), collapsed.state))
        assertEquals(resized(200f), sizes(collapsible.copy(size = 0f), press(KeyName.Right), collapsed.state))
        assertEquals(resized(280f), sizes(collapsible.copy(size = 0f), SplitterInput.Reset, collapsed.state))
        assertFalse(kernel.handles(side, KeyStroke(KeyName.Enter)))
        assertEquals(emptyList(), sizes(side, press(KeyName.Enter)))
    }

    @Test
    fun keysWithModifiersAreNotTheSplitters() {
        listOf(
            KeyStroke(KeyName.Right, shift = true),
            KeyStroke(KeyName.Right, meta = true),
            KeyStroke(KeyName.Home, control = true),
            KeyStroke(KeyName.End, alt = true),
            KeyStroke(KeyName.Tab),
            KeyStroke(KeyName.Escape),
        ).forEach { assertFalse(kernel.handles(side, it), "$it") }
        assertTrue(kernel.handles(side, KeyStroke(KeyName.Home)))
    }
}
