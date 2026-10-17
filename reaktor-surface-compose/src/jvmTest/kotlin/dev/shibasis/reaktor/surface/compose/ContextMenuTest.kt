package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.roundToIntRect
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ContextMenuTest {
    private var scope: ContextMenuScope? = null
    private val chosen = mutableListOf<String>()

    @Composable
    private fun Rows() = SurfaceEnvironmentProvider(SurfaceEnvironment()) {
        Box(Modifier.requiredSize(800.dp, 600.dp)) {
            ContextMenu {
                scope = this
                Area(Modifier.padding(40.dp)) {
                    Column {
                        Button({}, Modifier.testTag("orders")) { BasicText("orders") }
                        Button({}, Modifier.testTag("customers")) { BasicText("customers") }
                    }
                }
                Popup {
                    Item("copy", { chosen += "copy" }, Modifier.testTag("copy")) { BasicText("Copy name") }
                    Item("browse", { chosen += "browse" }, Modifier.testTag("browse")) { BasicText("Browse rows") }
                }
            }
        }
    }

    private fun ComposeUiTest.panelCorner(): IntOffset {
        val item = onNodeWithTag("copy").fetchSemanticsNode().boundsInWindow.roundToIntRect()
        val inset = with(density) { 8.dp.roundToPx() }
        return IntOffset(item.left - inset, item.top - inset)
    }

    @Test
    fun aSecondaryPressOpensTheMenuAtThePointer() = runComposeUiTest {
        setContent { Rows() }
        val customers = onNodeWithTag("customers").fetchSemanticsNode().boundsInWindow
        onNodeWithTag("customers").performMouseInput { rightClick(Offset(12f, 6f)) }
        onNodeWithTag("copy").assertIsFocused()
        assertEquals((customers.topLeft + Offset(12f, 6f)).round(), panelCorner())
        onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals(listOf("copy"), chosen)
        onNodeWithTag("copy").assertDoesNotExist()
    }

    @Test
    fun shiftF10OpensTheMenuBelowTheFocusedElementAndEscapeReturnsToIt() = runComposeUiTest {
        setContent { Rows() }
        onNodeWithTag("customers").requestFocus()
        val customers = onNodeWithTag("customers").fetchSemanticsNode().boundsInWindow.roundToIntRect()
        onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) } }
        onNodeWithTag("copy").assertIsFocused()
        assertEquals(IntOffset(customers.left, customers.bottom), panelCorner())
        onRoot().performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag("copy").assertDoesNotExist()
        onNodeWithTag("customers").assertIsFocused()
    }

    @Test
    fun openAtPlacesTheMenuAtAPointInsideTheArea() = runComposeUiTest {
        setContent { Rows() }
        val area = onNodeWithTag("orders").fetchSemanticsNode().boundsInWindow
        runOnIdle { requireNotNull(scope).openAt(Offset(30f, 20f)) }
        onNodeWithTag("copy").assertIsFocused()
        assertEquals((area.topLeft + Offset(30f, 20f)).round(), panelCorner())
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("browse").assertIsFocused()
    }
}
