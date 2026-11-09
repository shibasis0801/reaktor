package dev.shibasis.reaktor.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContrastTest {
    private fun near(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 0.01, "$actual, expected $expected")

    @Test
    fun knownPairs() {
        near(21.0, contrastRatio(Color.Black, Color.White))
        near(1.0, contrastRatio(Color.White, Color.White))
        // WCAG's own example: #767676 on white is the lightest grey that passes 4.5:1.
        near(4.54, contrastRatio(Color(0xFF767676), Color.White))
    }

    @Test
    fun translucentInkIsCompositedFirst() {
        val half = Color.Black.copy(alpha = 0.5f)
        near(contrastRatio(Color(0xFF7F7F7F), Color.White), contrastRatio(half, Color.White))
    }

    @Test
    fun checkCollectsEveryFailure() {
        val check = ContrastCheck()
        check.require("grey", Color(0xFF999999), Color.White, 4.5)
        check.require("black", Color.Black, Color.White, 7.0)
        check.require("silver", Color(0xFFCCCCCC), Color.White, 3.0)
        assertEquals(2, check.problems.size, check.problems.toString())
    }
}
