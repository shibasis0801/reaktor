package dev.shibasis.reaktor.tooling.database

import dev.shibasis.reaktor.tooling.infra.*
import kotlinx.serialization.json.*
import kotlin.test.*

class QueryReceiptTest {
    @Test fun preservesDuplicateColumnsNullAndExactNumbers() {
        val receipt = QueryReceipt(provider = "Postgres", columns = listOf(QueryColumn("x", "int8"), QueryColumn("x", "text")),
            rows = listOf(listOf(JsonPrimitive("9223372036854775807"), JsonNull)))
        receipt.validate(1, "Postgres")
        assertEquals(receipt, Json.decodeFromString<QueryReceipt>(Json.encodeToString(QueryReceipt.serializer(), receipt)))
        assertFails { receipt.validate(1, "ClickHouse") }
        assertFails { receipt.copy(rows = listOf(listOf(JsonNull))).validate(1) }
        assertFails { receipt.copy(rows = List(3) { receipt.rows.single() }).validate(1) }
    }

    @Test fun postgresPlansPreserveTreeCostsAndActualStatistics() {
        val plan = QueryPlan("postgres-json", Json.parseToJsonElement("""[{"Plan":{"Node Type":"Limit","Total Cost":12.3,"Actual Rows":5,"Plans":[{"Node Type":"Index Scan","Index Name":"users_pk","Plan Rows":42}]},"Execution Time":2.5}]"""), true)
        val operators = plan.operators()
        assertEquals(listOf(0, 1), operators.map { it.depth })
        assertEquals("12.3", operators.first().attributes["Total Cost"])
        assertEquals("5", operators.first().attributes["Actual Rows"])
        assertEquals("users_pk", operators.last().attributes["Index Name"])
    }

    @Test fun sqlitePlansRejectParentCycles() {
        val plan = QueryPlan("sqlite-query-plan", Json.parseToJsonElement("""[{"id":2,"parent":0,"detail":"SCAN users"},{"id":3,"parent":2,"detail":"SEARCH roles"}]"""))
        assertEquals(listOf(0, 1), plan.operators().map { it.depth })
        assertFails { plan.copy(document = Json.parseToJsonElement("""[{"id":2,"parent":3,"detail":"a"},{"id":3,"parent":2,"detail":"b"}]""")).operators() }
    }

    @Test fun storeQueriesRejectMutationsAndAmbiguousLocators() {
        val store = WorkerStore("D1", "DB")
        assertEquals("select 'delete;from'", WorkerStoreRead("select 'delete;from'; -- comment").validate(store).statement)
        listOf("delete from users", "with x as (delete from users returning *) select * from x", "select 1; select 2", "select readfile('/etc/passwd')").forEach {
            assertFails { WorkerStoreRead(it).validate(store) }
        }
        StoreKeyQuery(prefix = "images/", cursor = "provider-token").validate("R2")
        assertFails { StoreKeyQuery(key = "x", prefix = "y").validate("R2") }
        assertFails { StoreKeyQuery().validate("DurableObjects") }
        assertFails { WorkerStoreRead("{}", explain = true).validate(WorkerStore("R2", "BUCKET")) }
    }
}
