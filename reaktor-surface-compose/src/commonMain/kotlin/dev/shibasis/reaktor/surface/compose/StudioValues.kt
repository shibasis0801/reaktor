package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

data class Spread(val name: String, val spacing: Dp)

object StudioValues {
    val Alignments: Map<String, Alignment> = linkedMapOf(
        "TopStart" to Alignment.TopStart, "TopCenter" to Alignment.TopCenter, "TopEnd" to Alignment.TopEnd,
        "CenterStart" to Alignment.CenterStart, "Center" to Alignment.Center, "CenterEnd" to Alignment.CenterEnd,
        "BottomStart" to Alignment.BottomStart, "BottomCenter" to Alignment.BottomCenter, "BottomEnd" to Alignment.BottomEnd,
    )

    val Horizontal: Map<String, Alignment.Horizontal> =
        linkedMapOf("Start" to Alignment.Start, "CenterHorizontally" to Alignment.CenterHorizontally, "End" to Alignment.End)

    val Vertical: Map<String, Alignment.Vertical> =
        linkedMapOf("Top" to Alignment.Top, "CenterVertically" to Alignment.CenterVertically, "Bottom" to Alignment.Bottom)

    val Weights: Map<String, FontWeight> = linkedMapOf(
        "Thin" to FontWeight.Thin, "ExtraLight" to FontWeight.ExtraLight, "Light" to FontWeight.Light, "Normal" to FontWeight.Normal,
        "Medium" to FontWeight.Medium, "SemiBold" to FontWeight.SemiBold, "Bold" to FontWeight.Bold, "ExtraBold" to FontWeight.ExtraBold,
        "Black" to FontWeight.Black,
    )

    val TextAligns: Map<String, TextAlign> = linkedMapOf(
        "Start" to TextAlign.Start, "Center" to TextAlign.Center, "End" to TextAlign.End, "Justify" to TextAlign.Justify,
        "Left" to TextAlign.Left, "Right" to TextAlign.Right, "Unspecified" to TextAlign.Unspecified,
    )

    val Overflows: Map<String, TextOverflow> = linkedMapOf(
        "Clip" to TextOverflow.Clip, "Ellipsis" to TextOverflow.Ellipsis, "Visible" to TextOverflow.Visible,
        "StartEllipsis" to TextOverflow.StartEllipsis, "MiddleEllipsis" to TextOverflow.MiddleEllipsis,
    )

    val Scales: Map<String, ContentScale> = linkedMapOf(
        "Fit" to ContentScale.Fit, "Crop" to ContentScale.Crop, "FillBounds" to ContentScale.FillBounds, "FillWidth" to ContentScale.FillWidth,
        "FillHeight" to ContentScale.FillHeight, "Inside" to ContentScale.Inside, "None" to ContentScale.None,
    )

    val Spreads: List<String> = listOf("Start", "Center", "End", "SpaceBetween", "SpaceAround", "SpaceEvenly")

    val Layouts: List<String> = listOf("Column", "Row", "Box")

    val Cross: List<String> = listOf("Start", "Center", "End")

    fun cross(alignment: Alignment.Horizontal): String = when (alignment) {
        Alignment.CenterHorizontally -> "Center"
        Alignment.End -> "End"
        else -> "Start"
    }

    fun cross(alignment: Alignment.Vertical): String = when (alignment) {
        Alignment.CenterVertically -> "Center"
        Alignment.Bottom -> "End"
        else -> "Start"
    }

    fun horizontalOf(name: String): Alignment.Horizontal = when (name) {
        "Center" -> Alignment.CenterHorizontally
        "End" -> Alignment.End
        else -> Alignment.Start
    }

    fun verticalOf(name: String): Alignment.Vertical = when (name) {
        "Center" -> Alignment.CenterVertically
        "End" -> Alignment.Bottom
        else -> Alignment.Top
    }

    fun crossKotlin(name: String, vertical: Boolean): String =
        "Alignment." + if (vertical) nameOf(horizontalOf(name), Horizontal) else nameOf(verticalOf(name), Vertical)

    fun <T> nameOf(value: T, options: Map<String, T>): String? = options.entries.firstOrNull { it.value == value }?.key

    fun alignmentName(value: Any?): String? = when (value) {
        is Alignment -> nameOf(value, Alignments)
        is Alignment.Horizontal -> nameOf(value, Horizontal)
        is Alignment.Vertical -> nameOf(value, Vertical)
        else -> null
    }

