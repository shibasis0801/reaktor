package dev.shibasis.reaktor.surface.compose

import dev.shibasis.reaktor.surface.ThemeMismatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppearancesTest {
    private val badge = AppearanceKey("badge", "bare badge")

    @Test
    fun namedSlotsKeepTheirLooksAndEverythingElseStaysBare() {
        val styledButton: ButtonAppearance = object : ButtonAppearance by BareButton {}
        val styled = Appearances(button = styledButton, toast = BareToast)
        assertSame(styledButton, styled.button)
        assertSame(BareButton, styled.menuItem)
        assertSame(BarePanel, styled.dialog)
        assertSame(BareListRow, styled[Appearance.ListRow])
    }

    @Test
    fun aKeyNoThemeSetsDrawsItsBareDefault() {
        assertEquals("bare badge", Appearances()[badge])
        assertFalse(badge in Appearances())
    }

    @Test
    fun anotherModuleAddsItsOwnSlotWithoutEditingSurface() {
        val themed = Appearances() + (badge provides "dense badge")
        assertEquals("dense badge", themed[badge])
        assertTrue(badge in themed)
        assertSame(BareButton, themed.button)
    }

    @Test
    fun theRightHandSideWinsWhenSetsAreCombined() {
        val styledButton: ButtonAppearance = object : ButtonAppearance by BareButton {}
        val base = Appearances(badge provides "base", Appearance.Button provides styledButton)
        val override = Appearances(badge provides "override")
        val merged = base + override
        assertEquals("override", merged[badge])
        assertSame(styledButton, merged.button)
    }

    @Test
    fun equalSetsAreEqual() {
        assertEquals(Appearances(), Appearances(button = BareButton))
        assertEquals(Appearances().hashCode(), Appearances(button = BareButton).hashCode())
    }

    @Test
    fun theOverlayKeysStartBare() {
        val bare = Appearances()
        assertSame(BareSeparator, bare[Appearance.Separator])
        assertSame(BareTooltip, bare[Appearance.Tooltip])
        assertSame(BarePanel, bare[Appearance.Popover])
        assertFalse(Appearance.Popover in Appearances(dialog = BarePanel))
    }

    @Test
    fun aMismatchNamesBothThemes() {
        val mismatch = ThemeMismatch("Machine Signal", "human-signal-light")
        assertEquals("A Machine Signal appearance was drawn under the theme 'human-signal-light'", mismatch.message)
    }
}
