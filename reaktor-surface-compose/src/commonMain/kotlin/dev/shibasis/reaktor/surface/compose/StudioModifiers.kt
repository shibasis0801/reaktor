package dev.shibasis.reaktor.surface.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.InspectableValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

class StepArg(val name: String, val type: FieldType, val choices: List<String> = emptyList())

@Immutable
data class ModifierStep(val id: String, val name: String, val args: Map<String, String>, val editable: Boolean) {
    val label: String get() = ModifierSteps.label(name, args)
}

enum class StepState { Original, Edited, Removed, Added }

@Immutable
data class PatchedStep(val step: ModifierStep, val state: StepState, val original: Map<String, String>)

class StepContext(val scope: Any?, val tokens: SurfaceTokens?)

object ModifierSteps {
    const val Prefix = "modifier."
    const val Added = "modifier+"
    const val Removed = "-"
    const val Start = "^"

    fun elements(modifier: Modifier): List<Modifier.Element> = modifier.foldIn(ArrayList()) { list, element -> list.add(element); list }

    fun read(modifier: Modifier, context: StepContext): List<ModifierStep> {
        val seen = HashMap<String, Int>()
        return elements(modifier).map { element ->
            val raw = raw(element)
            val index = seen[raw.name] ?: 0
            seen[raw.name] = index + 1
            val args = Codecs[raw.name]?.takeIf { it.fits(context.scope) }?.read?.invoke(raw, context)
            ModifierStep("${raw.name}#$index", raw.name, args ?: raw.shown(), args != null)
        }
    }

    fun touches(values: Map<String, String>?): Boolean = values?.keys?.any { it.startsWith(Prefix) || it.startsWith(Added) } == true

    fun apply(modifier: Modifier, values: Map<String, String>, context: StepContext): Modifier {
        if (!touches(values)) return modifier
        val additions = additions(values)
        val seen = HashMap<String, Int>()
        var result: Modifier = Modifier
        fun place(anchor: String) {
            additions.filter { it.anchor == anchor }.forEach { added ->
                build(added.step.name, added.step.args, context)?.let { result = result.then(it) }
                place(added.step.id)
            }
        }
        place(Start)
        elements(modifier).forEach { element ->
            val raw = raw(element)
            val index = seen[raw.name] ?: 0
            seen[raw.name] = index + 1
            val id = "${raw.name}#$index"
            if (values[Prefix + id] != Removed) {
                val overrides = overrides(values, id)
                val rebuilt = if (overrides.isEmpty()) null
                else Codecs[raw.name]?.takeIf { it.fits(context.scope) }?.let { codec -> codec.read(raw, context)?.let { codec.build(it + overrides, context) } }
                result = result.then(rebuilt ?: element)
            }
            place(id)
        }
        return result
    }

    fun patched(steps: List<ModifierStep>, values: Map<String, String>?): List<PatchedStep> {
        val all = values.orEmpty()
        val additions = additions(all)
        return buildList {
            fun place(anchor: String) {
                additions.filter { it.anchor == anchor }.forEach { added ->
                    add(PatchedStep(added.step, StepState.Added, emptyMap()))
                    place(added.step.id)
                }
            }
            place(Start)
            steps.forEach { step ->
                val overrides = overrides(all, step.id)
                val state = when {
                    all[Prefix + step.id] == Removed -> StepState.Removed
                    overrides.isNotEmpty() -> StepState.Edited
                    else -> StepState.Original
                }
                add(PatchedStep(step.copy(args = step.args + overrides), state, step.args))
                place(step.id)
            }
        }
    }

    fun addition(values: Map<String, String>?, name: String, anchor: String, args: Map<String, String>): Map<String, String> {
        val taken = additions(values.orEmpty()).map { it.step.id }.toSet()
        val id = generateSequence(0) { it + 1 }.map { "+$name#$it" }.first { it !in taken }
        return mapOf(Added + id.removePrefix("+") to anchor) + args.mapKeys { (arg, _) -> Added + id.removePrefix("+") + "." + arg }
    }

