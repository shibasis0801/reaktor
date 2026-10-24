package dev.shibasis.reaktor.surface.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import dev.shibasis.reaktor.surface.ComponentContract
import dev.shibasis.reaktor.surface.Easing
import dev.shibasis.reaktor.surface.InkRole
import dev.shibasis.reaktor.surface.Motion
import dev.shibasis.reaktor.surface.TextRole
import dev.shibasis.reaktor.surface.ThemeSnapshot
import dev.shibasis.reaktor.surface.inkOf
import dev.shibasis.reaktor.surface.textRoleOf
import dev.shibasis.reaktor.surface.token

val LocalSurfaceStudio: ProvidableCompositionLocal<SurfaceStudio?> = staticCompositionLocalOf { null }

interface SurfaceStudio {
    @Composable
    fun Element(call: SurfaceCall, render: @Composable (SurfaceEdits) -> Unit)
}

typealias FieldType = dev.shibasis.reaktor.surface.FieldType

enum class SurfaceFlow { Vertical, Horizontal, Wrap, Layered }

data class SurfaceField(
    val name: String,
    val type: FieldType,
    val value: String?,
    val editable: Boolean = true,
    val choices: List<String> = emptyList(),
    val payload: Any? = null,
) {
    companion object {
        fun text(name: String, value: String?, editable: Boolean = true) = SurfaceField(name, FieldType.Text, value, editable)
        fun role(name: String, value: TextRole?) = SurfaceField(name, FieldType.Role, value?.token)
        fun ink(name: String, value: InkRole?) = SurfaceField(name, FieldType.Ink, value?.token)
        fun count(name: String, value: Int) = SurfaceField(name, FieldType.Count, if (value == Int.MAX_VALUE) Unbounded else value.toString())
        fun flag(name: String, value: Boolean, editable: Boolean = true) = SurfaceField(name, FieldType.Flag, value.toString(), editable)
        fun fraction(name: String, value: Float?, editable: Boolean = true) = SurfaceField(name, FieldType.Fraction, value?.let(TokenText::write), editable)
        fun choice(name: String, value: String, choices: List<String>, editable: Boolean = true) = SurfaceField(name, FieldType.Choice, value, editable, choices)
        fun size(name: String, value: Dp) = SurfaceField(name, FieldType.Size, TokenText.write(value))
        fun size(name: String, value: TextUnit) = SurfaceField(name, FieldType.Size, if (value.isSpecified) TokenText.write(value) else "")
        fun number(name: String, value: Float) = SurfaceField(name, FieldType.Number, TokenText.write(value))
        fun color(name: String, value: Color, tokens: SurfaceTokens?) = SurfaceField(name, FieldType.Color, tokens?.colorName(value) ?: StudioValues.color(value))
        fun style(name: String, value: TextStyle, tokens: SurfaceTokens?) = SurfaceField(name, FieldType.Style, tokens?.styleName(value) ?: "", tokens?.styles?.isNotEmpty() == true)
        fun shape(name: String, value: Shape?, tokens: SurfaceTokens?) = SurfaceField(name, FieldType.Shape, value?.let { tokens?.shapeName(it) ?: StudioValues.shape(it) }, value == null || StudioValues.shape(value) != null || tokens?.shapeName(value) != null)
        fun picture(name: String, value: String?, payload: Any?) = SurfaceField(name, FieldType.Picture, value, value != null, payload = payload)
        fun <T> choice(name: String, value: T, options: Map<String, T>) =
            SurfaceField(name, FieldType.Choice, StudioValues.nameOf(value, options) ?: "", StudioValues.nameOf(value, options) != null, options.keys.toList())

        const val Unbounded = "unbounded"
    }
}

class SurfaceCall(
    val kind: String,
    val modifier: Modifier,
    val fields: List<SurfaceField> = emptyList(),
    val appearance: Any? = null,
    val appearanceKey: AppearanceKey<*>? = null,
    val slots: List<String> = emptyList(),
    val flow: SurfaceFlow? = null,
    val contract: ComponentContract? = SurfaceContracts[kind],
)

interface SurfaceEdits {
    val modifier: Modifier
    fun value(field: String): String?
    fun <A : Any> appearance(original: A): A
    fun action(name: String, original: () -> Unit): () -> Unit
    fun <T> handler(name: String, original: (T) -> Unit): (T) -> Unit
    fun slot(name: String, content: @Composable () -> Unit): @Composable () -> Unit
    fun <S> scoped(name: String, content: @Composable S.() -> Unit): @Composable S.() -> Unit
    fun appended(name: String): (@Composable () -> Unit)? = null
}

