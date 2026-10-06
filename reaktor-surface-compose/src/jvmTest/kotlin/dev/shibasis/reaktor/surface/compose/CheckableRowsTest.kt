package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.shibasis.reaktor.surface.listSource
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CheckableRowsTest {
    private val checks = listOf("lint", "unit", "e2e")

    @Test
    fun spaceTogglesTheActiveCheckableRowOfASingleSelectList() = runComposeUiTest {
        var required by mutableStateOf(setOf("lint"))
        var selection by mutableStateOf(setOf("lint"))
        var passedThrough = 0
        setContent {
            Column(Modifier.onKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Spacebar) passedThrough++; false }) {
                AutomationScope("checks") {
                    ListBox(
                        listSource(checks, { it }, text = { it }, checked = { it in required }),
                        selection,
                        { selection = it },
                        Modifier.height(200.dp),
                        onCheckedChange = { key, checked -> required = if (checked) required + key else required - key },
                    ) { BasicText(it) }
                }
            }
        }
        onNodeWithTag("checks/row/lint").requestFocus()
        onNodeWithTag("checks/row/lint").assertIsOn()
        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        onNodeWithTag("checks/row/lint").assertIsOff().assertIsSelected()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("checks/row/unit").assertIsFocused().assertIsSelected().assertIsOff()
        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        onNodeWithTag("checks/row/unit").assertIsOn().assertIsSelected()
        assertEquals(setOf("unit"), required)
        assertEquals(setOf("unit"), selection)
        assertEquals(0, passedThrough)
    }

    @Test
    fun spacePassesThroughARowThatIsNotCheckable() = runComposeUiTest {
        var passedThrough = 0
        setContent {
            Column(Modifier.onKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Spacebar) passedThrough++; false }) {
                AutomationScope("checks") {
                    ListBox(listSource(checks, { it }, text = { it }, checked = { if (it == "e2e") null else false }), emptySet(), {}, Modifier.height(200.dp)) { BasicText(it) }
                }
            }
        }
        onNodeWithTag("checks/row/lint").requestFocus()
        onRoot().performKeyInput { pressKey(Key.MoveEnd) }
        onNodeWithTag("checks/row/e2e").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(1, passedThrough)
    }
}