    fun argKey(step: ModifierStep, arg: String): String =
        if (step.id.startsWith("+")) Added + step.id.removePrefix("+") + "." + arg else Prefix + step.id + "." + arg

    fun keys(step: ModifierStep, values: Map<String, String>?): Set<String> {
        val root = if (step.id.startsWith("+")) Added + step.id.removePrefix("+") else Prefix + step.id
        return values.orEmpty().keys.filterTo(HashSet()) { it == root || it.startsWith("$root.") }
    }

    fun removalKey(step: ModifierStep): String = Prefix + step.id

    fun schema(name: String, scope: Any?): List<StepArg> = Codecs[name]?.args?.invoke(scope).orEmpty()

    fun addable(context: StepContext): List<ModifierStep> = Templates.filter { (name, _) -> Codecs[name]?.fits(context.scope) == true }
        .map { (name, args) -> ModifierStep(name, name, if (name == "align") mapOf("alignment" to alignmentDefault(context.scope)) else args, true) }
        .distinctBy { it.label }

    fun label(name: String, args: Map<String, String>): String = when (name) {
        "graphicsLayer" -> layerForm(args)?.first ?: name
        else -> name
    }

    fun kotlin(step: ModifierStep): String = when {
        !step.editable -> opaqueKotlin(step)
        else -> Codecs[step.name]?.kotlin?.invoke(step.args) ?: opaqueKotlin(step)
    }

    fun kotlin(steps: List<PatchedStep>): String =
        "Modifier" + steps.filter { it.state != StepState.Removed }.joinToString("") { "." + kotlin(it.step) }

    fun original(steps: List<ModifierStep>): String = "Modifier" + steps.joinToString("") { "." + kotlin(it) }

    private class Addition(val step: ModifierStep, val anchor: String)

    private fun additions(values: Map<String, String>): List<Addition> {
        val roots = values.keys.filter { it.startsWith(Added) && '.' !in it.removePrefix(Added) }
        return roots.sortedBy { it.substringAfterLast('#').toIntOrNull() ?: 0 }.map { root ->
            val id = "+" + root.removePrefix(Added)
            val name = id.removePrefix("+").substringBefore('#')
            val args = values.filterKeys { it.startsWith("$root.") }.mapKeys { (key, _) -> key.removePrefix("$root.") }
            Addition(ModifierStep(id, name, args, true), values.getValue(root))
        }
    }

    private fun overrides(values: Map<String, String>, id: String): Map<String, String> {
        val root = "$Prefix$id."
        return values.filterKeys { it.startsWith(root) }.mapKeys { (key, _) -> key.removePrefix(root) }
    }

    private fun build(name: String, args: Map<String, String>, context: StepContext): Modifier? =
        Codecs[name]?.takeIf { it.fits(context.scope) }?.let { codec -> runCatching { codec.build(args, context) }.getOrNull() }

    private class Raw(val name: String, val value: Any?, val props: Map<String, Any?>) {
        fun shown(): Map<String, String> = buildMap {
            (listOf("value" to value) + props.toList()).forEach { (key, item) ->
                when (item) {
                    is String -> put(key, item)
                    is Boolean, is Int -> put(key, item.toString())
                    is Dp -> put(key, TokenText.write(item).orEmpty())
                    is Float -> put(key, TokenText.write(item).orEmpty())
                }
            }
        }
    }

    private fun raw(element: Modifier.Element): Raw {
        val inspectable = element as? InspectableValue
        val name = inspectable?.nameFallback
            ?: element::class.simpleName?.removeSuffix("Element")?.removeSuffix("Modifier")?.replaceFirstChar { it.lowercase() }
            ?: "modifier"
        val props = inspectable?.inspectableElements?.associate { it.name to it.value }.orEmpty()
        return Raw(name, inspectable?.valueOverride, props)
    }

    private class Codec(
        val args: (Any?) -> List<StepArg>,
        val read: (Raw, StepContext) -> Map<String, String>?,
        val build: (Map<String, String>, StepContext) -> Modifier?,
        val kotlin: (Map<String, String>) -> String,
        val fits: (Any?) -> Boolean = { true },
    )