fun SurfaceEdits.text(field: String, original: String): String = value(field) ?: original

fun SurfaceEdits.role(field: String, original: TextRole?): TextRole? = when (val chosen = value(field)) {
    null -> original
    "" -> null
    else -> textRoleOf(chosen) ?: original
}

fun SurfaceEdits.ink(field: String, original: InkRole?): InkRole? = when (val chosen = value(field)) {
    null -> original
    "" -> null
    else -> inkOf(chosen) ?: original
}

fun SurfaceEdits.count(field: String, original: Int): Int = when (val chosen = value(field)) {
    null -> original
    SurfaceField.Unbounded -> Int.MAX_VALUE
    else -> chosen.toIntOrNull() ?: original
}

fun SurfaceEdits.flag(field: String, original: Boolean): Boolean = value(field)?.toBooleanStrictOrNull() ?: original

fun SurfaceEdits.fraction(field: String, original: Float?): Float? = when (val chosen = value(field)) {
    null -> original
    "" -> null
    else -> chosen.toFloatOrNull() ?: original
}

fun SurfaceEdits.number(field: String, original: Float): Float = value(field)?.toFloatOrNull() ?: original

fun SurfaceEdits.size(field: String, original: Dp): Dp = value(field)?.let { TokenText.read(it, original) as? Dp } ?: original

fun SurfaceEdits.size(field: String, original: TextUnit): TextUnit = when (val chosen = value(field)) {
    null -> original
    "" -> TextUnit.Unspecified
    else -> TokenText.read(chosen, 0.sp) as? TextUnit ?: original
}

fun <T> SurfaceEdits.choice(field: String, original: T, options: Map<String, T>): T = value(field)?.let { chosen -> options[chosen] } ?: original

fun SurfaceEdits.color(field: String, original: Color, tokens: SurfaceTokens?): Color = when (val chosen = value(field)) {
    null -> original
    "" -> Color.Unspecified
    else -> tokens?.colors?.get(chosen) ?: StudioValues.color(chosen) ?: original
}

fun SurfaceEdits.style(field: String, original: TextStyle, tokens: SurfaceTokens?): TextStyle = value(field)?.let { tokens?.styles?.get(it) } ?: original

fun SurfaceEdits.shape(field: String, original: Shape, tokens: SurfaceTokens?): Shape = value(field)?.let { tokens?.shapes?.get(it) ?: StudioValues.shape(it) } ?: original

@Suppress("UNCHECKED_CAST")
fun <T : Any> SurfaceEdits.picture(field: String, original: T, pictures: SurfacePictures?): T = value(field)?.let { pictures?.get(it) as? T } ?: original

fun SurfaceEdits.optional(name: String, content: (@Composable () -> Unit)?): (@Composable () -> Unit)? = content?.let { slot(name, it) }

class AppearanceVariant(val key: AppearanceKey<*>, val name: String, val appearance: Any)

fun <A : Any> AppearanceKey<A>.variant(name: String, appearance: A): AppearanceVariant = AppearanceVariant(this, name, appearance)

@Immutable
class AppearanceCatalog(val name: String, val variants: List<AppearanceVariant>) {
    fun variants(key: AppearanceKey<*>): List<AppearanceVariant> = variants.filter { it.key == key }

    fun nameOf(appearance: Any?): String? = variants.firstOrNull { it.appearance === appearance }?.name

    fun named(key: AppearanceKey<*>, name: String): AppearanceVariant? = variants.firstOrNull { it.key == key && it.name == name }

    operator fun plus(other: AppearanceCatalog): AppearanceCatalog = AppearanceCatalog(name, variants + other.variants)
}

fun AppearanceCatalog(name: String, vararg variants: AppearanceVariant): AppearanceCatalog = AppearanceCatalog(name, variants.toList())

val LocalAppearanceCatalog: ProvidableCompositionLocal<AppearanceCatalog?> = staticCompositionLocalOf { null }

