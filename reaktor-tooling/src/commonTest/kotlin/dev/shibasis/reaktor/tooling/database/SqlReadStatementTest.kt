package dev.shibasis.reaktor.tooling.database

import kotlin.test.*

class SqlReadStatementTest {
    @Test fun readFunctionsCanShareNamesWithMutationKeywords() {
        listOf("select replace('old', 'old', 'new')", "select replace /* gap */ ('old', 'old', 'new')",
            "with data as (select replace('old', 'old', 'new') as value) select value from data").forEach {
            assertTrue(SqlReadStatement.normalize(it).startsWith(it.substringBefore(' ')))
        }
        listOf("replace into data values (1)", "with x as (delete from data returning *) select * from x",
            "select * into other from data").forEach { assertFailsWith<IllegalArgumentException> { SqlReadStatement.normalize(it) } }
    }

    @Test fun transactionReadsUseDatabaseAuthorityAndRetainSingleStatementAndDangerousFunctionGuards() {
        val query = "select update from read_projection"
        assertFailsWith<IllegalArgumentException> { SqlReadStatement.normalize(query) }
        assertEquals(query, SqlReadStatement.normalize(query, readOnlyTransaction = true))
        BoundQuery("select update from read_projection where id = ?", listOf(BoundQueryParameter(value = "1"))).validate(readOnlyTransaction = true)
        for (transaction in listOf(false, true)) {
            listOf("select 1; select 2", "delete from data", "select writefile('/tmp/invalid', 'invalid')",
                "select pg_cancel_backend(1)", "select lo_export(1, '/tmp/invalid')", "select set_config('role','owner',false)",
                "select 'unfinished").forEach { assertFailsWith<IllegalArgumentException> { SqlReadStatement.normalize(it, transaction) } }
        }
    }
}
