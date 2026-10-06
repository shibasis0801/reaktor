package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shibasis.reaktor.surface.Ink
import dev.shibasis.reaktor.surface.InkRole
import dev.shibasis.reaktor.surface.TextRole
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TextTest {
    private val asked = mutableListOf<Pair<TextRole?, InkRole?>>()
    private val chosen = TextSelectionColors(Color.Red, Color.Red.copy(alpha = .4f))

    private val recording = object : TextAppearance {
        @Composable
        override fun style(role: TextRole?, ink: InkRole?, theme: ThemeSnapshot): TextStyle {
            asked += role to ink
            return TextStyle(fontSize = if (role == Type.Title) 20.sp else 10.sp, color = if (ink == Ink.Danger) Color.Red else Color.Black)
        }

        @Composable
        override fun selection(theme: ThemeSnapshot) = chosen
    }

    private fun layout(tag: String, test: androidx.compose.ui.test.ComposeUiTest): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        test.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    @Test
    fun theAppearanceResolvesTheRoleAndInkItIsGiven() = runComposeUiTest {
        setContent {
            SurfaceTheme(BareTheme, Appearances(Appearance.Text provides recording)) {
                Text("Deploys", Modifier.testTag("titled"), Type.Title, Ink.Danger)
                Text("inherited", Modifier.testTag("inherited"))
            }
        }
        waitForIdle()
        assertTrue((Type.Title to Ink.Danger) in asked)
        assertTrue((null to null) in asked)
        assertEquals(20.sp, layout("titled", this).layoutInput.style.fontSize)
        assertEquals(Color.Red, layout("titled", this).layoutInput.style.color)
        assertEquals(10.sp, layout("inherited", this).layoutInput.style.fontSize)
    }

    @Test
    fun aTextKeepsToItsLinesAndEndsInAnEllipsis() = runComposeUiTest {
        val long = "a long sentence that cannot fit in a narrow column of text at all"
        setContent {
            SurfaceTheme(BareTheme, Appearances(Appearance.Text provides recording)) {
                Text(long, Modifier.width(40.dp).testTag("one"))
                Text(long, Modifier.width(40.dp).testTag("two"), lines = 2)
                Text("end", Modifier.width(120.dp).testTag("aligned"), align = TextAlign.End)
            }
        }
        waitForIdle()
        assertEquals(1, layout("one", this).lineCount)
        assertEquals(TextOverflow.Ellipsis, layout("one", this).layoutInput.overflow)
        assertEquals(2, layout("two", this).lineCount)
        assertEquals(TextAlign.End, layout("aligned", this).layoutInput.style.textAlign)
        onNodeWithTag("one").assertTextEquals(long)
    }

    @Test
    fun theTextAppearanceChoosesTheSelectionColours() = runComposeUiTest {
        var themed: TextSelectionColors? = null
        var bare: TextSelectionColors? = null
        var outside: TextSelectionColors? = null
        setContent {
            outside = LocalTextSelectionColors.current
            SurfaceTheme(BareTheme, Appearances(Appearance.Text provides recording)) { themed = LocalTextSelectionColors.current }
            SurfaceTheme(BareTheme, Appearances()) { bare = LocalTextSelectionColors.current }
        }
        waitForIdle()
        assertEquals(chosen, themed)
        assertEquals(outside, bare)
    }
}
