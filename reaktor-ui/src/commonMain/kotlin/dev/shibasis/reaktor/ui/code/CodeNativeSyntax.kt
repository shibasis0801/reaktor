package dev.shibasis.reaktor.ui.code

internal data class CodeString(
    val delimiter: String,
    val escaped: Boolean,
    val multiline: Boolean,
    val openingLength: Int = delimiter.length,
) : CodeLexState

internal object CppLanguage : CodeLanguage(
    id = "cpp", label = "C++",
    keywords = setOf(
        "alignas", "alignof", "and", "and_eq", "asm", "auto", "bitand", "bitor", "break",
        "case", "catch", "class", "compl", "concept", "const", "consteval", "constexpr",
        "constinit", "const_cast", "continue", "co_await", "co_return", "co_yield", "decltype",
        "default", "delete", "do", "dynamic_cast", "else", "enum", "explicit", "export",
        "extern", "false", "final", "for", "friend", "goto", "if", "inline", "mutable",
        "namespace", "new", "noexcept", "not", "not_eq", "nullptr", "operator", "or", "or_eq",
        "override", "private", "protected", "public", "register", "reinterpret_cast", "requires",
        "return", "sizeof", "static", "static_assert", "static_cast", "struct", "switch",
        "template", "this", "thread_local", "throw", "true", "try", "typedef", "typeid",
        "typename", "union", "using", "virtual", "volatile", "while", "xor", "xor_eq",
        "module", "import",
    ),
    types = setOf("bool", "char", "char8_t", "char16_t", "char32_t", "double", "float", "int",
        "long", "short", "signed", "unsigned", "void", "wchar_t", "size_t", "ptrdiff_t"),
    lineComment = "//", blockComment = "/*" to "*/", docComment = "/**",
    quotes = setOf('"', '\''), annotationPrefix = '#',
) {
    override fun stringAt(line: String, index: Int): CodeString? {
        var quote = index
        if (line.startsWith("u8", quote)) quote += 2
        else if (line[quote] in "uUL") quote++
        if (quote < line.length && line.startsWith("R\"", quote)) {
            val start = quote + 2
            var end = start
            while (end < line.length && end - start <= 16) {
                val char = line[end]
                if (char == '(') return CodeString(
                    ")" + line.substring(start, end) + "\"", escaped = false, multiline = true,
                    openingLength = end + 1 - index,
                )
                if (char.isWhitespace() || char == ')' || char == '\\' || char.code !in 33..126) break
                end++
            }
        }
        if (quote < line.length && line[quote] in quotes) return CodeString(
            line[quote].toString(), escaped = true, multiline = false, openingLength = quote + 1 - index,
        )
        return super.stringAt(line, index)
    }

    override fun scanNumber(line: String, start: Int): Int {
        var end = start + 1
        while (end < line.length) {
            val char = line[end]
            val exponent = char in "+-" && line[end - 1] in "eEpP"
            val separator = char == '\'' && end + 1 < line.length && line[end + 1].isLetterOrDigit()
            if (char.isLetterOrDigit() || char == '_' || char == '.' || exponent || separator) end++ else break
        }
        return end
    }
}

internal object OpenFgaLanguage : CodeLanguage(
    id = "openfga", label = "OpenFGA",
    keywords = setOf("model", "schema", "type", "relation", "relations", "define", "and", "or",
        "but", "not", "from", "with", "condition", "module", "extend", "in", "true", "false", "null"),
    types = setOf("bool", "string", "int", "uint", "double", "duration", "timestamp", "ipaddress", "map", "list"),
    quotes = setOf('"', '\''), lineComment = "#", indent = "  ",
) {
    override fun commentAt(line: String, index: Int): Boolean =
        line.startsWith("//", index) || (line[index] == '#' && (index == 0 || line[index - 1].isWhitespace()))

    override fun wordEnd(line: String, start: Int): Int {
        var end = start + 1
        while (end < line.length) {
            val char = line[end]
            if (char.isLetterOrDigit() || char == '_') end++
            else if (char in "-./" && end + 1 < line.length && (line[end + 1].isLetterOrDigit() || line[end + 1] == '_')) end++
            else break
        }
        return end
    }

    override fun stringAt(line: String, index: Int): CodeString? {
        var quote = index
        if (line[quote] in "bB") quote++
        val raw = quote < line.length && line[quote] in "rR"
        if (raw) quote++
        if (quote >= line.length || line[quote] !in quotes) return null
        val char = line[quote]
        val triple = quote + 2 < line.length && line[quote + 1] == char && line[quote + 2] == char
        val delimiter = if (triple) char.toString().repeat(3) else char.toString()
        return CodeString(delimiter, escaped = !raw, multiline = triple, openingLength = quote - index + delimiter.length)
    }
}
