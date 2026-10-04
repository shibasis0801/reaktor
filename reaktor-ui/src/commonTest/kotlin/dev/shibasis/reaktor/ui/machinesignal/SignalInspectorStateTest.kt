package dev.shibasis.reaktor.ui.machinesignal

import kotlin.test.*

class SignalInspectorStateTest {
    @Test fun pinnedIdentitySurvivesSelectionAndLayoutChangesUntilReleased() {
        val state = SignalInspectorState<String>()
        assertEquals("first", state.subject("first"))
        state.pin("first")
        state.collapsed = true
        assertEquals("first", state.subject("second"))
        state.reveal()
        assertFalse(state.collapsed)
        assertEquals("first", state.subject(null))
        state.followSelection()
        assertFalse(state.isPinned)
        assertEquals("second", state.subject("second"))
    }
}