    private fun size(name: String) = StepArg(name, FieldType.Size)
    private fun number(name: String) = StepArg(name, FieldType.Number)
    private fun flag(name: String) = StepArg(name, FieldType.Flag)
    private fun color(name: String) = StepArg(name, FieldType.Color)
    private fun shape(name: String) = StepArg(name, FieldType.Shape)
    private fun fraction(name: String) = StepArg(name, FieldType.Fraction)

    private fun Map<String, String>.dp(key: String): Dp = this[key]?.let { TokenText.read(it, 0.dp) as? Dp } ?: Dp.Unspecified
    private fun Map<String, String>.float(key: String, default: Float): Float = this[key]?.toFloatOrNull() ?: default
    private fun Map<String, String>.flag(key: String, default: Boolean): Boolean = this[key]?.toBooleanStrictOrNull() ?: default
    private fun Map<String, String>.color(key: String, context: StepContext): Color =
        this[key]?.let { context.tokens?.colors?.get(it) ?: StudioValues.color(it) } ?: Color.Unspecified
    private fun Map<String, String>.shape(key: String, context: StepContext): Shape =
        this[key]?.let { context.tokens?.shapes?.get(it) ?: StudioValues.shape(it) } ?: RectangleShape

    private fun dpText(value: Any?): String? = (value as? Dp)?.let { TokenText.write(it) }
    private fun floatText(value: Any?): String? = (value as? Float)?.let { TokenText.write(it) }
    private fun colorText(value: Any?, context: StepContext): String? = (value as? Color)?.let { context.tokens?.colorName(it) ?: StudioValues.color(it) }
    private fun shapeText(value: Any?, context: StepContext): String? = (value as? Shape)?.let { context.tokens?.shapeName(it) ?: StudioValues.shape(it) }

    private fun texts(vararg pairs: Pair<String, String?>): Map<String, String>? =
        pairs.associate { (key, value) -> key to (value ?: return null) }

    private fun dpK(text: String?): String = text?.takeIf { it.isNotEmpty() }?.let { StudioValues.kotlin(FieldType.Size, it) } ?: "Dp.Unspecified"
    private fun fK(text: String?): String = StudioValues.kotlin(FieldType.Number, text ?: "0")
    private fun colorK(text: String?): String = StudioValues.kotlin(FieldType.Color, text.orEmpty())
    private fun shapeK(text: String?): String = text?.let { StudioValues.kotlin(FieldType.Shape, it) } ?: "RectangleShape"

    private fun named(vararg pairs: Pair<String, String?>): String = pairs.filter { it.second != null }.joinToString(", ") { "${it.first} = ${it.second}" }

    private fun paddingKotlin(a: Map<String, String>): String {
        val start = a["start"].orEmpty()
        val top = a["top"].orEmpty()
        val end = a["end"].orEmpty()
        val bottom = a["bottom"].orEmpty()
        fun zero(text: String) = (TokenText.read(text, 0.dp) as? Dp)?.value == 0f
        return when {
            start == top && top == end && end == bottom -> "padding(${dpK(start)})"
            start == end && top == bottom -> "padding(" + named("horizontal" to dpK(start).takeIf { !zero(start) }, "vertical" to dpK(top).takeIf { !zero(top) }) + ")"
            else -> "padding(" + named("start" to dpK(start).takeIf { !zero(start) }, "top" to dpK(top).takeIf { !zero(top) },
                "end" to dpK(end).takeIf { !zero(end) }, "bottom" to dpK(bottom).takeIf { !zero(bottom) }) + ")"
        }
    }

    private fun alignmentChoices(scope: Any?): List<String> = when (scope) {
        is BoxScope -> StudioValues.Alignments.keys.toList()
        is ColumnScope -> StudioValues.Horizontal.keys.toList()
        is RowScope -> StudioValues.Vertical.keys.toList()
        else -> emptyList()
    }

