package dev.shibasis.reaktor.ui.material

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RippleTest {
    @Test
    fun ripplesOfTheSameColourAndShapeAreEqualSoTheirNodesAreKept() {
        assertEquals(materialRipple(Color.Black), materialRipple(Color.Black))
        assertEquals(materialRipple(Color.Black).hashCode(), materialRipple(Color.Black).hashCode())
        assertNotEquals(materialRipple(Color.Black), materialRipple(Color.White))
        assertNotEquals(materialRipple(Color.Black), materialRipple(Color.Black, bounded = false))
        assertNotEquals(materialRipple(Color.Black), materialRipple(Color.Black, radius = 20.dp))
    }

    @Test
    fun aRippleThatReadsItsColourIsTheSameRippleWhileItsProducerIs() {
        val ink = ColorProducer { Color.White }
        assertEquals(materialRipple(ink), materialRipple(ink))
        assertNotEquals(materialRipple(ink), materialRipple(ColorProducer { Color.White }))
    }
}
