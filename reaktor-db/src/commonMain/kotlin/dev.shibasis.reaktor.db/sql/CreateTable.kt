package dev.shibasis.reaktor.db.sql


class CreateTable(private val table: Table): Statement() {
    override fun toSql(): String {
        val colDefs = table.columns.joinToString(", ") { col ->
            val pk = if (col.primaryKey) " PRIMARY KEY" else ""
            val nn = if (col.notNull) " NOT NULL" else ""
            val dv = col.defaultValue?.let { " DEFAULT ${renderValue(it, col.type as SqlType<Any>)}" } ?: ""
            "${col.name} ${col.type.typeName}$pk$nn$dv"
        }
        return "CREATE TABLE IF NOT EXISTS ${table.tableName} ($colDefs)"
    }
}