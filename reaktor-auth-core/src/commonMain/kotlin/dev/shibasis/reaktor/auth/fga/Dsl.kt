package dev.shibasis.reaktor.auth.fga

data class DslIssue(val line: Int, val column: Int, val message: String) {
    override fun toString(): String = "$line:$column $message"
}

data class DslResult(val model: AuthorizationModel?, val issues: List<DslIssue>) {
    val ok: Boolean get() = model != null && issues.isEmpty()
}

object FgaDsl {
    fun parse(text: String): DslResult {
        val issues = mutableListOf<DslIssue>()
        val types = mutableListOf<TypeDefinition>()
        var schema: String? = null
        var sawModel = false
        var current: TypeDefinition? = null
        var inRelations = false
        fun close() {
            current?.let(types::add)
            current = null
            inRelations = false
        }
        text.lines().forEachIndexed { index, raw ->
            val number = index + 1
            val line = stripComment(raw)
            if (line.isBlank()) return@forEachIndexed
            val indent = line.indexOfFirst { !it.isWhitespace() } + 1
            val content = line.trim()
            val word = content.substringBefore(' ')
            when {
                content == "model" -> sawModel = true
                word == "schema" -> {
                    schema = content.substringAfter(' ').trim()
                    if (schema != "1.1") issues += DslIssue(number, indent, "Only schema 1.1 is supported, not $schema")
                }
                word == "type" -> {
                    close()
                    val name = content.substringAfter(' ').trim()
                    if (!Names.declarable(name)) issues += DslIssue(number, indent + 5, "\"$name\" is not a valid type name")
                    current = TypeDefinition(name)
                }
                content == "relations" -> if (current == null) issues += DslIssue(number, indent, "relations must follow a type")
                    else inRelations = true
                word == "define" -> {
                    val owner = current
                    if (owner == null || !inRelations) {
                        issues += DslIssue(number, indent, "define must sit under a type's relations")
                        return@forEachIndexed
                    }
                    val colon = content.indexOf(':')
                    if (colon < 0) {
                        issues += DslIssue(number, indent + content.length, "Expected ':' after the relation name")
                        return@forEachIndexed
                    }
                    val name = content.substring("define".length, colon).trim()
                    if (!Names.relation(name)) issues += DslIssue(number, indent + 7, "\"$name\" is not a valid relation name")
                    if (owner.relation(name) != null) issues += DslIssue(number, indent + 7, "${owner.name} defines $name twice")
                    val start = indent + colon + 1
                    Expression(content.substring(colon + 1), number, start).parse()
                        .onSuccess { current = owner.copy(relations = owner.relations + RelationDefinition(name, it)) }
                        .onFailure { issues += (it as? DslFailure)?.issue ?: DslIssue(number, start, it.message ?: "Invalid relation") }
                }
                word == "condition" -> issues += DslIssue(number, indent, "Conditions are not supported yet")
                word == "module" || word == "extend" -> issues += DslIssue(number, indent, "Modular models are not supported yet")
                else -> issues += DslIssue(number, indent, "Unexpected \"$content\"")
            }
        }
        close()
        if (!sawModel) issues.add(0, DslIssue(1, 1, "The model must start with \"model\""))
        if (schema == null) issues += DslIssue(1, 1, "The model must declare \"schema 1.1\"")
        types.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach { issues += DslIssue(1, 1, "Type $it is defined twice") }
        return DslResult(if (issues.isEmpty()) AuthorizationModel(types) else null, issues)
    }

    fun print(model: AuthorizationModel): String = buildString {
        append("model\n  schema ").append(model.schema).append('\n')
        model.types.forEach { type ->
            append("\ntype ").append(type.name).append('\n')
            if (type.relations.isNotEmpty()) {
                append("  relations\n")
                type.relations.forEach { append("    define ").append(it.name).append(": ").append(print(it.rewrite)).append('\n') }
            }
        }
    }

    fun print(rewrite: Rewrite): String = when (rewrite) {
        is Rewrite.Direct -> rewrite.types.joinToString(", ", "[", "]")
        is Rewrite.Computed -> rewrite.relation
        is Rewrite.From -> "${rewrite.relation} from ${rewrite.tupleset}"
        is Rewrite.Union -> rewrite.children.sortedBy { it !is Rewrite.Direct }.joinToString(" or ") { nested(it) }
        is Rewrite.Intersection -> rewrite.children.joinToString(" and ") { nested(it) }
        is Rewrite.Exclusion -> "${nested(rewrite.base)} but not ${nested(rewrite.subtract)}"
    }

    fun parseRewrite(text: String): Result<Rewrite> = Expression(text, 1, 1).parse()