@Immutable
class SurfaceTokens(
    val colors: Map<String, Color> = emptyMap(),
    val styles: Map<String, TextStyle> = emptyMap(),
    val shapes: Map<String, Shape> = emptyMap(),
    val sizes: Map<String, Dp> = emptyMap(),
) {
    fun colorName(color: Color): String? = if (color == Color.Unspecified) null else colors.entries.firstOrNull { it.value == color }?.key

    fun styleName(style: TextStyle): String? = styles.entries.firstOrNull { it.value == style }?.key

    fun shapeName(shape: Shape): String? = shapes.entries.firstOrNull { it.value == shape }?.key

    fun sizeName(size: Dp): String? = sizes.entries.firstOrNull { it.value == size }?.key

    operator fun plus(other: SurfaceTokens): SurfaceTokens =
        SurfaceTokens(colors + other.colors, styles + other.styles, shapes + other.shapes, sizes + other.sizes)
}

val LocalSurfaceTokens: ProvidableCompositionLocal<SurfaceTokens?> = staticCompositionLocalOf { null }

interface SurfacePictures {
    fun name(picture: Any): String?
    fun get(name: String): Any?
    val names: List<String>
}

val LocalSurfacePictures: ProvidableCompositionLocal<SurfacePictures?> = staticCompositionLocalOf { null }

interface SurfaceDesign {
    fun snapshot(original: ThemeSnapshot): ThemeSnapshot
    fun appearances(original: Appearances, catalog: AppearanceCatalog?): Appearances
}

val LocalSurfaceDesign: ProvidableCompositionLocal<SurfaceDesign?> = staticCompositionLocalOf { null }

@Suppress("UNCHECKED_CAST")
fun Appearances.with(variant: AppearanceVariant): Appearances = this + ((variant.key as AppearanceKey<Any>) provides variant.appearance)

object TokenText {
    fun write(value: Any?): String? = when (value) {
        is Color -> "#" + value.toArgb().toUInt().toString(16).padStart(8, '0').uppercase()
        is Dp -> if (value == Dp.Unspecified) "" else "${value.value.clean()}dp"
        is TextUnit -> when {
            value.isSp -> "${value.value.clean()}sp"
            value.isEm -> "${value.value.clean()}em"
            else -> null
        }
        is Float -> value.clean()
        is Int, is Boolean, is String -> value.toString()
        is Motion.Spring -> "spring(${value.damping.clean()}, ${value.stiffness.clean()})"
        is Motion.Timed -> "tween(${value.millis}" + (if (value.easing == Easing.Standard) "" else ", ${value.easing.name}") + ")"
        else -> null
    }

    fun read(text: String, like: Any?): Any? {
        val trimmed = text.trim()
        return when (like) {
            is Color -> trimmed.removePrefix("#").let { if (it.length == 6) "FF$it" else it }.takeIf { it.length == 8 }
                ?.toULongOrNull(16)?.let { Color(it.toLong().toInt()) }
            is Dp -> if (trimmed.isEmpty()) Dp.Unspecified else trimmed.removeSuffix("dp").trim().toFloatOrNull()?.let(::Dp)
            is TextUnit -> when {
                trimmed.endsWith("em") -> trimmed.removeSuffix("em").trim().toFloatOrNull()?.em
                else -> trimmed.removeSuffix("sp").trim().toFloatOrNull()?.sp
            }
            is Float -> trimmed.toFloatOrNull()
            is Int -> trimmed.toIntOrNull()
            is Boolean -> trimmed.toBooleanStrictOrNull()
            is String -> text
            is Motion -> SpringText.matchEntire(trimmed)?.destructured?.let { (damping, stiffness) ->
                damping.toFloatOrNull()?.let { d -> stiffness.toFloatOrNull()?.let { Motion.Spring(d, it) } }
            } ?: TweenText.matchEntire(trimmed)?.destructured?.let { (millis, easing) ->
                millis.toIntOrNull()?.let { Motion.Timed(it, if (easing.isEmpty()) Easing.Standard else Easing.entries.firstOrNull { e -> e.name.equals(easing, true) } ?: return null) }
            }
            else -> null
        }
    }

    private val SpringText = Regex("""spring\(\s*([\d.]+)\s*,\s*([\d.]+)\s*\)""")
    private val TweenText = Regex("""tween\(\s*(\d+)\s*(?:,\s*(\w+)\s*)?\)""")

    private fun Float.clean(): String = if (this % 1f == 0f) toInt().toString() else toString()
}
