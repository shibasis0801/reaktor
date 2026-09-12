package dev.shibasis.reaktor.tooling.database

object SqlReadStatement {
    private val forbiddenWords = setOf(
        "alter", "analyze", "attach", "begin", "call", "commit", "copy", "create", "delete",
        "detach", "do", "drop", "execute", "grant", "insert", "into", "lock", "merge", "pragma",
        "reindex", "release", "replace", "reset", "revoke", "rollback", "savepoint", "set", "truncate",
        "update", "upsert", "vacuum",
    )
    private val forbiddenFunctions = setOf(
        "dblink", "lo_export", "lo_import", "nextval", "pg_advisory_lock", "pg_advisory_lock_shared",
        "pg_cancel_backend", "pg_create_logical_replication_slot", "pg_reload_conf", "pg_terminate_backend",
        "readfile", "set_config", "setval", "writefile",
    )

    fun normalize(value: String): String {
        require(value.isNotBlank()) { "Enter a read query" }
        require('\u0000' !in value) { "Query text cannot contain a NUL byte" }
        require(value.encodeToByteArray().size <= 262_144) { "Query exceeds the 256 KiB limit" }
        val output = StringBuilder(value.length)
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var index = 0
        var state = LexicalState.Normal
        var dollarDelimiter: String? = null
        var blockDepth = 0
        val normalSemicolons = mutableListOf<Int>()

        fun finishWord() {
            if (word.isNotEmpty()) {
                words += word.toString().lowercase()
                word.clear()
            }
        }

        while (index < value.length) {
            val current = value[index]
            when (state) {
                LexicalState.Normal -> when {
                    current == '-' && value.getOrNull(index + 1) == '-' -> {
                        finishWord()
                        output.append(' ')
                        state = LexicalState.LineComment
                        index += 2
                        continue
                    }
                    current == '/' && value.getOrNull(index + 1) == '*' -> {
                        finishWord()
                        output.append(' ')
                        state = LexicalState.BlockComment
                        blockDepth = 1
                        index += 2
                        continue
                    }
                    current == '\'' -> {
                        finishWord()
                        output.append(current)
                        state = LexicalState.SingleQuote
                    }
                    current == '"' -> {
                        finishWord()
                        output.append(current)
                        state = LexicalState.DoubleQuote
                    }
                    current == '`' -> {
                        finishWord()
                        output.append(current)
                        state = LexicalState.BacktickQuote
                    }
                    current == '$' -> {
                        val delimiter = dollarDelimiterAt(value, index)
                        if (delimiter != null) {
                            finishWord()
                            output.append(delimiter)
                            dollarDelimiter = delimiter
                            state = LexicalState.DollarQuote
                            index += delimiter.length
                            continue
                        }
                        output.append(current)
                    }
                    current == '\\' -> error("Database client meta-commands are not allowed")
                    current == ';' -> {
                        finishWord()
                        normalSemicolons += output.length
                        output.append(current)
                    }
                    current.isLetterOrDigit() || current == '_' -> {
                        word.append(current)
                        output.append(current)
                    }
                    else -> {
                        finishWord()
                        output.append(current)
                    }
                }
                LexicalState.LineComment -> if (current == '\n') {
                    output.append('\n')
                    state = LexicalState.Normal
                }
                LexicalState.BlockComment -> when {
                    current == '/' && value.getOrNull(index + 1) == '*' -> {
                        blockDepth++
                        index++
                    }
                    current == '*' && value.getOrNull(index + 1) == '/' -> {
                        blockDepth--
                        index++
                        if (blockDepth == 0) state = LexicalState.Normal
                    }
                }
                LexicalState.SingleQuote -> {
                    output.append(current)
                    if (current == '\'' && value.getOrNull(index + 1) == '\'') {
                        output.append('\'')
                        index++
                    } else if (current == '\'') {
                        state = LexicalState.Normal
                    }
                }
                LexicalState.DoubleQuote -> {
                    output.append(current)
                    if (current == '"' && value.getOrNull(index + 1) == '"') {
                        output.append('"')
                        index++
                    } else if (current == '"') {
                        state = LexicalState.Normal
                    }
                }
                LexicalState.BacktickQuote -> {
                    output.append(current)
                    if (current == '`' && value.getOrNull(index + 1) == '`') {
                        output.append('`')
                        index++
                    } else if (current == '`') {
                        state = LexicalState.Normal
                    }
                }
                LexicalState.DollarQuote -> {
                    val delimiter = checkNotNull(dollarDelimiter)
                    if (value.startsWith(delimiter, index)) {
                        output.append(delimiter)
                        index += delimiter.length
                        state = LexicalState.Normal
                        dollarDelimiter = null
                        continue
                    }
                    output.append(current)
                }
            }
            index++
        }
        finishWord()
        require(state == LexicalState.Normal || state == LexicalState.LineComment) {
            "Query contains an unterminated quote or block comment"
        }
        val rawStatement = output.toString()
        require(normalSemicolons.size <= 1 && (
            normalSemicolons.isEmpty() || rawStatement.substring(normalSemicolons.single() + 1).isBlank()
        )) {
            "Database Studio executes exactly one statement"
        }
        val statement = normalSemicolons.singleOrNull()
            ?.let { offset -> rawStatement.removeRange(offset, offset + 1) }
            ?.trim()
            ?: rawStatement.trim()
        require(statement.isNotBlank()) { "Enter a read query" }
        require(words.firstOrNull() in setOf("select", "with", "values")) {
            "Database Studio accepts only SELECT, WITH, or VALUES queries"
        }
        val forbidden = words.firstOrNull { it in forbiddenWords || it in forbiddenFunctions }
        require(forbidden == null) { "Read query contains forbidden operation '$forbidden'" }
        require(!("for" in words && ("share" in words || "key" in words))) {
            "Locking SELECT clauses are not allowed"
        }
        return statement
    }

    private fun dollarDelimiterAt(value: String, start: Int): String? {
        var index = start + 1
        while (index < value.length && (value[index].isLetterOrDigit() || value[index] == '_')) index++
        if (value.getOrNull(index) != '$') return null
        val tag = value.substring(start + 1, index)
        if (tag.isNotEmpty() && (tag.first().isDigit() || tag.any { !it.isLetterOrDigit() && it != '_' })) return null
        return value.substring(start, index + 1)
    }

    private enum class LexicalState { Normal, LineComment, BlockComment, SingleQuote, DoubleQuote, BacktickQuote, DollarQuote }
}
