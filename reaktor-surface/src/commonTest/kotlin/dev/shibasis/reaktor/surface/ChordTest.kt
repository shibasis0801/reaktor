package dev.shibasis.reaktor.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChordTest {
    private val mac = KeyConvention.Mac
    private val pc = KeyConvention.Pc
    private val find = Chord.Of(KeyName.F, primary = true)
    private val palette = Chord.Of(KeyName.K, primary = true, shift = true)
    private val back = Chord.Of(KeyName.Left, alt = true)
    private val nextDocument = Chord.Of(KeyName.Tab, control = true)
    private val fullScreen = Chord.Of(KeyName.F, primary = true, control = true)

    @Test
    fun macLabelsUseApplesGlyphsInTheirOrder() {
        assertEquals("⌘F", find.label(mac))
        assertEquals("⇧⌘K", palette.label(mac))
        assertEquals("⌃⌘F", fullScreen.label(mac))
        assertEquals("⌥←", back.label(mac))
        assertEquals("⌃⇥", nextDocument.label(mac))
        assertEquals("⌃⌥⇧⌘S", Chord.Of(KeyName.S, primary = true, shift = true, alt = true, control = true).label(mac))
        assertEquals("⌘1", Chord.Of(KeyName.Digit1, primary = true).label(mac))
        assertEquals("⇧⎋", Chord.Of(KeyName.Escape, shift = true).label(mac))
    }

    @Test
    fun pcLabelsSpellTheModifiersOut() {
        assertEquals("Ctrl+F", find.label(pc))
        assertEquals("Ctrl+Shift+K", palette.label(pc))
        assertEquals("Ctrl+Alt+Shift+S", Chord.Of(KeyName.S, primary = true, shift = true, alt = true).label(pc))
        assertEquals("Alt+Left", back.label(pc))
        assertEquals("Ctrl+Tab", nextDocument.label(pc))
        assertEquals("Ctrl+[", Chord.Of(KeyName.LeftBracket, primary = true).label(pc))
        assertEquals("F11", Chord.Of(KeyName.F11).label(pc))
        assertEquals("Shift+Esc", Chord.Of(KeyName.Escape, shift = true).label(pc))
    }

    @Test
    fun primaryIsCommandOnMacAndControlElsewhere() {
        assertTrue(find.matches(KeyStroke(KeyName.F, meta = true), mac))
        assertFalse(find.matches(KeyStroke(KeyName.F, control = true), mac))
        assertTrue(find.matches(KeyStroke(KeyName.F, control = true), pc))
        assertFalse(find.matches(KeyStroke(KeyName.F, meta = true), pc))
    }

    @Test
    fun matchingIsExact() {
        assertFalse(find.matches(KeyStroke(KeyName.F, meta = true, shift = true), mac))
        assertFalse(find.matches(KeyStroke(KeyName.F, meta = true, control = true), mac))
        assertFalse(find.matches(KeyStroke(KeyName.G, meta = true), mac))
        assertTrue(find.matches(KeyStroke(KeyName.F, meta = true, character = 'f'), mac))
        assertTrue(nextDocument.matches(KeyStroke(KeyName.Tab, control = true), mac))
        assertFalse(nextDocument.matches(KeyStroke(KeyName.Tab, meta = true), mac))
        assertTrue(fullScreen.matches(KeyStroke(KeyName.F, meta = true, control = true), mac))
    }

    @Test
    fun aPlatformChordPicksItsConvention() {
        val platform = Chord.Platform(mac = fullScreen, pc = Chord.Of(KeyName.F11))
        assertEquals(fullScreen, platform.on(mac))
        assertEquals("⌃⌘F", platform.label(mac))
        assertEquals("F11", platform.label(pc))
        assertTrue(platform.matches(KeyStroke(KeyName.F11), pc))
        assertFalse(platform.matches(KeyStroke(KeyName.F11), mac))
        val macOnly = Chord.Platform(mac = find, pc = null)
        assertNull(macOnly.on(pc))
        assertEquals("", macOnly.label(pc))
        assertFalse(macOnly.matches(KeyStroke(KeyName.F, control = true), pc))
    }

    @Test
    fun primaryAndControlCollapseOnPcAndClashWithPrimaryAlone() {
        val set = CommandSet(
            listOf(
                Command(CommandId("full-screen"), "Enter Full Screen", fullScreen),
                Command(CommandId("find"), "Find", find),
            ),
        )
        assertEquals(emptyList(), set.findings(mac))
        assertEquals(
            listOf(
                ChordFinding.Clash(fullScreen, listOf(CommandId("full-screen"), CommandId("find"))),
                ChordFinding.Collapsed(CommandId("full-screen")),
            ),
            set.findings(pc),
        )
    }

    @Test
    fun twoCommandsOnOneChordClash() {
        val set = CommandSet(
            listOf(
                Command(CommandId("search"), "Search", Chord.Of(KeyName.K, primary = true)),
                Command(CommandId("palette"), "Palette", Chord.Of(KeyName.K, primary = true)),
                Command(CommandId("back"), "Back", back),
            ),
        )
        val expected = listOf(ChordFinding.Clash(Chord.Of(KeyName.K, primary = true), listOf(CommandId("search"), CommandId("palette"))))
        assertEquals(expected, set.findings(mac))
        assertEquals(expected, set.findings(pc))
    }
}
