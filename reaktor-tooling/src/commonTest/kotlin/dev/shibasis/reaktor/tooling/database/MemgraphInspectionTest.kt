package dev.shibasis.reaktor.tooling.database

import kotlin.test.*

class MemgraphInspectionTest {
    @Test fun explorationIsCanonicalBoundedAndCannotInjectCypher() {
        for (direction in MemgraphDirection.entries) for (type in listOf(null, "KNOWS", "A\" RETURN x\\B'")) {
            val request = MemgraphInspectionRequest(MemgraphInspection.Relationships, 25, 50, 9223372036854775807, direction, type)
            assertEquals(request, MemgraphInspection.parse(request.statement))
            listOf(request.statement + "; DELETE n", request.statement.replace("id(a)", "custom(id(a))"),
                request.statement.replace("SKIP 50", "SKIP 050"), request.statement.replace("WHERE ", "WHERE true OR "))
                .forEach { assertNull(MemgraphInspection.parse(it)) }
        }
        val node = MemgraphInspectionRequest(MemgraphInspection.Nodes, 1, 0, 7)
        assertEquals(node, MemgraphInspection.parse(node.statement))
        assertNull(MemgraphInspection.parse(node.statement.replace("= 7", "= -1")))
        assertNull(MemgraphInspection.parse(node.statement.replace("= 7", "= 07")))
    }
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