    fun color(value: Color): String = if (value == Color.Unspecified) "" else TokenText.write(value).orEmpty()

    fun color(text: String): Color? = if (text.isBlank()) Color.Unspecified else TokenText.read(text, Color.Black) as? Color

    fun shape(shape: Shape): String? = when {
        shape === RectangleShape -> "Rectangle"
        shape is RoundedCornerShape -> corners(shape)?.let { if (it.all { corner -> corner == "50%" }) "Circle" else "Rounded(${it.compact()})" }
        shape is CutCornerShape -> corners(shape)?.let { "Cut(${it.compact()})" }
        else -> null
    }

    fun shape(text: String): Shape? {
        val trimmed = text.trim()
        if (trimmed == "Rectangle") return RectangleShape
        if (trimmed == "Circle") return CircleShape
        val match = ShapePattern.matchEntire(trimmed) ?: return null
        val corners = match.groupValues[2].split(',').map { corner(it.trim()) ?: return null }
        val four = when (corners.size) {
            1 -> List(4) { corners[0] }
            4 -> corners
            else -> return null
        }
        return if (match.groupValues[1] == "Cut") CutCornerShape(four[0], four[1], four[2], four[3]) else RoundedCornerShape(four[0], four[1], four[2], four[3])
    }

    fun shapeKotlin(text: String): String {
        val trimmed = text.trim()
        if (trimmed == "Rectangle") return "RectangleShape"
        if (trimmed == "Circle") return "CircleShape"
        val match = ShapePattern.matchEntire(trimmed) ?: return trimmed
        val type = if (match.groupValues[1] == "Cut") "CutCornerShape" else "RoundedCornerShape"
        val corners = match.groupValues[2].split(',').map(String::trim)
        if (corners.size == 1) return "$type(${cornerKotlin(corners[0], bare = true)})"
        val names = listOf("topStart", "topEnd", "bottomEnd", "bottomStart")
        return "$type(" + corners.mapIndexed { index, corner ->
            if (corner.endsWith("%")) "${names[index]}Percent = ${corner.removeSuffix("%")}" else "${names[index]} = ${cornerKotlin(corner, bare = false)}"
        }.joinToString(", ") + ")"
    }

    fun kotlin(type: FieldType, text: String): String = when {
        type == FieldType.Text -> "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        text.isEmpty() -> "Unspecified"
        token(text) -> text
        type == FieldType.Color -> "Color(0x${text.removePrefix("#")})"
        type == FieldType.Shape -> shapeKotlin(text)
        type == FieldType.Size -> number(text, "dp") ?: number(text, "sp") ?: text
        type == FieldType.Number || type == FieldType.Fraction -> text.toFloatOrNull()?.let { "${TokenText.write(it)}f" } ?: text
        else -> text
    }

    fun spread(arrangement: Arrangement.Vertical): Spread = spreadOf(arrangement.spacing) { total ->
        IntArray(2).also { out -> with(arrangement) { Plain.arrange(total, Probe, out) } }
    }

    fun spread(arrangement: Arrangement.Horizontal): Spread = spreadOf(arrangement.spacing) { total ->
        IntArray(2).also { out -> with(arrangement) { Plain.arrange(total, Probe, LayoutDirection.Ltr, out) } }
    }

    fun vertical(spread: String, spacing: Dp): Arrangement.Vertical = when (spread) {
        "Center" -> if (spacing > 0.dp) Arrangement.spacedBy(spacing, Alignment.CenterVertically) else Arrangement.Center
        "End" -> if (spacing > 0.dp) Arrangement.spacedBy(spacing, Alignment.Bottom) else Arrangement.Bottom
        "SpaceBetween" -> Arrangement.SpaceBetween
        "SpaceAround" -> Arrangement.SpaceAround
        "SpaceEvenly" -> Arrangement.SpaceEvenly
        else -> if (spacing > 0.dp) Arrangement.spacedBy(spacing) else Arrangement.Top
    }

    fun horizontal(spread: String, spacing: Dp): Arrangement.Horizontal = when (spread) {
        "Center" -> if (spacing > 0.dp) Arrangement.spacedBy(spacing, Alignment.CenterHorizontally) else Arrangement.Center
        "End" -> if (spacing > 0.dp) Arrangement.spacedBy(spacing, Alignment.End) else Arrangement.End
        "SpaceBetween" -> Arrangement.SpaceBetween
        "SpaceAround" -> Arrangement.SpaceAround
        "SpaceEvenly" -> Arrangement.SpaceEvenly
        else -> if (spacing > 0.dp) Arrangement.spacedBy(spacing) else Arrangement.Start
    }

