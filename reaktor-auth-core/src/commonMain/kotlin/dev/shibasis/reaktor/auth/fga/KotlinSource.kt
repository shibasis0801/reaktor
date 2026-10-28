package dev.shibasis.reaktor.auth.fga

object FgaKotlin {
    private val taken = setOf(
        "name", "ref", "wildcard", "relations", "relation", "subject", "invoke", "definition", "toString", "hashCode", "equals",
        "direct", "from", "or", "and", "butNot", "tuple", "type",
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null", "object",
        "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while",
    )

    fun print(model: AuthorizationModel, name: String = "AccessModel", packageName: String? = null): String = buildString {
        val objects = objectNames(model, name)
        val properties = model.types.associate { type -> type.name to propertyNames(type) }
        fun ref(ref: TypeRef): String {
            val owner = objects[ref.type] ?: FgaNames.type(ref.type)
            return when {
                ref.wildcard -> "$owner.wildcard"
                ref.relation != null -> "$owner.${properties[ref.type]?.get(ref.relation) ?: ref.relation}"
                else -> owner
            }
        }
        fun expression(type: TypeDefinition, rewrite: Rewrite, nested: Boolean): String {
            val own = properties.getValue(type.name)
            val text = when (rewrite) {
                is Rewrite.Direct -> rewrite.types.joinToString(", ", "direct(", ")") { ref(it) }
                is Rewrite.Computed -> own[rewrite.relation] ?: FgaNames.property(rewrite.relation)
                is Rewrite.From -> {
                    val tupleset = own[rewrite.tupleset] ?: rewrite.tupleset
                    val target = (type.relation(rewrite.tupleset)?.rewrite as? Rewrite.Direct)?.types.orEmpty()
                        .firstOrNull { model.type(it.type)?.relation(rewrite.relation) != null }?.type
                    val inherited = target?.let { "${objects.getValue(it)}.${properties.getValue(it).getValue(rewrite.relation)}" } ?: "\"${rewrite.relation}\""
                    "$inherited from $tupleset"
                }
                is Rewrite.Union -> rewrite.children.joinToString(" or ") { expression(type, it, true) }
                is Rewrite.Intersection -> rewrite.children.joinToString(" and ") { expression(type, it, true) }
                is Rewrite.Exclusion -> "${expression(type, rewrite.base, true)} butNot ${expression(type, rewrite.subtract, true)}"
            }
            val compound = rewrite is Rewrite.Union || rewrite is Rewrite.Intersection || rewrite is Rewrite.Exclusion || rewrite is Rewrite.From
            return if (nested && compound) "($text)" else text
        }
        packageName?.let { append("package ").append(it).append("\n\n") }
        append("import dev.shibasis.reaktor.auth.fga.FgaRelation\n")
        append("import dev.shibasis.reaktor.auth.fga.FgaSchema\n")
        append("import dev.shibasis.reaktor.auth.fga.FgaType\n\n")
        append("object ").append(name).append(" : FgaSchema(").append(if (model.schema == "1.1") "" else "\"${model.schema}\"").append(") {\n")
        model.types.forEach { type ->
            append("    object ").append(objects.getValue(type.name)).append(" : FgaType(\"").append(type.name).append("\")")
            if (type.relations.isEmpty()) {
                append("\n\n")
                return@forEach
            }
            append(" {\n")
            val own = properties.getValue(type.name)
            type.relations.forEach { relation ->
                val property = own.getValue(relation.name)
                val explicit = if (FgaNames.relation(property) == relation.name) "" else "(\"${relation.name}\")"
                append("        val ").append(property).append(": FgaRelation by relation").append(explicit)
                append(" { ").append(expression(type, relation.rewrite, false)).append(" }\n")
            }
            append("    }\n\n")
        }
        append("    override val types get() = listOf(").append(model.types.joinToString { objects.getValue(it.name) }).append(")\n")
        append("}\n")
    }

    private fun objectNames(model: AuthorizationModel, schema: String): Map<String, String> {
        val used = mutableSetOf("FgaSchema", "FgaType", schema)
        return model.types.associate { type ->
            val base = FgaNames.type(type.name)
            val unique = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base$it" }.first { it !in used }
            used += unique
            type.name to unique
        }
    }

    private fun propertyNames(type: TypeDefinition): Map<String, String> {
        val used = mutableSetOf<String>()
        return type.relations.associate { relation ->
            val base = FgaNames.property(relation.name).let { if (it in taken) "${it}Relation" else it }
            val unique = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base$it" }.first { it !in used }
            used += unique
            relation.name to unique
        }
    }
}
