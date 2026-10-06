package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.shibasis.reaktor.surface.InkRole
import dev.shibasis.reaktor.surface.TextRole
import dev.shibasis.reaktor.surface.ThemeSnapshot

interface TextAppearance {
    @Composable
    fun style(role: TextRole?, ink: InkRole?, theme: ThemeSnapshot): TextStyle

    @Composable
    fun selection(theme: ThemeSnapshot): TextSelectionColors? = null
}

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    role: TextRole? = null,
    ink: InkRole? = null,
    lines: Int = 1,
    align: TextAlign = TextAlign.Unspecified,
    appearance: TextAppearance = LocalAppearances.current[Appearance.Text],
) = BasicText(text, modifier, appearance.resolved(role, ink, align), overflow = TextOverflow.Ellipsis, maxLines = lines)

@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    role: TextRole? = null,
    ink: InkRole? = null,
    lines: Int = 1,
    align: TextAlign = TextAlign.Unspecified,
    appearance: TextAppearance = LocalAppearances.current[Appearance.Text],
) = BasicText(text, modifier, appearance.resolved(role, ink, align), overflow = TextOverflow.Ellipsis, maxLines = lines)

@Composable
private fun TextAppearance.resolved(role: TextRole?, ink: InkRole?, align: TextAlign): TextStyle =
    style(role, ink, LocalThemeSnapshot.current).let { if (align == TextAlign.Unspecified) it else it.copy(textAlign = align) }

val BareText: TextAppearance = object : TextAppearance {
    @Composable
    override fun style(role: TextRole?, ink: InkRole?, theme: ThemeSnapshot): TextStyle = TextStyle.Default
}
