package dev.shibasis.reaktor.blueprint

import androidx.compose.ui.graphics.Color
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BlueprintCardActionContractTest {
    @Test
    fun copyingSelectionRetainsTheNamedActionAndItsOriginalCallback() {
        val invoked = mutableListOf<String>()
        val open = BlueprintCardAction("open", "Open source", SurfaceGraphSymbol) { invoked += "source" }
        val original = BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, "Source", "Book", actions = listOf(open))
        val selected = original.copy(selected = true)
        assertSame(open, selected.actions.single())
        selected.actions.single().onInvoke()
        assertEquals(listOf("source"), invoked)
        assertEquals("Open source", selected.actions.single().label)
        assertEquals("open", selected.actions.single().id)
        assertTrue(original.actions.single().icon === SurfaceGraphSymbol)
    }

    @Test
    fun independentCardsCanShareAnActionIdWithoutSharingTheTarget() {
        val invoked = mutableListOf<String>()
        val actions = listOf("source-a", "source-b").map { id ->
            BlueprintCardAction("open", "Open $id", SurfaceGraphSymbol) { invoked += id }
        }
        actions[1].onInvoke()
        actions[0].onInvoke()
        assertEquals(listOf("source-b", "source-a"), invoked)
    }
}
