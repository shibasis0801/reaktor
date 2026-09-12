package dev.shibasis.reaktor.tooling.database

import kotlin.test.*

class MigrationLedgerTest {
    @Test fun customIdentifiersStayInsideOneReadAndObservedShapeDeterminesTheFormat() {
        val schema = "tenant's schema"
        val table = "history\"; DROP TABLE secrets; --"
        SqlReadStatement.normalize(MigrationLedger.discover(MigrationDialect.Postgres, schema, table))
        val ledger = assertNotNull(MigrationLedger.recognize(MigrationDialect.Postgres, schema, table,
            listOf("INSTALLED_RANK", "VERSION", "SCRIPT", "SUCCESS"), true))
        assertEquals(MigrationLedgerKind.Flyway, ledger.kind)
        assertTrue(ledger.page(2).contains("ORDER BY \"INSTALLED_RANK\" DESC LIMIT 100 OFFSET 200"))
        SqlReadStatement.normalize(ledger.page())
        assertNull(MigrationLedger.recognize(MigrationDialect.Postgres, "public", "flyway_schema_history", listOf("version"), true))
        assertNull(MigrationLedger.recognize(MigrationDialect.Postgres, "public", "history",
            listOf("installed_rank", "INSTALLED_RANK", "version", "script", "success"), true))
        assertFailsWith<IllegalArgumentException> { ledger.copy(readable = false).page() }
        assertFailsWith<IllegalArgumentException> { ledger.page(-1) }
        assertFailsWith<IllegalArgumentException> { MigrationLedger.discover(MigrationDialect.Postgres, "bad\n", "table") }
    }
    @Test fun d1HistoryUsesItsOwnShapeAndSchema() {
        SqlReadStatement.normalize(MigrationLedger.discover(MigrationDialect.D1))
        val ledger = assertNotNull(MigrationLedger.recognize(MigrationDialect.D1, "main", "custom", listOf("id", "name", "applied_at"), true))
        assertEquals(MigrationLedgerKind.D1, ledger.kind)
        assertFailsWith<IllegalArgumentException> { MigrationLedger.discover(MigrationDialect.D1, "other", "table") }
        assertNull(MigrationLedger.recognize(MigrationDialect.D1, "main", "custom", listOf("version", "statements"), true))
    }

    @Test fun liquibaseUsesExecutionDateBeforeThePerRunSequence() {
        val columns = listOf("ID", "AUTHOR", "FILENAME", "DATEEXECUTED", "ORDEREXECUTED", "EXECTYPE")
        val ledger = assertNotNull(MigrationLedger.recognize(MigrationDialect.Postgres, "public", "DATABASECHANGELOG", columns, true))
        assertTrue(ledger.page().contains("ORDER BY \"DATEEXECUTED\" DESC, \"ORDEREXECUTED\" DESC"))
    }
}
