package dev.shibasis.reaktor.ui.code

/** The classes a line is painted in. Anything unclassified stays [Plain] and is not emitted. */
enum class CodeToken { Plain, Keyword, Type, Function, Builtin, Number, Text, Comment, Doc, Annotation, Operator, Bracket }

data class CodeTokenSpan(val start: Int, val end: Int, val token: CodeToken)

/**
 * One configurable lexer rather than one lexer per language. Every grammar here is line-oriented
 * and carries a small integer across the line break, which is what lets the editor re-colour an
 * edited line without re-reading the file.
 */
class CodeLanguage(
    val id: String,
    val label: String,
    val keywords: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val builtins: Set<String> = emptySet(),
    val lineComment: String? = null,
    val blockComment: Pair<String, String>? = null,
    val nestedBlockComments: Boolean = false,
    val docComment: String? = null,
    val quotes: Set<Char> = setOf('"'),
    val rawQuote: String? = null,
    val annotationPrefix: Char? = null,
    val ignoreKeywordCase: Boolean = false,
    val indent: String = "    ",
) {
    private val keywordSet = if (ignoreKeywordCase) keywords.mapTo(HashSet()) { it.lowercase() } else keywords
    private val typeSet = if (ignoreKeywordCase) types.mapTo(HashSet()) { it.lowercase() } else types
    private val builtinSet = if (ignoreKeywordCase) builtins.mapTo(HashSet()) { it.lowercase() } else builtins

    private fun classify(word: String, followedByCall: Boolean): CodeToken {
        val probe = if (ignoreKeywordCase) word.lowercase() else word
        return when {
            probe in keywordSet -> CodeToken.Keyword
            probe in typeSet -> CodeToken.Type
            probe in builtinSet -> CodeToken.Builtin
            followedByCall -> CodeToken.Function
            word.first().isUpperCase() && word.any { it.isLowerCase() } -> CodeToken.Type
            else -> CodeToken.Plain
        }
    }

    /**
     * Colours one line and returns the state to feed into the next one. [emit] receives only
     * non-plain runs, in order, which is exactly what an annotated string needs.
     */
    fun lex(line: String, entry: Int = 0, emit: (Int, Int, CodeToken) -> Unit): Int {
        var mode = entry and 0b11
        var doc = entry and DocBit != 0
        var depth = entry shr 3
        var index = 0

        while (index < line.length) {
            when (mode) {
                ModeBlock -> {
                    val (open, close) = blockComment ?: return Code
                    val from = index
                    while (index < line.length) {
                        if (line.startsWith(close, index)) {
                            index += close.length
                            depth--
                            if (depth <= 0) { mode = ModeCode; depth = 0; break }
                        } else if (nestedBlockComments && line.startsWith(open, index)) {
                            index += open.length
                            depth++
                        } else index++
                    }
                    emit(from, index, if (doc) CodeToken.Doc else CodeToken.Comment)
                    if (mode == ModeCode) doc = false
                }

                ModeRaw -> {
                    val raw = rawQuote ?: return Code
                    val from = index
                    val close = line.indexOf(raw, index)
                    index = if (close < 0) line.length else close + raw.length
                    if (close >= 0) mode = ModeCode
                    emit(from, index, CodeToken.Text)
                }

                else -> {
                    val char = line[index]
                    when {
                        char.isWhitespace() -> index++

                        lineComment != null && line.startsWith(lineComment, index) -> {
                            emit(index, line.length, CodeToken.Comment)
                            index = line.length
                        }

                        blockComment != null && line.startsWith(blockComment.first, index) -> {
                            doc = docComment != null && line.startsWith(docComment, index)
                            depth = 1
                            mode = ModeBlock
                            emit(index, index + blockComment.first.length, if (doc) CodeToken.Doc else CodeToken.Comment)
                            index += blockComment.first.length
                        }

                        rawQuote != null && line.startsWith(rawQuote, index) -> {
                            mode = ModeRaw
                            emit(index, index + rawQuote.length, CodeToken.Text)
                            index += rawQuote.length
                        }

                        char in quotes -> {
                            val end = scanQuoted(line, index, char)
                            emit(index, end, CodeToken.Text)
                            index = end
                        }

                        char.isDigit() -> {
                            val end = scanNumber(line, index)
                            emit(index, end, CodeToken.Number)
                            index = end
                        }

                        char == annotationPrefix && index + 1 < line.length && line[index + 1].isIdentifierStart() -> {
                            var end = index + 1
                            while (end < line.length && line[end].isIdentifierPart()) end++
                            emit(index, end, CodeToken.Annotation)
                            index = end
                        }

                        char.isIdentifierStart() -> {
                            var end = index
                            while (end < line.length && line[end].isIdentifierPart()) end++
                            val word = line.substring(index, end)
                            var probe = end
                            while (probe < line.length && line[probe] == ' ') probe++
                            val token = classify(word, probe < line.length && line[probe] == '(')
                            if (token != CodeToken.Plain) emit(index, end, token)
                            index = end
                        }

                        char in Brackets -> { emit(index, index + 1, CodeToken.Bracket); index++ }

                        char in Operators -> {
                            var end = index
                            while (end < line.length && line[end] in Operators) end++
                            emit(index, end, CodeToken.Operator)
                            index = end
                        }

                        else -> index++
                    }
                }
            }
        }
        return mode or (if (doc) DocBit else 0) or (depth shl 3)
    }

    fun spans(line: String, entry: Int = 0): List<CodeTokenSpan> =
        buildList { lex(line, entry) { start, end, token -> add(CodeTokenSpan(start, end, token)) } }

    /** The cheap path when only the carry-over matters, as when re-validating lines above the viewport. */
    fun state(line: String, entry: Int = 0): Int = lex(line, entry) { _, _, _ -> }

    private fun scanQuoted(line: String, start: Int, quote: Char): Int {
        var index = start + 1
        while (index < line.length) {
            when (line[index]) {
                '\\' -> index += 2
                quote -> return index + 1
                else -> index++
            }
        }
        return line.length
    }

    private fun scanNumber(line: String, start: Int): Int {
        var index = start
        if (line.startsWith("0x", index) || line.startsWith("0X", index) ||
            line.startsWith("0b", index) || line.startsWith("0B", index)
        ) index += 2
        while (index < line.length) {
            val char = line[index]
            val exponent = (char == '+' || char == '-') && index > start && (line[index - 1] == 'e' || line[index - 1] == 'E')
            val fraction = char == '.' && index + 1 < line.length && line[index + 1].isDigit()
            if (char.isLetterOrDigit() || char == '_' || fraction || exponent) index++ else break
        }
        return index
    }

    companion object {
        private const val ModeCode = 0
        private const val ModeBlock = 1
        private const val ModeRaw = 2
        private const val DocBit = 0b100

        /** The state a line inherits when nothing is open above it. */
        const val Code = 0

        private const val Brackets = "()[]{}"
        private const val Operators = "+-*/%=<>!&|^~?:.,;@#$"

        val Plain = CodeLanguage(id = "plaintext", label = "Text")

        val Kotlin = CodeLanguage(
            id = "kotlin", label = "Kotlin",
            keywords = setOf(
                "package", "import", "class", "interface", "object", "fun", "val", "var", "typealias",
                "if", "else", "when", "while", "for", "do", "return", "break", "continue",
                "try", "catch", "finally", "throw", "is", "as", "in", "out", "by", "where",
                "get", "set", "init", "constructor", "this", "super", "null", "true", "false",
                "companion", "data", "enum", "sealed", "annotation", "inner", "value", "expect", "actual",
                "external", "override", "open", "final", "abstract", "private", "public", "internal",
                "protected", "lateinit", "const", "suspend", "inline", "noinline", "crossinline",
                "reified", "operator", "infix", "tailrec", "vararg", "field", "it",
            ),
            types = setOf(
                "Int", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Char", "String",
                "Unit", "Any", "Nothing", "Array", "List", "Map", "Set", "MutableList", "MutableMap",
                "MutableSet", "Pair", "Triple", "Sequence", "Flow", "StateFlow", "Result", "Throwable",
            ),
            builtins = setOf(
                "println", "print", "require", "requireNotNull", "check", "checkNotNull", "error", "TODO",
                "listOf", "mapOf", "setOf", "mutableListOf", "mutableMapOf", "mutableSetOf", "arrayOf",
                "buildList", "buildString", "let", "run", "apply", "also", "with", "takeIf", "takeUnless", "lazy",
            ),
            lineComment = "//", blockComment = "/*" to "*/", nestedBlockComments = true, docComment = "/**",
            quotes = setOf('"', '\''), rawQuote = "\"\"\"", annotationPrefix = '@',
        )

        val TypeScript = CodeLanguage(
            id = "typescript", label = "TypeScript",
            keywords = setOf(
                "const", "let", "var", "function", "class", "interface", "type", "enum", "import",
                "export", "from", "default", "return", "if", "else", "for", "while", "do", "switch",
                "case", "break", "continue", "new", "this", "super", "extends", "implements",
                "async", "await", "yield", "try", "catch", "finally", "throw", "typeof", "instanceof",
                "in", "of", "as", "null", "undefined", "true", "false", "void", "public", "private",
                "protected", "readonly", "static", "abstract", "declare", "namespace", "satisfies", "keyof",
            ),
            types = setOf("string", "number", "boolean", "object", "symbol", "bigint", "unknown", "never", "any",
                "Array", "Promise", "Record", "Partial", "Readonly", "Map", "Set"),
            builtins = setOf("console", "JSON", "Math", "Object", "Array", "String", "Number", "fetch"),
            lineComment = "//", blockComment = "/*" to "*/", docComment = "/**",
            quotes = setOf('"', '\'', '`'), annotationPrefix = '@', indent = "  ",
        )

        val Sql = CodeLanguage(
            id = "sql", label = "SQL",
            keywords = setOf(
                "select", "from", "where", "join", "left", "right", "inner", "outer", "full", "cross",
                "on", "group", "by", "order", "having", "limit", "offset", "insert", "into", "values",
                "update", "set", "delete", "create", "table", "view", "materialized", "index", "drop",
                "alter", "add", "column", "as", "and", "or", "not", "null", "is", "in", "like", "ilike",
                "between", "exists", "union", "intersect", "except", "all", "distinct", "case", "when",
                "then", "else", "end", "with", "recursive", "returning", "primary", "key", "foreign",
                "references", "default", "constraint", "unique", "check", "cascade", "asc", "desc",
                "over", "partition", "window", "true", "false", "using", "explain", "analyze",
            ),
            types = setOf("int", "integer", "bigint", "smallint", "serial", "text", "varchar", "char",
                "boolean", "date", "timestamp", "timestamptz", "numeric", "decimal", "real", "jsonb",
                "json", "uuid", "bytea", "interval", "array"),
            builtins = setOf("count", "sum", "avg", "min", "max", "coalesce", "nullif", "cast", "now",
                "greatest", "least", "row_number", "rank", "dense_rank", "lag", "lead", "generate_series"),
            lineComment = "--", blockComment = "/*" to "*/", quotes = setOf('\'', '"'),
            ignoreKeywordCase = true, indent = "  ",
        )

        val Cypher = CodeLanguage(
            id = "cypher", label = "Cypher",
            keywords = setOf(
                "match", "optional", "where", "return", "with", "unwind", "create", "merge", "set",
                "delete", "detach", "remove", "order", "by", "skip", "limit", "as", "and", "or", "xor",
                "not", "in", "starts", "ends", "contains", "null", "true", "false", "call", "yield",
                "union", "distinct", "case", "when", "then", "else", "end", "foreach", "on", "index",
                "constraint", "exists", "asc", "desc", "profile", "explain",
            ),
            builtins = setOf("count", "collect", "sum", "avg", "min", "max", "size", "labels", "type",
                "id", "keys", "properties", "coalesce", "toInteger", "toFloat", "toString", "range"),
            lineComment = "//", blockComment = "/*" to "*/", quotes = setOf('\'', '"'),
            ignoreKeywordCase = true, indent = "  ",
        )

        val Json = CodeLanguage(
            id = "json", label = "JSON",
            keywords = setOf("true", "false", "null"), indent = "  ",
        )

        val Markdown = CodeLanguage(id = "markdown", label = "Markdown", indent = "  ")

        val all = listOf(Kotlin, TypeScript, Sql, Cypher, Json, Markdown, Plain)

        fun forId(id: String): CodeLanguage = all.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: Plain

        fun forPath(path: String): CodeLanguage = when (path.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> Kotlin
            "ts", "tsx", "js", "jsx", "mjs", "cjs" -> TypeScript
            "sql" -> Sql
            "cypher", "cql" -> Cypher
            "json", "jsonc" -> Json
            "md", "markdown", "mdx" -> Markdown
            else -> Plain
        }
    }
}

private fun Char.isIdentifierStart() = isLetter() || this == '_' || this == '$'

private fun Char.isIdentifierPart() = isLetterOrDigit() || this == '_' || this == '$'