    private fun alignmentDefault(scope: Any?): String = when (scope) {
        is BoxScope -> "Center"
        is ColumnScope -> "CenterHorizontally"
        is RowScope -> "CenterVertically"
        else -> ""
    }

    private fun layerForm(a: Map<String, String>): Pair<String, String>? {
        val changed = a.filter { (key, value) -> LayerDefaults[key] != value }
        val shape = a["shape"]
        return when {
            changed.keys == setOf("shape", "clip") && a["clip"] == "true" -> "clip" to "clip(${shapeK(shape)})"
            changed.keys == setOf("clip") && a["clip"] == "true" -> "clipToBounds" to "clipToBounds()"
            changed.keys == setOf("alpha") -> "alpha" to "alpha(${fK(a["alpha"])})"
            changed.keys == setOf("rotationZ") -> "rotate" to "rotate(${fK(a["rotationZ"])})"
            changed.keys == setOf("scaleX", "scaleY") && a["scaleX"] == a["scaleY"] -> "scale" to "scale(${fK(a["scaleX"])})"
            else -> null
        }
    }

    private val LayerDefaults = mapOf(
        "alpha" to "1", "scaleX" to "1", "scaleY" to "1", "rotationZ" to "0", "translationX" to "0", "translationY" to "0",
        "shadowElevation" to "0", "shape" to "Rectangle", "clip" to "false",
    )

    private fun opaqueKotlin(step: ModifierStep): String = when (step.name) {
        "testTag" -> "testTag(\"${step.args["tag"] ?: step.args["value"].orEmpty()}\")"
        else -> "${step.name}(…)"
    }

    private fun sized(name: String, key: String, make: (Dp) -> Modifier) = Codec(
        args = { listOf(size(key)) },
        read = { raw, _ -> texts(key to dpText(raw.value ?: raw.props[key])) },
        build = { a, _ -> make(a.dp(key)) },
        kotlin = { a -> "$name(${dpK(a[key])})" },
    )

    private fun bounded(name: String, make: (Dp, Dp) -> Modifier) = Codec(
        args = { listOf(size("min"), size("max")) },
        read = { raw, _ -> texts("min" to dpText(raw.props["min"]), "max" to dpText(raw.props["max"])) },
        build = { a, _ -> make(a.dp("min"), a.dp("max")) },
        kotlin = { a -> "$name(" + named("min" to a["min"]?.takeIf(String::isNotEmpty)?.let(::dpK), "max" to a["max"]?.takeIf(String::isNotEmpty)?.let(::dpK)) + ")" },
    )

    private fun filled(name: String, make: (Float) -> Modifier) = Codec(
        args = { listOf(fraction("fraction")) },
        read = { raw, _ -> texts("fraction" to floatText(raw.props["fraction"] ?: 1f)) },
        build = { a, _ -> make(a.float("fraction", 1f)) },
        kotlin = { a -> if (a.float("fraction", 1f) == 1f) "$name()" else "$name(${fK(a["fraction"])})" },
    )

    private fun boxSized(name: String, make: (Dp, Dp) -> Modifier) = Codec(
        args = { listOf(size("width"), size("height")) },
        read = { raw, _ ->
            val single = raw.value as? Dp
            texts("width" to dpText(single ?: raw.props["width"]), "height" to dpText(single ?: raw.props["height"]))
        },
        build = { a, _ -> make(a.dp("width"), a.dp("height")) },
        kotlin = { a -> if (a["width"] == a["height"]) "$name(${dpK(a["width"])})" else "$name(" + named("width" to dpK(a["width"]), "height" to dpK(a["height"])) + ")" },
    )

    private fun wrapped(name: String, choices: List<String>, make: (Any?, Boolean) -> Modifier) = Codec(
        args = { listOf(StepArg("align", FieldType.Choice, choices), flag("unbounded")) },
        read = { raw, _ -> texts("align" to StudioValues.alignmentName(raw.props["align"]), "unbounded" to (raw.props["unbounded"] as? Boolean ?: false).toString()) },
        build = { a, _ ->
            val chosen = a["align"]?.let { StudioValues.Alignments[it] ?: StudioValues.Horizontal[it] ?: StudioValues.Vertical[it] }
            make(chosen, a.flag("unbounded", false))
        },
        kotlin = { a -> "$name(" + named("align" to a["align"]?.let { "Alignment.$it" }, "unbounded" to a["unbounded"]?.takeIf { it == "true" }) + ")" },
    )

