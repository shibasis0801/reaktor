package dev.shibasis.reaktor.db.core

/** Shared SQLite-compatible storage schema, used by native SQLite and D1 authorities. */
class ObjectJournalSchema(val table: String, prefix: String) {
    init { require(table.matches(Regex("[A-Za-z0-9_]+")) && prefix.isNotBlank()) }
    val createConfiguration = "CREATE TABLE IF NOT EXISTS ${table}_journal_config (id INTEGER PRIMARY KEY CHECK(id=1), prefix TEXT NOT NULL)"
    val setConfiguration = "INSERT OR IGNORE INTO ${table}_journal_config(id,prefix) VALUES(1,?)"
    val getConfiguration = "SELECT prefix FROM ${table}_journal_config WHERE id=1"
    val read = "SELECT sequence,store_name,key,payload,at_millis FROM ${table}_changes WHERE store_name=? AND sequence>? ORDER BY sequence LIMIT ?"
    val statements: List<String> = buildList {
        add("CREATE TABLE IF NOT EXISTS ${table}_changes (sequence INTEGER PRIMARY KEY AUTOINCREMENT,store_name TEXT NOT NULL,key TEXT NOT NULL,payload TEXT,at_millis INTEGER NOT NULL)")
        add("CREATE INDEX IF NOT EXISTS ${table}_changes_scope ON ${table}_changes(store_name,sequence)")
        val literal = "'${prefix.replace("'", "''")}'"
        for ((operation, row) in listOf("INSERT" to "NEW", "UPDATE" to "NEW", "DELETE" to "OLD")) {
            val payload = if (operation == "DELETE") "NULL" else "$row.value"
            val timestamp = if (operation == "DELETE") "CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)" else "$row.updated_at"
            add("""
                CREATE TRIGGER IF NOT EXISTS ${table}_journal_${operation.lowercase()}
                AFTER $operation ON $table
                WHEN substr($row.store_name,1,length($literal))=$literal
                BEGIN
                    INSERT INTO ${table}_changes(store_name,key,payload,at_millis)
                    VALUES($row.store_name,$row.key,$payload,$timestamp);
                END
            """.trimIndent())
        }
    }
}
