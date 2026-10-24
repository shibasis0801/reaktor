package dev.shibasis.reaktor.surface

enum class FieldType { Text, Role, Ink, Count, Flag, Fraction, Choice, Size, Color, Number, Style, Shape, Picture }

data class FieldSchema(
    val name: String,
    val type: FieldType,
    val default: String? = null,
    val choices: List<String> = emptyList(),
    val code: String? = null,
) {
    fun kotlin(value: String): String? = when {
        code != null -> code.replace("%s", value)
        type == FieldType.Text -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        type == FieldType.Flag || type == FieldType.Count || type == FieldType.Number -> value
        else -> null
    }
}

data class SlotSchema(val name: String, val accepts: Set<String> = emptySet(), val max: Int = Int.MAX_VALUE, val portable: Boolean = true) {
    fun refusal(kind: String, filled: Int): String? = when {
        accepts.isNotEmpty() && kind !in accepts -> "Its $name takes ${accepts.sorted().joinToString()}, not $kind"
        filled >= max -> "Its $name holds at most $max"
        else -> null
    }
}

data class Variant(val name: String, val values: Map<String, String>)

enum class AccessibleName { Content, Label, None }

data class SemanticObligations(val name: AccessibleName = AccessibleName.Content, val role: String? = null, val actions: Set<String> = emptySet(), val reason: String? = null) {
    init {
        require(name != AccessibleName.None || reason != null) { "A component without an accessible name must say why" }
    }
}

data class ComponentContract(
    val id: String,
    val kind: String,
    val version: Int = 1,
    val behavior: Int = 1,
    val fields: List<FieldSchema> = emptyList(),
    val events: List<String> = emptyList(),
    val slots: List<SlotSchema> = emptyList(),
    val variants: List<Variant> = emptyList(),
    val semantics: SemanticObligations = SemanticObligations(),
) {
    fun field(name: String): FieldSchema? = fields.firstOrNull { it.name == name }

    fun slot(name: String?): SlotSchema? = slots.firstOrNull { it.name == name }
}

object SlotKinds {
    val Words: Set<String> = setOf("Text")
    val Label: Set<String> = Words + setOf("Icon", "Row", "Spacer")
    val Glyph: Set<String> = setOf("Icon", "Image", "Drawing")
    val Status: Set<String> = Words + setOf("Badge", "Icon", "Progress", "ProgressIndicator")
    val Controls: Set<String> = Label + setOf("Button", "Switch", "Checkbox", "TextField", "Badge")
}
