package dev.shibasis.reaktor.db.sql

class Delete(private val table: Table): Statement() {
    private var whereClause: Expression? = null
    fun where(expr: Expression) = apply { whereClause = expr }

    override fun toSql(): String {
        val base = "DELETE FROM ${table.tableName}"
        return whereClause?.let { "$base WHERE ${it.toSql()}" } ?: base
    }
}
