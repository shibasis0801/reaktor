package dev.shibasis.reaktor.cloudflare

private val sqlIdentifierPattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

internal enum class SqlDialect {
    Postgres,
    Sqlite,
}

class SqlIdentifier internal constructor(
    internal val value: String,
)

class SqlValues internal constructor(
    internal val values: Array<out Any?>,
)

fun sqlIdentifier(value: String): SqlIdentifier {
    require(sqlIdentifierPattern.matches(value)) {
        "Invalid SQL identifier '$value'"
    }
    return SqlIdentifier(value)
}

fun sqlValues(vararg values: Any?): SqlValues = SqlValues(values)

internal fun StringBuilder.appendIdentifier(identifier: SqlIdentifier) {
    append('"')
    append(identifier.value)
    append('"')
}

private fun StringBuilder.appendValue(
    value: Any?,
    params: MutableList<Any?>,
    dialect: SqlDialect,
) {
    params += value
    when (dialect) {
        SqlDialect.Postgres -> {
            append("$")
            append(params.size)
        }

        SqlDialect.Sqlite -> append("?")
    }
}

private fun Any?.asSqlIdentifier(): SqlIdentifier =
    when (this) {
        is SqlIdentifier -> this
        is String -> sqlIdentifier(this)
        else -> error("SQL template expected an identifier but got ${this?.let { it::class.simpleName } ?: "null"}")
    }

private fun Any?.asSqlValues(): List<Any?> =
    when (this) {
        is SqlValues -> values.toList()
        is Array<*> -> toList()
        is Iterable<*> -> toList()
        else -> error("SQL template expected a value list but got ${this?.let { it::class.simpleName } ?: "null"}")
    }

private fun Array<out Any?>.requireArgument(index: Int): Any? =
    getOrNull(index) ?: error("Missing SQL template argument at index $index")

class SqlStatement internal constructor(
    internal val query: String,
    internal val params: Array<Any?>,
)

class SqlBuilder internal constructor(
    private val dialect: SqlDialect,
) {
    private val query = StringBuilder()
    private val params = mutableListOf<Any?>()

    operator fun String.unaryPlus() {
        query.append(this)
    }

    fun value(value: Any?) {
        query.appendValue(value, params, dialect)
    }

    fun values(vararg arguments: Any?) {
        appendValues(arguments.asList())
    }

    fun identifier(identifier: SqlIdentifier) {
        query.appendIdentifier(identifier)
    }

    fun identifier(name: String) {
        identifier(sqlIdentifier(name))
    }

    private fun appendValues(arguments: List<Any?>) {
        require(arguments.isNotEmpty()) { "SQL value lists cannot be empty" }
        arguments.forEachIndexed { index, argument ->
            if (index > 0) {
                query.append(", ")
            }
            value(argument)
        }
    }

    internal fun build(): SqlStatement = SqlStatement(query.toString(), params.toTypedArray())
}

private fun templateQuery(
    dialect: SqlDialect,
    template: String,
    arguments: Array<out Any?>,
): SqlStatement {
    val query = StringBuilder()
    val params = mutableListOf<Any?>()
    var argumentIndex = 0
    var index = 0

    while (index < template.length) {
        val current = template[index]
        if (current != '%') {
            query.append(current)
            index += 1
            continue
        }

        require(index + 1 < template.length) { "Dangling % at end of SQL template" }
        when (val token = template[index + 1]) {
            '%' -> query.append('%')
            'I' -> query.appendIdentifier(arguments.requireArgument(argumentIndex++).asSqlIdentifier())
            'V' -> query.appendValue(arguments.requireArgument(argumentIndex++), params, dialect)
            'L' -> {
                val values = arguments.requireArgument(argumentIndex++).asSqlValues()
                require(values.isNotEmpty()) { "SQL value lists cannot be empty" }
                values.forEachIndexed { valueIndex, value ->
                    if (valueIndex > 0) {
                        query.append(", ")
                    }
                    query.appendValue(value, params, dialect)
                }
            }

            else -> error("Unsupported SQL template token %$token")
        }
        index += 2
    }

    require(argumentIndex == arguments.size) {
        "Unused SQL template arguments: expected $argumentIndex but got ${arguments.size}"
    }

    return SqlStatement(query.toString(), params.toTypedArray())
}

fun postgresQuery(build: SqlBuilder.() -> Unit): SqlStatement =
    SqlBuilder(SqlDialect.Postgres).apply(build).build()

fun postgresQuery(
    template: String,
    vararg arguments: Any?,
): SqlStatement = templateQuery(SqlDialect.Postgres, template, arguments)

fun d1Query(build: SqlBuilder.() -> Unit): SqlStatement =
    SqlBuilder(SqlDialect.Sqlite).apply(build).build()

fun d1Query(
    template: String,
    vararg arguments: Any?,
): SqlStatement = templateQuery(SqlDialect.Sqlite, template, arguments)
