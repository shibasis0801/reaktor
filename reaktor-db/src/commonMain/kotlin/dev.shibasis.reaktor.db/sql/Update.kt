package dev.shibasis.reaktor.db.sql


class Update(private val table: Table): Statement() {
    private val assignments = mutableMapOf<Column<*>, Any?>()
    private var whereClause: Expression? = null

    fun <T> set(column: Column<T>, value: T) = apply { assignments[column] = value }
    fun where(expr: Expression) = apply { whereClause = expr }

    override fun toSql(): String {
        val setPart = assignments.entries.joinToString(", ") {
            "${it.key.name} = ${renderValue(it.value, it.key.type as SqlType<Any?>)}"
        }
        val base = "UPDATE ${table.tableName} SET $setPart"
        return whereClause?.let { "$base WHERE ${it.toSql()}" } ?: base
    }
}
