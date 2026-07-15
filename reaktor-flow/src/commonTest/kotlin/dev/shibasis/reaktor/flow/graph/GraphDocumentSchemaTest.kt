package dev.shibasis.reaktor.flow.graph

import dev.shibasis.reaktor.flow.graph.document.DocEdge
import dev.shibasis.reaktor.flow.graph.document.DocNode
import dev.shibasis.reaktor.flow.graph.document.DocNodeData
import dev.shibasis.reaktor.flow.graph.document.DocPoint
import dev.shibasis.reaktor.flow.graph.document.DocPort
import dev.shibasis.reaktor.flow.graph.document.DocPortRole
import dev.shibasis.reaktor.flow.graph.document.DocScope
import dev.shibasis.reaktor.flow.graph.document.GraphDocument
import dev.shibasis.reaktor.flow.graph.document.GraphDocumentKind
import dev.shibasis.reaktor.flow.graph.document.decodeGraphDocument
import dev.shibasis.reaktor.flow.graph.document.encode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Freezes the GraphDocument wire schema — the inter-phase, cross-language
 * contract of the graph-editor unification. The TS twin (Manna) and the
 * Memgraph `graphdoc.*` gateway ops parse exactly these shapes. If a change
 * here is intentional, it must be additive and mirrored in both consumers.
 */
class GraphDocumentSchemaTest {

    private fun sampleDocument() = GraphDocument(
        id = "doc-1",
        tenantId = "tenant-bb",
        name = "Systems Roadmap",
        kind = GraphDocumentKind.Roadmap,
        nodes = listOf(
            DocNode(
                id = "n1",
                kind = "topic",
                data = DocNodeData(title = "Operating Systems", meta = JsonObject(mapOf("priority" to JsonPrimitive("P0")))),
            ),
            DocNode(
                id = "n2",
                kind = "transform",
                scopeId = "root/phase-1",
                position = DocPoint(120.0, 64.5),
                data = DocNodeData(title = "Ingest", content = "reads events"),
                ports = listOf(
                    DocPort(key = "in", type = "Event", role = DocPortRole.Consumer),
                    DocPort(key = "out", type = "Row", role = DocPortRole.Provider),
                ),
            ),
        ),
        edges = listOf(
            DocEdge(id = "e1", from = "n1", to = "n2", kind = "prereq", label = "before"),
            DocEdge(id = "e2", from = "n2", to = "n1", kind = "dataflow", fromPort = "out", toPort = null),
        ),
        scopes = listOf(DocScope(id = "root/phase-1", label = "Phase 1", color = "#9FC7FF")),
        version = 3,
        updatedAt = "2026-07-12T10:00:00Z",
    )

    @Test
    fun roundTripPreservesEveryField() {
        val document = sampleDocument()
        assertEquals(document, decodeGraphDocument(document.encode()))
    }

    @Test
    fun wireFormatUsesFrozenFieldNamesAndKindSpellings() {
        val json = sampleDocument().encode()
        // Frozen spellings: enum serial names and field names the TS twin relies on.
        for (fragment in listOf(
            "\"kind\":\"roadmap\"",
            "\"role\":\"consumer\"",
            "\"role\":\"provider\"",
            "\"scopeId\":\"root/phase-1\"",
            "\"fromPort\":\"out\"",
            "\"tenantId\":\"tenant-bb\"",
            "\"position\":{\"x\":120.0,\"y\":64.5}",
        )) {
            kotlin.test.assertTrue(fragment in json, "wire format lost frozen fragment: $fragment\njson: $json")
        }
    }

    @Test
    fun defaultsStayOffTheWireAndUnknownKeysAreIgnored() {
        val minimal = GraphDocument(id = "d", tenantId = "t", name = "Untitled")
        val json = minimal.encode()
        // Absent optionals/defaults must not be emitted (forward-compat contract).
        kotlin.test.assertTrue("updatedAt" !in json && "position" !in json, "defaults leaked: $json")

        // Future writers may add fields; today's reader must not reject them.
        val fromFuture = decodeGraphDocument(
            """{"id":"d","tenantId":"t","name":"Untitled","futureField":{"nested":true}}""",
        )
        assertEquals("d", fromFuture.id)
        assertEquals(GraphDocumentKind.Concept, fromFuture.kind)
        assertNull(fromFuture.updatedAt)
    }
}
