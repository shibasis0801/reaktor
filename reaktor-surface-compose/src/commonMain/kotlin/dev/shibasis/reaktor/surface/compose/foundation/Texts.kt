package dev.shibasis.reaktor.surface.compose.foundation

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import dev.shibasis.reaktor.surface.compose.LocalSurfaceStudio
import dev.shibasis.reaktor.surface.compose.LocalSurfaceTokens
import dev.shibasis.reaktor.surface.compose.StudioValues
import dev.shibasis.reaktor.surface.compose.SurfaceCall
import dev.shibasis.reaktor.surface.compose.SurfaceEdits
import dev.shibasis.reaktor.surface.compose.SurfaceField
import dev.shibasis.reaktor.surface.compose.SurfaceTokens
import dev.shibasis.reaktor.surface.compose.choice
import dev.shibasis.reaktor.surface.compose.color
import dev.shibasis.reaktor.surface.compose.count
import dev.shibasis.reaktor.surface.compose.flag
import dev.shibasis.reaktor.surface.compose.size
import dev.shibasis.reaktor.surface.compose.style
import androidx.compose.foundation.text.BasicText as FoundationBasicText

@Composable
@NonRestartableComposable
fun BasicText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    color: ColorProducer? = null,
    autoSize: TextAutoSize? = null,
) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) FoundationBasicText(text, modifier, style, onTextLayout, overflow, softWrap, maxLines, minLines, color, autoSize)
    else {
        val tokens = LocalSurfaceTokens.current
        studio.Element(basicCall(text, modifier, style, overflow, softWrap, maxLines, minLines, tokens)) { edits ->
            FoundationBasicText(edits.value("text") ?: text, edits.modifier, edits.restyled(style, tokens), onTextLayout,
                edits.choice("overflow", overflow, StudioValues.Overflows), edits.flag("softWrap", softWrap), edits.count("maxLines", maxLines),
                edits.count("minLines", minLines), color, autoSize)
        }
    }
}

@Composable
@NonRestartableComposable
fun BasicText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    color: ColorProducer? = null,
    autoSize: TextAutoSize? = null,
) {
    val studio = LocalSurfaceStudio.current
    if (studio == null) FoundationBasicText(text, modifier, style, onTextLayout, overflow, softWrap, maxLines, minLines, inlineContent, color, autoSize)
    else {
        val tokens = LocalSurfaceTokens.current
        studio.Element(basicCall(text.text, modifier, style, overflow, softWrap, maxLines, minLines, tokens)) { edits ->
            FoundationBasicText(edits.value("text")?.let(::AnnotatedString) ?: text, edits.modifier, edits.restyled(style, tokens), onTextLayout,
                edits.choice("overflow", overflow, StudioValues.Overflows), edits.flag("softWrap", softWrap), edits.count("maxLines", maxLines),
                edits.count("minLines", minLines), inlineContent, color, autoSize)
        }
    }
}

private fun basicCall(text: String, modifier: Modifier, style: TextStyle, overflow: TextOverflow, softWrap: Boolean, maxLines: Int, minLines: Int, tokens: SurfaceTokens?) =
    SurfaceCall("Text", modifier, listOf(SurfaceField.text("text", text)) + styleFields(style, tokens) + listOf(
        SurfaceField.choice("overflow", overflow, StudioValues.Overflows),
        SurfaceField.flag("softWrap", softWrap),
        SurfaceField.count("maxLines", maxLines),
        SurfaceField.count("minLines", minLines),
    ))

fun styleFields(style: TextStyle, tokens: SurfaceTokens?): List<SurfaceField> = listOf(
    SurfaceField.style("style", style, tokens),
    SurfaceField.color("color", style.color, tokens),
    SurfaceField.size("fontSize", style.fontSize),
    SurfaceField.choice("fontWeight", style.fontWeight ?: androidx.compose.ui.text.font.FontWeight.Normal, StudioValues.Weights),
    SurfaceField.choice("textAlign", style.textAlign, StudioValues.TextAligns),
    SurfaceField.size("lineHeight", style.lineHeight),
    SurfaceField.size("letterSpacing", style.letterSpacing),
)

fun SurfaceEdits.restyled(original: TextStyle, tokens: SurfaceTokens?): TextStyle {
    val base = style("style", original, tokens)
    if (StyleFields.none { value(it) != null }) return base
    return base.copy(
        color = color("color", base.color, tokens),
        fontSize = size("fontSize", base.fontSize),
        fontWeight = value("fontWeight")?.let { StudioValues.Weights[it] } ?: base.fontWeight,
        textAlign = value("textAlign")?.let { StudioValues.TextAligns[it] } ?: base.textAlign,
        lineHeight = size("lineHeight", base.lineHeight),
        letterSpacing = size("letterSpacing", base.letterSpacing),
    )
}

private val StyleFields = listOf("color", "fontSize", "fontWeight", "textAlign", "lineHeight", "letterSpacing")
