package dev.shibasis.reaktor.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GridKernelTest {
    private val properties = GridProperties(listSource((0..99).map { "r$it" }, { it }),
        listSource((0..59).map { "c$it" }, { it }), KeyConvention.Mac)

    @Test fun arrowsStayWithinTheGridAndMirrorOnlyTheHorizontalAxis() {
        for (rtl in listOf(false, true)) {
            val p = properties.copy(rightToLeft = rtl)
            val state = GridState(GridCell("r5", "c5"))
            val right = GridKernel.reduce(p, state, GridInput.Stroke(KeyStroke(KeyName.Right)))
            assertEquals(GridCell("r5", if (rtl) "c4" else "c6"), right.state.active)
            assertEquals(right.state.active, right.state.anchor)
            assertTrue(right.events.isEmpty())
            assertEquals(listOf(LocalCommand.Focus(right.state.active!!.part), LocalCommand.Reveal(right.state.active!!.part)), right.commands)
            assertEquals(GridCell("r6", "c5"), GridKernel.reduce(p, state, GridInput.Stroke(KeyStroke(KeyName.Down))).state.active)
        }
        val first = GridKernel.initial(properties)
        assertEquals(first, GridKernel.reduce(properties, first, GridInput.Stroke(KeyStroke(KeyName.Left))).state)
        assertEquals(first, GridKernel.reduce(properties, first, GridInput.Stroke(KeyStroke(KeyName.Up))).state)
        val last = GridState(GridCell("r99", "c59"))
        assertEquals(last, GridKernel.reduce(properties, last, GridInput.Stroke(KeyStroke(KeyName.Right))).state)
        assertEquals(last, GridKernel.reduce(properties, last, GridInput.Stroke(KeyStroke(KeyName.Down))).state)
    }

    @Test fun shiftFormsARectangleAndReversesAroundTheStableAnchor() {
        val start = GridState(GridCell("r5", "c5"))
        val across = GridKernel.reduce(properties, start, GridInput.Stroke(KeyStroke(KeyName.Right, shift = true))).state
        val below = GridKernel.reduce(properties, across, GridInput.Stroke(KeyStroke(KeyName.Down, shift = true))).state
        assertEquals(GridCell("r5", "c5"), below.anchor)
        assertEquals(5..6 to 5..6, below.range(properties))
        val above = (1..2).fold(below) { state, _ -> GridKernel.reduce(properties, state, GridInput.Stroke(KeyStroke(KeyName.Up, shift = true))).state }
        assertEquals(4..5 to 5..6, above.range(properties))
        val collapsed = GridKernel.reduce(properties, above, GridInput.Stroke(KeyStroke(KeyName.Left))).state
        assertEquals(GridCell("r4", "c5"), collapsed.active)
        assertEquals(4..4 to 5..5, collapsed.range(properties))
    }

    @Test fun homeEndAndPageUseTheRowGridAndViewportBoundaries() {
        val start = GridState(GridCell("r50", "c30"))
        assertEquals(GridCell("r50", "c0"), GridKernel.reduce(properties, start, GridInput.Stroke(KeyStroke(KeyName.Home))).state.active)
        assertEquals(GridCell("r50", "c59"), GridKernel.reduce(properties, start, GridInput.Stroke(KeyStroke(KeyName.End))).state.active)
        assertEquals(GridCell("r0", "c0"), GridKernel.reduce(properties, start, GridInput.Stroke(KeyStroke(KeyName.Home, meta = true))).state.active)
        assertEquals(GridCell("r99", "c59"), GridKernel.reduce(properties, start, GridInput.Stroke(KeyStroke(KeyName.End, meta = true))).state.active)
        assertEquals(GridCell("r67", "c30"), GridKernel.reduce(properties, start, GridInput.Stroke(KeyStroke(KeyName.PageDown), 17)).state.active)
        assertEquals(GridCell("r33", "c30"), GridKernel.reduce(properties, start, GridInput.Stroke(KeyStroke(KeyName.PageUp), 17)).state.active)
        assertEquals(GridCell("r99", "c30"), GridKernel.reduce(properties, start, GridInput.Stroke(KeyStroke(KeyName.PageDown), Int.MAX_VALUE)).state.active)
    }

    @Test fun copyAndActivationAreIntentsAndMatchThePlatformConvention() {
        val state = GridState(GridCell("r5", "c5"), GridCell("r3", "c2"))
        for (keys in KeyConvention.entries) {
            val p = properties.copy(keys = keys)
            val copy = KeyStroke(KeyName.C, meta = keys == KeyConvention.Mac, control = keys == KeyConvention.Pc)
            assertTrue(GridKernel.handles(p, copy))
            assertEquals(listOf(GridEvent.Copy((3..5).map { "r$it" }, (2..5).map { "c$it" })), GridKernel.reduce(p, state, GridInput.Stroke(copy)).events)
            assertFalse(GridKernel.handles(p, copy.copy(shift = true)))
            assertFalse(GridKernel.handles(p, KeyStroke(KeyName.C)))
            assertFalse(GridKernel.handles(p, KeyStroke(KeyName.C, meta = keys == KeyConvention.Pc, control = keys == KeyConvention.Mac)))
        }
        assertEquals(listOf(GridEvent.Activate(state.active!!)), GridKernel.reduce(properties, state, GridInput.Stroke(KeyStroke(KeyName.Enter))).events)
        val clicked = GridCell("r8", "c7")
        val point = GridKernel.reduce(properties, state, GridInput.Point(clicked))
        assertEquals(GridState(clicked), point.state)
        assertEquals(listOf(GridEvent.Activate(clicked)), point.events)
        val focused = GridKernel.reduce(properties, state, GridInput.Focused(state.active!!))
        assertEquals(state, focused.state)
        assertTrue(focused.events.isEmpty()); assertTrue(focused.commands.isEmpty())
    }

    @Test fun stableCellKeysRetainTheRangeAcrossSortAndReconcileDeletedRowsAndColumns() {
        val state = GridState(GridCell("r5", "c5"), GridCell("r3", "c2"))
        val sorted = properties.copy(rows = listSource((99 downTo 0).map { "r$it" }, { it }))
        assertEquals(state, GridKernel.reconcile(sorted, state).state)
        assertEquals(94..96 to 2..5, state.range(sorted))
        val filtered = properties.copy(rows = listSource(listOf("r5", "r8"), { it }))
        assertEquals(GridState(GridCell("r5", "c5")), GridKernel.reconcile(filtered, state).state)
        val removed = properties.copy(columns = listSource(listOf("c0", "c1"), { it }))
        assertEquals(GridState(GridCell("r0", "c0")), GridKernel.reconcile(removed, state).state)
        assertNull(state.range(removed))
        val empty = properties.copy(rows = listSource(emptyList<String>(), { it }))
        assertEquals(GridState(), GridKernel.reconcile(empty, state).state)
        assertTrue(GridKernel.reduce(empty, state, GridInput.Stroke(KeyStroke(KeyName.Enter))).events.isEmpty())
    }

    @Test fun disabledCellsAreSkippedAndInvalidPointerIntentsDoNothing() {
        val p = GridProperties(listSource(listOf("r0", "r1", "r2"), { it }, enabled = { it != "r1" }),
            listSource(listOf("c0", "c1", "c2"), { it }, enabled = { it != "c1" }), KeyConvention.Mac)
        val first = GridKernel.initial(p)
        assertEquals(GridCell("r2", "c0"), GridKernel.reduce(p, first, GridInput.Stroke(KeyStroke(KeyName.Down))).state.active)
        assertEquals(GridCell("r0", "c2"), GridKernel.reduce(p, first, GridInput.Stroke(KeyStroke(KeyName.Right))).state.active)
        for (cell in listOf(GridCell("r1", "c0"), GridCell("r0", "c1"), GridCell("missing", "c0"))) {
            assertEquals(Reduction(first), GridKernel.reduce(p, first, GridInput.Point(cell)))
        }
        assertEquals(GridState(GridCell("r0", "c0")), GridKernel.reconcile(p, GridState(GridCell("r1", "c1"))).state)
    }

    @Test fun cellIdentityDoesNotCollideWhenKeysContainSeparators() {
        assertTrue(GridCell("a/b", "c").part != GridCell("a", "b/c").part)
        assertTrue(GridCell("a", "bc").part != GridCell("ab", "c").part)
    }
}
