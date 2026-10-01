package dev.shibasis.reaktor.surface

import kotlin.test.Test
import kotlin.test.assertEquals

class PresentationTest {
    @Test fun resizingOnlyChangesProjection() {
        val compact = presentationPlan("atlas", "source:42", SurfaceConstraints(390f, 800f))
        val expanded = presentationPlan("atlas", "source:42", SurfaceConstraints(1440f, 900f, hasFinePointer = true))
        assertEquals("atlas", compact.focusedEntryId)
        assertEquals("atlas", expanded.focusedEntryId)
        assertEquals(listOf(PaneRole.Primary), compact.panes.map { it.role })
        assertEquals(listOf(PaneRole.Navigation, PaneRole.Primary, PaneRole.Supporting), expanded.panes.map { it.role })
        assertEquals("source:42", expanded.panes.last().entryId)
    }

    @Test fun textScaleCanCollapseAConstrainedPane() {
        assertEquals(PaneLayout.Expanded, presentationPlan("learn", "lesson", SurfaceConstraints(1200f, 800f)).layout)
        assertEquals(PaneLayout.Medium, presentationPlan("learn", "lesson", SurfaceConstraints(1200f, 800f, 1.5f)).layout)
    }
}
