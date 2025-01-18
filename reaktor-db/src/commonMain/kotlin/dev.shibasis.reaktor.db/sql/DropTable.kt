package dev.shibasis.reaktor.db.sql



class DropTable(private val table: Table): Statement() {
    override fun toSql(): String = "DROP TABLE IF EXISTS ${table.tableName}"
}
