package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
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
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    val style = appearance.resolved(role, ink, align)
    if (overflow == TextOverflow.MiddleEllipsis && lines == 1) MiddleEllipsisText(AnnotatedString(text), modifier, style)
    else BasicText(text, modifier, style, overflow = overflow, maxLines = lines)
}

@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    role: TextRole? = null,
    ink: InkRole? = null,
    lines: Int = 1,
    align: TextAlign = TextAlign.Unspecified,
    appearance: TextAppearance = LocalAppearances.current[Appearance.Text],
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    val style = appearance.resolved(role, ink, align)
    if (overflow == TextOverflow.MiddleEllipsis && lines == 1) MiddleEllipsisText(text, modifier, style)
    else BasicText(text, modifier, style, overflow = overflow, maxLines = lines)
}

@Composable
private fun MiddleEllipsisText(original: AnnotatedString, modifier: Modifier, style: TextStyle) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.semantics(mergeDescendants = true) {}, propagateMinConstraints = true) {
        fun fits(candidate: AnnotatedString) = !measurer.measure(candidate, style, maxLines = 1,
            softWrap = false, constraints = Constraints(maxWidth = constraints.maxWidth)).hasVisualOverflow
        val rendered = if (fits(original)) original else {
            val cuts = buildList {
                add(0)
                original.text.forEachIndexed { index, char ->
                    if (!char.isHighSurrogate() || original.text.getOrNull(index + 1)?.isLowSurrogate() != true) add(index + 1)
                }
            }
            fun candidate(count: Int): AnnotatedString = original.subSequence(0, cuts[(count + 1) / 2]) +
                AnnotatedString("…") + original.subSequence(cuts[cuts.lastIndex - count / 2], original.length)
            var low = 0
            var high = cuts.lastIndex - 1
            while (low < high) {
                val count = (low + high + 1) / 2
                if (fits(candidate(count))) low = count else high = count - 1
            }
            candidate(low).takeIf(::fits) ?: AnnotatedString("")
        }
        BasicText(rendered, Modifier.semantics { text = original }, style, softWrap = false,
            overflow = TextOverflow.Clip, maxLines = 1)
    }
}

@Composable
fun Modifier.lineBox(role: TextRole, inset: Dp): Modifier {
    val style = LocalAppearances.current[Appearance.Text].style(role, null, LocalThemeSnapshot.current)
    val line = with(LocalDensity.current) {
        val font = if (style.fontSize.type == TextUnitType.Sp) style.fontSize.toDp() else 0.dp
        maxOf(font, when (style.lineHeight.type) {
            TextUnitType.Sp -> style.lineHeight.toDp()
            TextUnitType.Em -> font * style.lineHeight.value
            else -> font
        })
    }
    return heightIn(min = line + inset * 2)
}

@Composable
private fun TextAppearance.resolved(role: TextRole?, ink: InkRole?, align: TextAlign): TextStyle =
    style(role, ink, LocalThemeSnapshot.current).let { if (align == TextAlign.Unspecified) it else it.copy(textAlign = align) }

val BareText: TextAppearance = object : TextAppearance {
    @Composable
    override fun style(role: TextRole?, ink: InkRole?, theme: ThemeSnapshot): TextStyle = TextStyle.Default
}
