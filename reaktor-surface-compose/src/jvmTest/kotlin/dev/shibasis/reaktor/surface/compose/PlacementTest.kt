package dev.shibasis.reaktor.surface.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class PlacementTest {
    private val canvas = IntSize(1000, 800)
    private val menu = IntSize(200, 300)
    private val button = IntRect(400, 100, 480, 130)

    private fun at(anchor: IntRect, side: Side, align: Align, gap: Int = 6, content: IntSize = menu, direction: LayoutDirection = LayoutDirection.Ltr) =
        place(anchor, content, canvas, side, align, gap, direction)

    @Test
    fun theDefaultsOpenBelowAndEndAligned() {
        assertEquals(IntOffset(280, 136), at(button, Side.Below, Align.End))
        assertEquals(IntOffset(400, 130), at(button, Side.Below, Align.Start, gap = 0))
        assertEquals(IntOffset(340, 136), at(button, Side.Below, Align.Center))
    }

    @Test
    fun aMenuWithNoRoomBelowFlipsAbove() {
        val low = IntRect(400, 600, 480, 630)
        assertEquals(IntOffset(280, 294), at(low, Side.Below, Align.End))
        assertEquals(IntOffset(280, 136), at(button, Side.Above, Align.End))
    }

    @Test
    fun anAlignmentWithNoRoomTakesTheOppositeOneBeforeClamping() {
        val nearRight = IntRect(900, 100, 980, 130)
        assertEquals(IntOffset(780, 130), at(nearRight, Side.Below, Align.Start, gap = 0))
        val nearLeft = IntRect(10, 100, 90, 130)
        assertEquals(IntOffset(10, 136), at(nearLeft, Side.Below, Align.End))
    }

    @Test
    fun aMenuThatFitsNowhereIsClampedIntoTheCanvas() {
        val tall = IntSize(200, 900)
        assertEquals(IntOffset(280, 0), at(button, Side.Below, Align.End, content = tall))
        val wide = IntSize(1200, 300)
        assertEquals(0, at(button, Side.Below, Align.Start, content = wide).x)
    }

    @Test
    fun rightToLeftMirrorsAlignmentAndTheInlineSides() {
        assertEquals(IntOffset(400, 136), at(button, Side.Below, Align.End, direction = LayoutDirection.Rtl))
        assertEquals(IntOffset(280, 130), at(button, Side.Below, Align.Start, gap = 0, direction = LayoutDirection.Rtl))
        assertEquals(IntOffset(200, 100), at(button, Side.End, Align.Start, gap = 0, direction = LayoutDirection.Rtl))
        assertEquals(IntOffset(480, 100), at(button, Side.Start, Align.Start, gap = 0, direction = LayoutDirection.Rtl))
    }

    @Test
    fun aSubmenuOpensBesideItsItemAndFlipsAtTheEdge() {
        val item = IntRect(600, 200, 800, 240)
        assertEquals(IntOffset(800, 200), at(item, Side.End, Align.Start, gap = 0))
        val edge = IntRect(820, 200, 990, 240)
        assertEquals(IntOffset(620, 200), at(edge, Side.End, Align.Start, gap = 0))
        val bottom = IntRect(600, 700, 800, 740)
        assertEquals(IntOffset(800, 440), at(bottom, Side.End, Align.Start, gap = 0))
    }

    @Test
    fun aPointAnchorPutsTheMenuCornerAtThePointer() {
        val pointer = OverlayAnchor.Point(Offset(300.4f, 250.6f)).window()
        assertEquals(IntRect(300, 251, 300, 251), pointer)
        assertEquals(IntOffset(300, 251), at(pointer, Side.Below, Align.Start, gap = 0))
        assertEquals(IntOffset(750, 251), at(OverlayAnchor.Point(Offset(950f, 251f)).window(), Side.Below, Align.Start, gap = 0))
        assertEquals(IntRect(10, 20, 110, 70), OverlayAnchor.Bounds(Rect(10.2f, 19.8f, 110.2f, 69.8f)).window())
    }
}
