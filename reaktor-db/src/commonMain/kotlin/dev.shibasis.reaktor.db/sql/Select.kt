package dev.shibasis.reaktor.db.sql

/*
Follow this https://www.sqlite.org/lang_select.html
And model completely.
 */
class Select: Statement() {
    private val tables = mutableListOf<Table>()
    private val columns = mutableListOf<SqlConstruct>()
    private var whereClause: Expression? = null
    private var groupByCols = mutableListOf<Column<*>>()
    private var havingClause: Expression? = null
    private var limitValue: Int? = null
    private var offsetValue: Int? = null
    private val orderBys = mutableListOf<Pair<Column<*>, SortOrder>>()
    private val joins = mutableListOf<JoinClause>()

    fun from(vararg t: Table) = apply { tables.addAll(t) }
    fun columns(vararg cols: SqlConstruct) = apply { columns.addAll(cols) }
    fun where(expr: Expression) = apply { whereClause = expr }
    fun groupBy(vararg cols: Column<*>) = apply { groupByCols.addAll(cols) }
    fun having(expr: Expression) = apply { havingClause = expr }
    fun orderBy(column: Column<*>, order: SortOrder) = apply { orderBys.add(column to order) }
    fun limit(value: Int) = apply { limitValue = value }
    fun offset(value: Int) = apply { offsetValue = value }
    fun join(table: Table, type: JoinType, on: Expression) = apply { joins.add(JoinClause(table, type, on)) }

    override fun toSql(): String = buildString {
        append("SELECT ")
        append(if (columns.isEmpty()) "*" else columns.joinToString(", ") { it.toSql() })
        append(" FROM ")
        append(tables.joinToString(", ") { it.toSql() })
        joins.forEach { j ->
            append(" ${j.type.keyword} JOIN ${j.table.toSql()} ON ${j.on.toSql()}")
        }
        whereClause?.let { append(" WHERE ").append(it.toSql()) }
        if (groupByCols.isNotEmpty()) {
            append(" GROUP BY ").append(groupByCols.joinToString(", ") { it.toSql() })
        }
        havingClause?.let { append(" HAVING ").append(it.toSql()) }
        if (orderBys.isNotEmpty()) {
            append(" ORDER BY ")
            append(orderBys.joinToString(", ") { "${it.first.toSql()} ${it.second.sql}" })
        }
        limitValue?.let { append(" LIMIT $it") }
        offsetValue?.let { append(" OFFSET $it") }
    }
}