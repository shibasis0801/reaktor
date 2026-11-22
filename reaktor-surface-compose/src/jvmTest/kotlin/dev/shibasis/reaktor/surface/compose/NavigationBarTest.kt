package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.PressState
import dev.shibasis.reaktor.surface.ThemeSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NavigationBarTest {
    private val places = listOf("ledger" to "Ledger", "months" to "Months", "settings" to "Settings")

    @Composable
    private fun Bar(selected: String?, onSelectedChange: (String) -> Unit, enabled: Boolean = true) = Box(Modifier.width(312.dp)) {
        NavigationBar(selected, onSelectedChange, enabled = enabled, insets = WindowInsets(0)) {
            places.forEach { (key, label) -> Item(key, icon = { BasicText("•") }, label = { BasicText(label) }, modifier = Modifier.testTag(key)) }
        }
    }

    @Test
    fun choosingAnItemReportsItsKeyAndMarksItAsTheSelectedTab() = runComposeUiTest {
        var selected by mutableStateOf("ledger")
        setContent { Bar(selected, { selected = it }) }
        onNodeWithTag("ledger").assertIsSelected()
        onNodeWithTag("months").assertIsNotSelected().performClick()
        assertEquals("months", selected)
        onNodeWithTag("months").assertIsSelected()
        onNodeWithTag("ledger").assertIsNotSelected()
        assertEquals(Role.Tab, onNodeWithTag("settings").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
    }

    @Test
    fun itemsShareTheBarsWidthEqually() = runComposeUiTest {
        setContent { Bar("ledger", {}) }
        val widths = places.map { (key, _) -> onNodeWithTag(key).getBoundsInRoot().let { it.right - it.left } }
        // Equal shares, give or take the pixel a row hands out when the width doesn't divide.
        assertTrue(widths.max() - widths.min() <= 1.dp, "$widths")
        // The bare bar spaces its items 8 dp apart: three items and two gaps fill 312 dp.
        assertEquals(312.dp, widths[0] + widths[1] + widths[2] + 16.dp)
    }

    @Test
    fun arrowKeysMoveFocusAndEnterChooses() = runComposeUiTest {
        var selected by mutableStateOf("ledger")
        val chosen = mutableListOf<String>()
        setContent { Bar(selected, { selected = it; chosen += it }) }
        onNodeWithTag("ledger").requestFocus()
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithTag("months").assertIsFocused().assertIsNotSelected()
        assertEquals(emptyList(), chosen)
        onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("months"), chosen)
    }

    @Test
    fun aDisabledBarChoosesNothing() = runComposeUiTest {
        val chosen = mutableListOf<String>()
        setContent { Bar("ledger", { chosen += it }, enabled = false) }
        onNodeWithTag("settings").performClick()
        assertEquals(emptyList(), chosen)
    }

    @Test
    fun theThemesAppearancesDrawTheBarAndEachItem() = runComposeUiTest {
        val drawn = mutableListOf<String>()
        val looks = Appearances(
            Appearance.NavigationBar provides object : NavigationBarAppearance {
                @Composable
                override fun Content(properties: NavigationBarProperties, state: Unit, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: NavigationBarSlots) {
                    drawn += "bar:${properties.enabled}"
                    Row(content = slots.items)
                }
            },
            Appearance.NavigationItem provides object : ItemAppearance {
                @Composable
                override fun Content(properties: ItemProperties, state: PressState, theme: ThemeSnapshot, feedback: ComposeFeedback, slots: ItemSlots) {
                    drawn += "item:${properties.selected}:${slots.icon != null}"
                    slots.content()
                }
            },
        )
        setContent { CompositionLocalProvider(LocalAppearances provides looks) { Bar("months", {}) } }
        waitForIdle()
        assertEquals(listOf("bar:true", "item:false:true", "item:true:true", "item:false:true"), drawn)
    }
}
