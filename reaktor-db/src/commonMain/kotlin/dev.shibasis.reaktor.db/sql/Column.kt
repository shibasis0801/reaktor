package dev.shibasis.reaktor.db.sql


class Column<T>(
    val name: String,
    val type: SqlType<T>,
    val table: Table,
    val primaryKey: Boolean,
    val notNull: Boolean,
    val defaultValue: T?
) : SqlConstruct {
    override fun toSql(): String = "${table.actualName()}.$name"
}
