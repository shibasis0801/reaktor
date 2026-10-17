package dev.shibasis.reaktor.ui.core.adaptive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthoredLayoutTest {
    @Test fun flexibleSlotsFillTheWindowWithoutScalingControlsOrLosingTheFooter() {
        val toolbar = AuthoredLayoutNode("toolbar", AuthoredBounds(0f, 0f, 1000f, 70f),
            axis = AuthoredAxis.Horizontal, fillWidth = true, children = listOf(
                AuthoredLayoutNode("project", AuthoredBounds(12f, 20f, 100f, 28f)),
                AuthoredLayoutNode("elastic", AuthoredBounds(124f, 20f, 664f, 28f), fillWidth = true),
                AuthoredLayoutNode("run", AuthoredBounds(800f, 20f, 188f, 28f)),
            ))
        val root = AuthoredLayoutNode("window", AuthoredBounds(0f, 0f, 1000f, 800f), AuthoredAxis.Vertical,
            children = listOf(toolbar,
                AuthoredLayoutNode("body", AuthoredBounds(0f, 70f, 1000f, 706f), fillWidth = true, fillHeight = true),
                AuthoredLayoutNode("footer", AuthoredBounds(0f, 776f, 1000f, 24f), fillWidth = true)))
        for ((w, h) in listOf(1600f to 1000f, 700f to 600f)) {
            val layout = root.adaptTo(w, h)
            assertEquals(AuthoredBounds(12f, 20f, 100f, 28f), layout.bounds.getValue("project"))
            assertEquals(w - 200f, layout.bounds.getValue("run").x)
            assertEquals(70f, layout.bounds.getValue("toolbar").height)
            assertEquals(h - 24f, layout.bounds.getValue("footer").y)
            assertEquals(w, layout.width)
            assertEquals(h, layout.height)
        }
    }

    @Test fun constrainedSlotsStopAtTheirIntrinsicMinimumWithoutOverlappingSiblings() {
        val child = AuthoredLayoutNode("fixed", AuthoredBounds(0f, 0f, 100f, 30f))
        val root = AuthoredLayoutNode("root", AuthoredBounds(0f, 0f, 500f, 100f), AuthoredAxis.Horizontal,
            children = listOf(
                AuthoredLayoutNode("a", AuthoredBounds(0f, 0f, 200f, 30f), AuthoredAxis.Horizontal, fillWidth = true, children = listOf(child)),
                AuthoredLayoutNode("gap", AuthoredBounds(200f, 0f, 100f, 30f), fillWidth = true),
                AuthoredLayoutNode("b", AuthoredBounds(300f, 0f, 200f, 30f)),
            ))
        val result = root.adaptTo(100f, 100f)
        assertEquals(300f, result.width)
        assertEquals(100f, result.bounds.getValue("a").width)
        assertEquals(0f, result.bounds.getValue("gap").width)
        assertEquals(100f, result.bounds.getValue("b").x)
        assertTrue(result.bounds.values.all { it.width >= 0f && it.height >= 0f })
    }

    @Test fun authoredCoordinatesAndAbsoluteDiagramPositionsArePreserved() {
        val pin = AuthoredLayoutNode("pin", AuthoredBounds(-2.5f, 8.25f, 5f, 5f), absolute = true)
        val canvas = AuthoredLayoutNode("canvas", AuthoredBounds(0f, 0f, 100f, 100f), fillWidth = true, fillHeight = true, children = listOf(pin))
        val root = AuthoredLayoutNode("root", AuthoredBounds(0.5f, 0.5f, 100f, 100f), AuthoredAxis.Vertical, children = listOf(canvas))
        assertEquals(root.bounds, root.adaptTo(101f, 101f).bounds.getValue("root"))
        assertEquals(pin.bounds, root.adaptTo(201f, 301f).bounds.getValue("pin"))
    }

    @Test fun declaredContentFloorsStopCompressionAndOverflowTheOwnerInstead() {
        fun card(id: String, x: Float, floor: Float) = AuthoredLayoutNode(id, AuthoredBounds(x, 0f, 260f, 100f),
            AuthoredAxis.Vertical, fillWidth = true, padding = AuthoredInsets(0f, 18f, 0f, 18f), children = listOf(
                AuthoredLayoutNode("$id/label", AuthoredBounds(18f, 18f, 224f, 22f), fillWidth = true, minWidth = floor)))
        val row = AuthoredLayoutNode("row", AuthoredBounds(0f, 0f, 800f, 100f), AuthoredAxis.Horizontal,
            fillWidth = true, children = listOf(card("a", 0f, 118f), card("b", 260f, 96f), card("c", 520f, 0f)))

        val squeezed = row.adaptTo(360f, 100f)
        // The floors are honoured, the floorless card absorbs what is left, and the row overflows.
        assertEquals(154f, squeezed.bounds.getValue("a").width)
        assertEquals(132f, squeezed.bounds.getValue("b").width)
        assertEquals(54f, squeezed.bounds.getValue("c").width, absoluteTolerance = 0.001f)
        assertEquals(118f, squeezed.bounds.getValue("a/label").width)
        assertEquals(360f, squeezed.width)

        // A floor is a floor, not a size: nothing changes while the window can pay for the authored width.
        assertEquals(row.adaptTo(800f, 100f).bounds, row.copy(children = row.children.map {
            it.copy(children = it.children.map { child -> child.copy(minWidth = 0f) })
        }).adaptTo(800f, 100f).bounds)
    }

    @Test fun aContentFloorNeverGrowsALayoutBeyondItsAuthoredSize() {
        val label = AuthoredLayoutNode("label", AuthoredBounds(0f, 0f, 120f, 20f), fillWidth = true, minWidth = 400f)
        val root = AuthoredLayoutNode("root", AuthoredBounds(0f, 0f, 120f, 20f), AuthoredAxis.Horizontal,
            fillWidth = true, children = listOf(label))
        val layout = root.adaptTo(40f, 20f)
        assertEquals(120f, layout.width)
        assertEquals(120f, layout.bounds.getValue("label").width)
    }
}
