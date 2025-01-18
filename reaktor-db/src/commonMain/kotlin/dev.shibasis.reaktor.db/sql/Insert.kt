package dev.shibasis.reaktor.db.sql


class Insert(private val table: Table): Statement() {
    private val assignments = mutableMapOf<Column<*>, Any?>()
    fun <T> set(column: Column<T>, value: T) = apply { assignments[column] = value }

    override fun toSql(): String {
        val columnsPart = assignments.keys.joinToString(", ") { it.name }
        val valuesPart = assignments.entries.joinToString(", ") {
            renderValue(it.value, it.key.type as SqlType<Any?>)
        }
        return "INSERT INTO ${table.tableName} ($columnsPart) VALUES ($valuesPart)"
    }
}