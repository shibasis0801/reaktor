package dev.shibasis.reaktor.tooling.database

enum class MigrationDialect { Postgres, D1 }

enum class MigrationLedgerKind(val required: Set<String>, val order: List<String>) {
    Flyway(setOf("installed_rank", "version", "script", "success"), listOf("installed_rank")),
    Liquibase(setOf("id", "author", "filename", "dateexecuted", "orderexecuted", "exectype"), listOf("dateexecuted", "orderexecuted", "id", "author", "filename")),
    Atlas(setOf("version", "applied", "total", "type"), listOf("version")),
    Supabase(setOf("version", "statements"), listOf("version")),
    D1(setOf("id", "name", "applied_at"), listOf("id"));
}

data class MigrationLedgerRef(val dialect: MigrationDialect, val schema: String, val table: String,
    val kind: MigrationLedgerKind, val columns: List<String>, val readable: Boolean) {
    init {
        MigrationLedger.identifier(schema); MigrationLedger.identifier(table)
        require(columns.size in 1..512 && columns.distinct().size == columns.size)
        columns.forEach(MigrationLedger::identifier)
        require(columns.map { it.lowercase() }.toSet().containsAll(kind.required)) { "Ledger columns do not match the selected format" }
        require(kind.order.all { name -> columns.count { it.equals(name, true) } == 1 }) { "Ambiguous migration order columns" }
        require((dialect == MigrationDialect.D1) == (kind == MigrationLedgerKind.D1))
        require(dialect != MigrationDialect.D1 || schema == "main")
    }

    fun page(page: Int = 0, size: Int = 100): String {
        require(readable) { "The current database role cannot read this ledger" }
        require(page in 0..10_000 && size in 1..500)
        val order = kind.order.joinToString(", ") { name ->
            MigrationLedger.identifier(columns.single { it.equals(name, true) }) + " DESC"
        }
        return "SELECT * FROM ${MigrationLedger.identifier(schema)}.${MigrationLedger.identifier(table)} ORDER BY $order LIMIT $size OFFSET ${page * size}"
    }
}

object MigrationLedger {
    internal fun identifier(value: String): String {
        require(value.isNotBlank() && value.length <= 128 && value.none { it.isISOControl() || it == '\\' }) { "Enter a valid database identifier" }
        return "\"${value.replace("\"", "\"\"")}\""
    }
    private fun literal(value: String): String {
        identifier(value)
        return "'${value.replace("'", "''")}'"
    }

    fun discover(dialect: MigrationDialect, schema: String = "", table: String = ""): String {
        require(table.isNotBlank() || schema.isBlank()) { "A custom ledger needs both its schema and table" }
        return when (dialect) {
            MigrationDialect.Postgres -> {
                val filter = if (table.isBlank()) "lower(c.relname) IN ('flyway_schema_history','databasechangelog','atlas_schema_revisions','schema_migrations')"
                    else "n.nspname=${literal(schema.ifBlank { "public" })} AND c.relname=${literal(table)}"
                """
                    SELECT n.nspname AS schema,c.relname AS ledger,
                        (SELECT json_agg(a.attname ORDER BY a.attnum) FROM pg_attribute a WHERE a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped) AS columns,
                        has_schema_privilege(c.relnamespace,'USAGE') AND has_table_privilege(c.oid,'SELECT') AS readable
                    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE c.relkind IN ('r','p') AND ($filter) ORDER BY n.nspname,c.relname
                """.trimIndent()
            }
            MigrationDialect.D1 -> {
                require(schema.isBlank() || schema == "main") { "D1 migration tables belong to main" }
                "SELECT 'main' AS schema,m.name AS ledger,(SELECT json_group_array(p.name) FROM pragma_table_info(m.name) p) AS columns,1 AS readable FROM sqlite_master m WHERE m.type='table' AND m.name=${literal(table.ifBlank { "d1_migrations" })} ORDER BY m.name"
            }
        }
    }

    fun recognize(dialect: MigrationDialect, schema: String, table: String, columns: List<String>, readable: Boolean): MigrationLedgerRef? {
        val normalized = columns.map { it.lowercase() }.toSet()
        val kind = MigrationLedgerKind.entries.firstOrNull {
            (dialect == MigrationDialect.D1) == (it == MigrationLedgerKind.D1) && normalized.containsAll(it.required) &&
                it.order.all { name -> columns.count { column -> column.equals(name, true) } == 1 }
        } ?: return null
        return MigrationLedgerRef(dialect, schema, table, kind, columns, readable)
    }
}
