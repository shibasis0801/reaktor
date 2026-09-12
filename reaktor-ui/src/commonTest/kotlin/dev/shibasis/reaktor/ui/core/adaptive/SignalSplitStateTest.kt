package dev.shibasis.reaktor.ui.core.adaptive

import dev.shibasis.reaktor.ui.machinesignal.*
import kotlin.test.*

class SignalSplitStateTest {
    @Test fun shrinkingCannotProduceNegativePanesOrAnInvalidRange() {
        for (extent in listOf(0f, 1f, 64f, 390f, 900f, 3200f)) {
            for (request in listOf(-1f, 0f, .42f, 1f, 5f, Float.NaN)) {
                val fraction = constrainedSplitFraction(request, extent, 280f, 320f)
                assertTrue(fraction in 0f..1f)
                if (extent >= 600) {
                    assertTrue(fraction * extent >= 279.99f)
                    assertTrue((1 - fraction) * extent >= 319.99f)
                }
            }
        }
    }

    @Test fun temporaryWindowCompressionDoesNotEraseTheChosenProportion() {
        val state = SignalSplitState(.42f)
        state.fraction = .7f
        val compact = constrainedSplitFraction(state.fraction, 390f, 280f, 320f)
        assertTrue(compact < .5f)
        assertEquals(.7f, constrainedSplitFraction(state.fraction, 2400f, 280f, 320f))
        state.reset()
        assertEquals(.42f, state.fraction)
    }
}
