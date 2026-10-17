package dev.shibasis.reaktor.media.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfReaderStateTest {
    @Test fun restoredPageIsClampedToTheActualDocument() {
        val state = PdfReaderState(30)
        state.loaded(3, true)
        assertEquals(2, state.state.value.page)
        state.goToPage(3)
        assertNull(state.commands.value)
    }

    @Test fun repeatedNavigationIsStillDeliveredAndSelectionCannotCrossDocuments() {
        val state = PdfReaderState()
        state.loaded(3, true)
        state.selected(PdfSelection("passage", listOf(PdfRegion(1, 0f, 0f, 10f, 10f))))
        state.goToPage(2)
        val first = state.commands.value!!.first
        state.goToPage(2)
        assertTrue(state.commands.value!!.first > first)
        assertNull(state.state.value.selection)
        state.selected(PdfSelection("outside", listOf(PdfRegion(8, 0f, 0f, 10f, 10f))))
        assertNull(state.state.value.selection)
    }
}
