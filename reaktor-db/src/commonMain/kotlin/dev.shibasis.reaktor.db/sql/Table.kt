package dev.shibasis.reaktor.db.sql

open class Table(val tableName: String, val alias: String? = null): SqlConstruct {
    private val _columns = mutableListOf<Column<*>>()
    val columns: List<Column<*>> get() = _columns

    fun <T> column(
        colName: String,
        type: SqlType<T>,
        primaryKey: Boolean = false,
        notNull: Boolean = false,
        defaultValue: T? = null
    ): Column<T> {
        val c = Column(colName, type, this, primaryKey, notNull, defaultValue)
        _columns.add(c)
        return c
    }

    override fun toSql() = alias?.let { "$tableName AS $it" } ?: tableName
    fun actualName() = alias ?: tableName
}
