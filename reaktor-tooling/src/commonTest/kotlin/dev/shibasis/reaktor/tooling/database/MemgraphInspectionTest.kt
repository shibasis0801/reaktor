package dev.shibasis.reaktor.tooling.database

import kotlin.test.*

class MemgraphInspectionTest {
    @Test fun catalogPreservesExactReadIdentityAndBounds() {
        for (read in MemgraphInspection.entries) {
            val request = assertNotNull(MemgraphInspection.parse(read.statement(100, 200)))
            assertEquals(read, request.read)
            assertEquals(100, request.limit)
            assertEquals(200, request.offset)
        }
    }
    @Test fun mutationsProceduresAndPaginationInjectionAreRejected() {
        val nodes = MemgraphInspection.Nodes.statement()
        listOf("MATCH (n) DELETE n", "CALL db.labels()", nodes + "; DELETE n", nodes + "\nRETURN 1",
            nodes.replace("SKIP 0", "SKIP -1"), nodes.replace("LIMIT 100", "LIMIT 501"),
            nodes.replace("properties(n)", "custom.export(n)"), nodes.replace("SKIP 0", "SKIP 1000001"))
            .forEach { assertNull(MemgraphInspection.parse(it), it) }
    }
}