    private fun nested(rewrite: Rewrite): String = when (rewrite) {
        is Rewrite.Union, is Rewrite.Intersection, is Rewrite.Exclusion -> "(${print(rewrite)})"
        else -> print(rewrite)
    }

    private fun stripComment(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("#")) return ""
        val at = line.indices.firstOrNull { line[it] == '#' && it > 0 && line[it - 1].isWhitespace() } ?: return line
        return line.substring(0, at)
    }
}

private class DslFailure(val issue: DslIssue) : IllegalArgumentException(issue.message)

private class Expression(private val text: String, private val line: Int, private val offset: Int) {
    private data class Token(val text: String, val column: Int)

    private val tokens: List<Token> = buildList {
        var at = 0
        while (at < text.length) {
            val char = text[at]
            when {
                char.isWhitespace() -> at++
                char in "[]()," -> {
                    add(Token(char.toString(), at))
                    at++
                }
                else -> {
                    val start = at
                    while (at < text.length && !text[at].isWhitespace() && text[at] !in "[](),") at++
                    add(Token(text.substring(start, at), start))
                }
            }
        }
    }
    private var position = 0

    fun parse(): Result<Rewrite> = runCatching {
        if (tokens.isEmpty()) fail(text.length, "A relation needs a definition")
        val rewrite = expression()
        peek()?.let { fail(it.column, "Unexpected \"${it.text}\"") }
        rewrite
    }

    private fun expression(): Rewrite {
        val first = group()
        return when (peek()?.text) {
            "or" -> Rewrite.Union(listOf(first) + repeated("or"))
            "and" -> Rewrite.Intersection(listOf(first) + repeated("and"))
            "but" -> {
                next()
                expect("not")
                val subtract = group()
                peek()?.takeIf { it.text in setOf("or", "and", "but") }?.let { fail(it.column, "Wrap mixed operators in parentheses") }
                Rewrite.Exclusion(first, subtract)
            }
            else -> first
        }
    }

    private fun repeated(operator: String): List<Rewrite> = buildList {
        while (peek()?.text == operator) {
            next()
            add(group())
        }
        peek()?.takeIf { it.text in setOf("or", "and", "but") }?.let { fail(it.column, "Wrap mixed operators in parentheses") }
    }

    private fun group(): Rewrite {
        val token = next() ?: fail(text.length, "Expected a relation, a [type list] or a (group)")
        return when (token.text) {
            "[" -> direct()
            "(" -> expression().also { expect(")") }
            "or", "and", "but", "not", "from", ")", "]", "," -> fail(token.column, "Expected a relation before \"${token.text}\"")
            else -> {
                if (!Names.relation(token.text)) fail(token.column, "\"${token.text}\" is not a valid relation name")
                if (peek()?.text == "from") {
                    next()
                    val tupleset = next() ?: fail(text.length, "Expected a relation after \"from\"")
                    if (!Names.relation(tupleset.text)) fail(tupleset.column, "\"${tupleset.text}\" is not a valid relation name")
                    Rewrite.From(token.text, tupleset.text)
                } else Rewrite.Computed(token.text)
            }
        }
    }

    private fun direct(): Rewrite {
        val types = mutableListOf<TypeRef>()
        while (true) {
            val token = next() ?: fail(text.length, "Unclosed type list")
            if (token.text == "]" && types.isEmpty()) fail(token.column, "A type list needs at least one type")
            types += typeRef(token)
            if (peek()?.text == "with") fail(peek()!!.column, "Conditions are not supported yet")
            when (next()?.text) {
                "," -> continue
                "]" -> return Rewrite.Direct(types)
                else -> fail(text.length, "Expected ',' or ']' in the type list")
            }
        }
    }

    private fun typeRef(token: Token): TypeRef {
        val value = token.text
        val ref = when {
            value.endsWith(":*") -> TypeRef(value.removeSuffix(":*"), wildcard = true)
            '#' in value -> TypeRef(value.substringBefore('#'), value.substringAfter('#'))
            else -> TypeRef(value)
        }
        if (!Names.type(ref.type) || ref.relation?.let(Names::relation) == false) fail(token.column, "\"$value\" is not a valid type reference")
        return ref
    }

    private fun expect(text: String) {
        val token = next()
        if (token?.text != text) fail(token?.column ?: this.text.length, "Expected \"$text\"")
    }

    private fun peek(): Token? = tokens.getOrNull(position)

    private fun next(): Token? = tokens.getOrNull(position)?.also { position++ }

    private fun fail(column: Int, message: String): Nothing = throw DslFailure(DslIssue(line, offset + column, message))
}