    fun arrangementKotlin(spread: String, spacing: String, vertical: Boolean): String {
        val gap = spacing.takeIf(::token) ?: number(spacing, "dp")?.takeIf { (TokenText.read(spacing, 0.dp) as? Dp ?: 0.dp) > 0.dp }
        val start = if (vertical) "Top" else "Start"
        val end = if (vertical) "Bottom" else "End"
        val center = if (vertical) "CenterVertically" else "CenterHorizontally"
        return when (spread) {
            "Center" -> gap?.let { "Arrangement.spacedBy($it, Alignment.$center)" } ?: "Arrangement.Center"
            "End" -> gap?.let { "Arrangement.spacedBy($it, Alignment.$end)" } ?: "Arrangement.$end"
            "SpaceBetween", "SpaceAround", "SpaceEvenly" -> "Arrangement.$spread"
            else -> gap?.let { "Arrangement.spacedBy($it)" } ?: "Arrangement.$start"
        }
    }

    fun crossed(alignment: Alignment.Horizontal): Alignment.Vertical = when (alignment) {
        Alignment.CenterHorizontally -> Alignment.CenterVertically
        Alignment.End -> Alignment.Bottom
        else -> Alignment.Top
    }

    fun crossed(alignment: Alignment.Vertical): Alignment.Horizontal = when (alignment) {
        Alignment.CenterVertically -> Alignment.CenterHorizontally
        Alignment.Bottom -> Alignment.End
        else -> Alignment.Start
    }

    fun boxed(alignment: Alignment.Horizontal): Alignment = when (alignment) {
        Alignment.CenterHorizontally -> Alignment.TopCenter
        Alignment.End -> Alignment.TopEnd
        else -> Alignment.TopStart
    }

    fun boxed(alignment: Alignment.Vertical): Alignment = when (alignment) {
        Alignment.CenterVertically -> Alignment.CenterStart
        Alignment.Bottom -> Alignment.BottomStart
        else -> Alignment.TopStart
    }

    private fun spreadOf(spacing: Dp, probe: (Int) -> IntArray): Spread {
        val small = probe(100)
        val large = probe(200)
        val name = when {
            small[0] == 0 && large[0] == 0 && small[1] == large[1] -> "Start"
            small[0] == 0 && large[0] == 0 -> "SpaceBetween"
            small[1] == 90 && large[1] == 190 -> "End"
            large[0] - small[0] == 50 && large[1] - small[1] == 50 -> "Center"
            small[0] == 20 -> "SpaceAround"
            small[0] in 26..27 -> "SpaceEvenly"
            else -> "Start"
        }
        return Spread(name, if (name.startsWith("Space")) 0.dp else spacing)
    }

    private val Plain: Density = Density(1f)

    private val Probe = intArrayOf(10, 10)

    private val ShapePattern = Regex("""(Rounded|Cut)\(([^)]*)\)""")

    private fun corners(shape: CornerBasedShape): List<String>? =
        listOf(shape.topStart, shape.topEnd, shape.bottomEnd, shape.bottomStart).map { corner(it) ?: return null }

    private fun corner(size: CornerSize): String? {
        val small = size.toPx(Size(100f, 100f), Plain)
        val large = size.toPx(Size(200f, 200f), Plain)
        return when {
            abs(large - small) < .01f -> "${TokenText.write(small)}dp"
            abs(large - 2 * small) < .01f -> "${TokenText.write(small)}%"
            else -> null
        }
    }

    private fun corner(text: String): CornerSize? = when {
        text.endsWith("%") -> text.removeSuffix("%").trim().toFloatOrNull()?.let { CornerSize(it.toInt()) }
        else -> text.removeSuffix("dp").trim().toFloatOrNull()?.let { CornerSize(it.dp) }
    }

    private fun cornerKotlin(corner: String, bare: Boolean): String =
        if (corner.endsWith("%")) corner.removeSuffix("%") else number(corner, "dp") ?: if (bare) corner else "0.dp"

    private fun List<String>.compact(): String = if (distinct().size == 1) first() else joinToString(",")

    fun token(text: String): Boolean = text.contains('.') && text.first().isUpperCase() && !text.endsWith("dp") && !text.endsWith("sp")

    private fun number(text: String, unit: String): String? =
        text.takeIf { it.endsWith(unit) }?.removeSuffix(unit)?.trim()?.toFloatOrNull()?.let { "${TokenText.write(it)}.$unit" }
}
