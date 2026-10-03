package dev.shibasis.reaktor.ui.code

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class CodeViewTest {
    private val source = "{\n  \"worker\": \"messaging-service\",\n  \"replicas\": 2\n}"

    @Test
    fun copyTakesTheWholeTextWhenNothingIsSelectedAndFindOpensTheFindBar() = runComposeUiTest {
        val clipboard = HeldClipboard()
        setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                CodeView(source, Modifier.size(600.dp, 240.dp).testTag("view"), tag = "json")
            }
        }
        onNodeWithTag("view").performCustomAccessibilityActionWithLabel("Copy")
        assertEquals(source, clipboard.held)
        onNodeWithTag("json-find-query").assertDoesNotExist()
        onNodeWithTag("view").performCustomAccessibilityActionWithLabel("Find")
        onNodeWithTag("json-find-query").assertExists()
    }

    @Test
    fun escapeThenTabLeavesTheView() = runComposeUiTest {
        setContent {
            Column {
                CodeView(source, Modifier.size(600.dp, 200.dp), tag = "json")
                Box(Modifier.size(20.dp).testTag("after").focusable())
            }
        }
        onNodeWithTag("json-surface").requestFocus()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        onNodeWithTag("json-surface").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("after").assertIsFocused()
    }

    private class HeldClipboard : ClipboardManager {
        var held: String? = null

        override fun setText(annotatedString: AnnotatedString) {
            held = annotatedString.text
        }

        override fun getText(): AnnotatedString? = held?.let(::AnnotatedString)
    }
}
