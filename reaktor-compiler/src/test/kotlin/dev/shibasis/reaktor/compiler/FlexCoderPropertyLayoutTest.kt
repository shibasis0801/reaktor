package dev.shibasis.reaktor.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FlexCoderPropertyLayoutTest {
    @Test
    fun wireNamesDriveUtf8OrderAndPreencodedKeyBlock() {
        val layout = buildFlexPropertyLayout(
            listOf(
                FlexPropertyName(sourceName = "lastInSource", wireName = "alpha"),
                FlexPropertyName(sourceName = "firstInSource", wireName = "zeta"),
                FlexPropertyName(sourceName = "unicodeSource", wireName = "éclair"),
            ),
        )

        assertEquals(
            listOf("lastInSource", "firstInSource", "unicodeSource"),
            layout.properties.map(FlexPropertyName::sourceName),
        )
        assertEquals(listOf("alpha", "zeta", "éclair"), layout.properties.map(FlexPropertyName::wireName))
        assertEquals(listOf(0, 6, 11), layout.keyStarts)
        assertEquals("alpha\u0000zeta\u0000éclair\u0000", layout.keysLiteral)
        assertFalse(layout.keysLiteral.contains("InSource"))
    }

    @Test
    fun duplicateWireNamesAreRejectedBeforeGeneratingAmbiguousPositionalCode() {
        val error = assertFailsWith<IllegalArgumentException> {
            buildFlexPropertyLayout(
                listOf(
                    FlexPropertyName(sourceName = "oldName", wireName = "stable_name"),
                    FlexPropertyName(sourceName = "newName", wireName = "stable_name"),
                ),
            )
        }

        assertEquals("Duplicate FlexBuffer wire names: stable_name", error.message)
    }

    @Test
    fun sourceIdentifiersAreEscapedIndependentlyFromWireNames() {
        assertEquals("ordinary", renderKotlinIdentifier("ordinary"))
        assertEquals("`when`", renderKotlinIdentifier("when"))
        assertEquals("`display name`", renderKotlinIdentifier("display name"))
    }
}