    private val Codecs: Map<String, Codec> = mapOf(
        "padding" to Codec(
            args = { listOf(size("start"), size("top"), size("end"), size("bottom")) },
            read = { raw, _ ->
                val p = raw.props
                when {
                    "start" in p -> texts("start" to dpText(p["start"]), "top" to dpText(p["top"]), "end" to dpText(p["end"]), "bottom" to dpText(p["bottom"]))
                    "horizontal" in p -> texts("start" to dpText(p["horizontal"]), "top" to dpText(p["vertical"]), "end" to dpText(p["horizontal"]), "bottom" to dpText(p["vertical"]))
                    "paddingValues" in p -> (p["paddingValues"] as? PaddingValues)?.let { v ->
                        texts("start" to dpText(v.calculateStartPadding(LayoutDirection.Ltr)), "top" to dpText(v.calculateTopPadding()),
                            "end" to dpText(v.calculateEndPadding(LayoutDirection.Ltr)), "bottom" to dpText(v.calculateBottomPadding()))
                    }
                    raw.value is Dp -> dpText(raw.value)?.let { all -> mapOf("start" to all, "top" to all, "end" to all, "bottom" to all) }
                    else -> null
                }
            },
            build = { a, _ -> Modifier.padding(a.dp("start").orZero(), a.dp("top").orZero(), a.dp("end").orZero(), a.dp("bottom").orZero()) },
            kotlin = ::paddingKotlin,
        ),
        "size" to boxSized("size") { width, height -> Modifier.size(width, height) },
        "requiredSize" to boxSized("requiredSize") { width, height -> Modifier.requiredSize(width, height) },
        "width" to sized("width", "width") { Modifier.width(it) },
        "height" to sized("height", "height") { Modifier.height(it) },
        "requiredWidth" to sized("requiredWidth", "width") { Modifier.requiredWidth(it) },
        "requiredHeight" to sized("requiredHeight", "height") { Modifier.requiredHeight(it) },
        "widthIn" to bounded("widthIn") { min, max -> Modifier.widthIn(min, max) },
        "heightIn" to bounded("heightIn") { min, max -> Modifier.heightIn(min, max) },
        "sizeIn" to Codec(
            args = { listOf(size("minWidth"), size("minHeight"), size("maxWidth"), size("maxHeight")) },
            read = { raw, _ -> texts("minWidth" to dpText(raw.props["minWidth"]), "minHeight" to dpText(raw.props["minHeight"]),
                "maxWidth" to dpText(raw.props["maxWidth"]), "maxHeight" to dpText(raw.props["maxHeight"])) },
            build = { a, _ -> Modifier.sizeIn(a.dp("minWidth"), a.dp("minHeight"), a.dp("maxWidth"), a.dp("maxHeight")) },
            kotlin = { a -> "sizeIn(" + named(*listOf("minWidth", "minHeight", "maxWidth", "maxHeight").map { it to a[it]?.takeIf(String::isNotEmpty)?.let(::dpK) }.toTypedArray()) + ")" },
        ),
        "defaultMinSize" to Codec(
            args = { listOf(size("minWidth"), size("minHeight")) },
            read = { raw, _ -> texts("minWidth" to dpText(raw.props["minWidth"]), "minHeight" to dpText(raw.props["minHeight"])) },
            build = { a, _ -> Modifier.defaultMinSize(a.dp("minWidth"), a.dp("minHeight")) },
            kotlin = { a -> "defaultMinSize(" + named("minWidth" to a["minWidth"]?.takeIf(String::isNotEmpty)?.let(::dpK), "minHeight" to a["minHeight"]?.takeIf(String::isNotEmpty)?.let(::dpK)) + ")" },
        ),
        "fillMaxWidth" to filled("fillMaxWidth") { Modifier.fillMaxWidth(it) },
        "fillMaxHeight" to filled("fillMaxHeight") { Modifier.fillMaxHeight(it) },
        "fillMaxSize" to filled("fillMaxSize") { Modifier.fillMaxSize(it) },
        "wrapContentWidth" to wrapped("wrapContentWidth", StudioValues.Horizontal.keys.toList()) { align, unbounded ->
            Modifier.wrapContentWidth(align as? Alignment.Horizontal ?: Alignment.CenterHorizontally, unbounded)
        },
        "wrapContentHeight" to wrapped("wrapContentHeight", StudioValues.Vertical.keys.toList()) { align, unbounded ->
            Modifier.wrapContentHeight(align as? Alignment.Vertical ?: Alignment.CenterVertically, unbounded)
        },
        "wrapContentSize" to wrapped("wrapContentSize", StudioValues.Alignments.keys.toList()) { align, unbounded ->
            Modifier.wrapContentSize(align as? Alignment ?: Alignment.Center, unbounded)
        },
        "weight" to Codec(
            args = { listOf(number("weight"), flag("fill")) },
            read = { raw, _ -> texts("weight" to floatText(raw.props["weight"] ?: raw.value), "fill" to (raw.props["fill"] as? Boolean ?: true).toString()) },
            build = { a, context ->
                val weight = a.float("weight", 1f).coerceAtLeast(.01f)
                when (val scope = context.scope) {
                    is RowScope -> with(scope) { Modifier.weight(weight, a.flag("fill", true)) }
                    is ColumnScope -> with(scope) { Modifier.weight(weight, a.flag("fill", true)) }
                    else -> null
                }
            },
            kotlin = { a -> if (a.flag("fill", true)) "weight(${fK(a["weight"])})" else "weight(${fK(a["weight"])}, fill = false)" },
            fits = { it is RowScope || it is ColumnScope },
        ),
        "align" to Codec(
            args = { scope -> listOf(StepArg("alignment", FieldType.Choice, alignmentChoices(scope))) },
            read = { raw, _ -> texts("alignment" to StudioValues.alignmentName(raw.value ?: raw.props["alignment"])) },
            build = { a, context ->
                val name = a["alignment"].orEmpty()
                when (val scope = context.scope) {
                    is BoxScope -> StudioValues.Alignments[name]?.let { with(scope) { Modifier.align(it) } }
                    is ColumnScope -> StudioValues.Horizontal[name]?.let { with(scope) { Modifier.align(it) } }
                    is RowScope -> StudioValues.Vertical[name]?.let { with(scope) { Modifier.align(it) } }
                    else -> null
                }
            },
            kotlin = { a -> "align(Alignment.${a["alignment"]})" },
            fits = { it is BoxScope || it is ColumnScope || it is RowScope },
        ),
        "background" to Codec(
            args = { listOf(color("color"), shape("shape")) },
            read = { raw, context ->
                val brush = raw.props["brush"]
                val solid = (raw.props["color"] as? Color) ?: (brush as? SolidColor)?.value
                if (brush != null && brush !is SolidColor) null
                else texts("color" to colorText(solid, context), "shape" to shapeText(raw.props["shape"] ?: RectangleShape, context))
            },
            build = { a, context -> Modifier.background(a.color("color", context), a.shape("shape", context)) },
            kotlin = { a -> if (a["shape"] == null || a["shape"] == "Rectangle") "background(${colorK(a["color"])})" else "background(${colorK(a["color"])}, ${shapeK(a["shape"])})" },
        ),
        "border" to Codec(
            args = { listOf(size("width"), color("color"), shape("shape")) },
            read = { raw, context ->
                val brush = raw.props["brush"]
                val solid = (raw.props["color"] as? Color) ?: (brush as? SolidColor)?.value
                if (brush != null && brush !is SolidColor) null
                else texts("width" to dpText(raw.props["width"]), "color" to colorText(solid, context), "shape" to shapeText(raw.props["shape"] ?: RectangleShape, context))
            },
            build = { a, context -> Modifier.border(a.dp("width").orZero(), a.color("color", context), a.shape("shape", context)) },
            kotlin = { a -> "border(${dpK(a["width"])}, ${colorK(a["color"])}" + (a["shape"]?.takeIf { it != "Rectangle" }?.let { ", ${shapeK(it)}" }.orEmpty()) + ")" },
        ),
        "graphicsLayer" to Codec(
            args = { listOf(fraction("alpha"), number("rotationZ"), number("scaleX"), number("scaleY"), number("translationX"), number("translationY"),
                number("shadowElevation"), shape("shape"), flag("clip")) },
            read = { raw, context ->
                val p = raw.props
                val plain = p["renderEffect"] == null && p["colorFilter"] == null && (p["blendMode"] ?: BlendMode.SrcOver) == BlendMode.SrcOver &&
                    (p["compositingStrategy"] ?: CompositingStrategy.Auto) == CompositingStrategy.Auto &&
                    (p["rotationX"] as? Float ?: 0f) == 0f && (p["rotationY"] as? Float ?: 0f) == 0f &&
                    (p["transformOrigin"] ?: TransformOrigin.Center) == TransformOrigin.Center && "block" !in p
                if (!plain) null
                else texts(*listOf("alpha", "rotationZ", "scaleX", "scaleY", "translationX", "translationY", "shadowElevation").map { it to floatText(p[it]) }.toTypedArray(),
                    "shape" to shapeText(p["shape"] ?: RectangleShape, context), "clip" to (p["clip"] as? Boolean ?: false).toString())
            },
            build = { a, context ->
                Modifier.graphicsLayer(scaleX = a.float("scaleX", 1f), scaleY = a.float("scaleY", 1f), alpha = a.float("alpha", 1f),
                    translationX = a.float("translationX", 0f), translationY = a.float("translationY", 0f), shadowElevation = a.float("shadowElevation", 0f),
                    rotationZ = a.float("rotationZ", 0f), shape = a.shape("shape", context), clip = a.flag("clip", false))
            },
            kotlin = { a ->
                layerForm(a)?.second ?: ("graphicsLayer(" + a.filter { (key, value) -> LayerDefaults[key] != value }.entries.joinToString(", ") { (key, value) ->
                    "$key = " + when (key) {
                        "shape" -> shapeK(value)
                        "clip" -> value
                        else -> fK(value)
                    }
                } + ")")
            },
        ),
        "shadow" to Codec(
            args = { listOf(size("elevation"), shape("shape"), flag("clip")) },
            read = { raw, context -> texts("elevation" to dpText(raw.props["elevation"]), "shape" to shapeText(raw.props["shape"] ?: RectangleShape, context),
                "clip" to (raw.props["clip"] as? Boolean ?: false).toString()) },
            build = { a, context -> Modifier.shadow(a.dp("elevation").orZero(), a.shape("shape", context), a.flag("clip", false)) },
            kotlin = { a -> "shadow(${dpK(a["elevation"])}" + (a["shape"]?.takeIf { it != "Rectangle" }?.let { ", ${shapeK(it)}" }.orEmpty()) + ")" },
        ),
        "offset" to Codec(
            args = { listOf(size("x"), size("y")) },
            read = { raw, _ -> texts("x" to dpText(raw.props["x"]), "y" to dpText(raw.props["y"])) },
            build = { a, _ -> Modifier.offset(a.dp("x").orZero(), a.dp("y").orZero()) },
            kotlin = { a -> "offset(" + named("x" to dpK(a["x"]), "y" to dpK(a["y"])) + ")" },
        ),
        "absoluteOffset" to Codec(
            args = { listOf(size("x"), size("y")) },
            read = { raw, _ -> texts("x" to dpText(raw.props["x"]), "y" to dpText(raw.props["y"])) },
            build = { a, _ -> Modifier.absoluteOffset(a.dp("x").orZero(), a.dp("y").orZero()) },
            kotlin = { a -> "absoluteOffset(" + named("x" to dpK(a["x"]), "y" to dpK(a["y"])) + ")" },
        ),
        "aspectRatio" to Codec(
            args = { listOf(number("ratio"), flag("matchHeightConstraintsFirst")) },
            read = { raw, _ -> texts("ratio" to floatText(raw.props["ratio"]), "matchHeightConstraintsFirst" to (raw.props["matchHeightConstraintsFirst"] as? Boolean ?: false).toString()) },
            build = { a, _ -> Modifier.aspectRatio(a.float("ratio", 1f).coerceAtLeast(.01f), a.flag("matchHeightConstraintsFirst", false)) },
            kotlin = { a -> "aspectRatio(${fK(a["ratio"])}" + (if (a.flag("matchHeightConstraintsFirst", false)) ", matchHeightConstraintsFirst = true" else "") + ")" },
        ),
        "zIndex" to Codec(
            args = { listOf(number("zIndex")) },
            read = { raw, _ -> texts("zIndex" to floatText(raw.props["zIndex"] ?: raw.value)) },
            build = { a, _ -> Modifier.zIndex(a.float("zIndex", 0f)) },
            kotlin = { a -> "zIndex(${fK(a["zIndex"])})" },
        ),
        "spoken" to Codec(
            args = { listOf(StepArg("label", FieldType.Text), StepArg("role", FieldType.Choice, listOf("") + SpokenRoles.keys), flag("heading"), flag("hidden"), flag("grouped")) },
            read = { raw, _ ->
                mapOf("label" to (raw.props["label"] as? String).orEmpty(), "role" to SpokenRoles.entries.firstOrNull { it.value == raw.props["role"] }?.key.orEmpty()) +
                    SpokenFlags.associateWith { (raw.props[it] == true).toString() }
            },
            build = { a, _ -> Modifier.spoken(a["label"]?.takeIf(String::isNotEmpty), a["role"]?.let(SpokenRoles::get), a.flag("heading", false), a.flag("hidden", false), a.flag("grouped", false)) },
            kotlin = { a ->
                "spoken(" + (listOfNotNull(a["label"]?.takeIf(String::isNotEmpty)?.let { StudioValues.kotlin(FieldType.Text, it) }, a["role"]?.takeIf(String::isNotEmpty)?.let { "role = Role.$it" }) +
                    SpokenFlags.filter { a.flag(it, false) }.map { "$it = true" }).joinToString(", ") + ")"
            },
        ),
    )

