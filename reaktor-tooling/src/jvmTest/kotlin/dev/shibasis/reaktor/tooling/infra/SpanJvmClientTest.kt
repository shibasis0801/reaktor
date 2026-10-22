package dev.shibasis.reaktor.tooling.infra

import kotlinx.serialization.json.*
import kotlin.test.*

class SpanJvmClientTest {
    private val base = listOf("trace_id", "span_id", "parent_id", "phase", "island", "contract", "operation", "started_ms", "duration_ms", "status", "started_at")
    private val op = InfrastructureOperation.SpanRead(DatabaseConnection.KubernetesService("fixture", "reaktor", "clickhouse", 8123), "bestbuds_dev", windowMinutes = 15, limit = 10)

    @Test fun oldSchemaReadsContinueWithExplicitMissingJoinColumns() = InfrastructureSession().use { session ->
        val requests = mutableListOf<String>()
        val output = SpanJvmClient(session).readReceipt(op) { sql ->
            requests += sql
            if (sql.startsWith("SELECT name")) base.joinToString("\n") { "{\"name\":\"$it\"}" } else "{}"
        }
        val receipt = Json.decodeFromString(SpanReadReceipt.serializer(), output)
        assertEquals(base, receipt.columns)
        assertEquals(15, receipt.windowMinutes)
        assertTrue(requests.last().contains("'' AS application_session"))
        assertTrue(requests.last().contains("'' AS port"))
        assertTrue(requests.last().contains("'' AS edge"))
        assertTrue(requests.last().contains("'' AS activation"))
        assertTrue(requests.last().contains("FROM bestbuds_dev.service_spans"))
        assertTrue(requests.last().contains("LIMIT 10"))
        assertFalse(requests.any { it.contains("ALTER") })
    }

    @Test fun newSchemaPreservesQualifiedJoinIdentity() = InfrastructureSession().use { session ->
        val columns = base + listOf("application_session", "environment", "build", "node", "graph_digest", "port", "edge", "activation", "application", "installation", "platform")
        val requests = mutableListOf<String>()
        val output = SpanJvmClient(session).readReceipt(op) { sql ->
            requests += sql
            if (sql.startsWith("SELECT name")) columns.joinToString("\n") { "{\"name\":\"$it\"}" }
            else """{"application_session":"fixture-session","node":"message-node","graph_digest":"message-graph","port":"send-port","edge":"delivery-edge","activation":"phone-activation"}"""
        }
        val receipt = Json.decodeFromString(SpanReadReceipt.serializer(), output)
        assertEquals(columns, receipt.columns)
        assertEquals("fixture-session", receipt.rows.single().jsonObject["application_session"]!!.jsonPrimitive.content)
        val row = receipt.rows.single().jsonObject
        assertEquals("message-node", row.getValue("node").jsonPrimitive.content)
        assertEquals("message-graph", row.getValue("graph_digest").jsonPrimitive.content)
        assertEquals("send-port", row.getValue("port").jsonPrimitive.content)
        assertEquals("delivery-edge", row.getValue("edge").jsonPrimitive.content)
        assertEquals("phone-activation", row.getValue("activation").jsonPrimitive.content)
        assertFalse(requests.last().contains("'' AS"))
    }

    @Test fun filtersRunBeforeTheLimitAndAreEncodedAsData() = InfrastructureSession().use { session ->
        val filter = SpanReadFilter(trace = "1".repeat(32), session = "x' OR 1=1 --\\你好", build = "build-a", node = "node-a")
        val requests = mutableListOf<String>()
        val columns = base + listOf("application_session", "build", "node")
        val output = SpanJvmClient(session).readReceipt(op.copy(filter = filter)) { sql ->
            requests += sql
            if (sql.startsWith("SELECT name")) columns.joinToString("\n") { "{\"name\":\"$it\"}" } else ""
        }
        val sql = requests.last()
        assertTrue(sql.indexOf("trace_id = unhex(") < sql.indexOf("LIMIT"))
        assertTrue(sql.contains("application_session = unhex("))
        assertFalse(sql.contains(filter.session!!))
        assertEquals(filter, Json.decodeFromString(SpanReadReceipt.serializer(), output).filter)
    }

    @Test fun missingFilterColumnsAndInvalidTraceNeverReadRows() = InfrastructureSession().use { session ->
        var requests = 0
        assertFailsWith<IllegalArgumentException> {
            SpanJvmClient(session).readReceipt(op.copy(filter = SpanReadFilter(session = "session"))) {
                requests++
                base.joinToString("\n") { "{\"name\":\"$it\"}" }
            }
        }
        assertEquals(1, requests)
        assertFailsWith<IllegalArgumentException> {
            SpanJvmClient(session).readReceipt(op.copy(filter = SpanReadFilter(trace = "bad"))) { requests++; "" }
        }
        assertEquals(1, requests)
    }

    @Test fun missingBaseSchemaOrInvalidScopeFailsBeforeReadingRows() = InfrastructureSession().use { session ->
        var requests = 0
        assertFails { SpanJvmClient(session).readReceipt(op) { requests++; "{\"name\":\"trace_id\"}" } }
        assertEquals(1, requests)
        assertFailsWith<IllegalArgumentException> { SpanJvmClient(session).readReceipt(op.copy(database = "bestbuds; DROP TABLE")) { requests++; "" } }
        assertFailsWith<IllegalArgumentException> { SpanJvmClient(session).readReceipt(op.copy(limit = 5_001)) { requests++; "" } }
        assertEquals(1, requests)
    }
}
