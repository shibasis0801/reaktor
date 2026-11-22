package dev.shibasis.reaktor.blueprint

import androidx.compose.ui.graphics.Color
import dev.shibasis.reaktor.blueprint.product.SurfaceGraphSymbol
import kotlin.test.Test
import kotlin.test.assertEquals

class BlueprintElapsedTimeTest {
    @Test fun elapsedTimeChangesOnlyThePaintedSubtitleAndNamesAnExpiredLease() {
        val look = BlueprintCardLook(Color.Blue, SurfaceGraphSymbol, "Build it", "builder", heldSince = 60000, leaseUntil = 300000)
        assertEquals("builder · 1m", look.atTime(120000).subtitle)
        assertEquals("builder · 2m", look.atTime(180000).subtitle)
        assertEquals("builder · lease lapsed", look.atTime(300000).subtitle)
        assertEquals("builder", look.subtitle)
    }
}