    private val SpokenFlags = listOf("heading", "hidden", "grouped")

    private val Templates: List<Pair<String, Map<String, String>>> = listOf(
        "padding" to mapOf("start" to "8dp", "top" to "8dp", "end" to "8dp", "bottom" to "8dp"),
        "fillMaxWidth" to mapOf("fraction" to "1"),
        "fillMaxSize" to mapOf("fraction" to "1"),
        "size" to mapOf("width" to "48dp", "height" to "48dp"),
        "width" to mapOf("width" to "120dp"),
        "height" to mapOf("height" to "48dp"),
        "weight" to mapOf("weight" to "1", "fill" to "true"),
        "align" to emptyMap(),
        "background" to mapOf("color" to "#FFEEEEEE", "shape" to "Rectangle"),
        "border" to mapOf("width" to "1dp", "color" to "#FF888888", "shape" to "Rectangle"),
        "graphicsLayer" to LayerDefaults + mapOf("shape" to "Rounded(12dp)", "clip" to "true"),
        "graphicsLayer" to LayerDefaults + mapOf("alpha" to "0.5"),
        "shadow" to mapOf("elevation" to "4dp", "shape" to "Rectangle", "clip" to "false"),
        "offset" to mapOf("x" to "0dp", "y" to "0dp"),
        "aspectRatio" to mapOf("ratio" to "1", "matchHeightConstraintsFirst" to "false"),
        "spoken" to mapOf("label" to "", "role" to "", "heading" to "false", "hidden" to "false", "grouped" to "false"),
    )

    private fun Dp.orZero(): Dp = if (this == Dp.Unspecified) 0.dp else this
}
