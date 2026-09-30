package dev.shibasis.reaktor.tooling.database

import kotlin.test.*

class MemgraphReadPageTest {
    private val edgeColumns = listOf("relationship_id", "source_id", "type", "target_id", "properties")

    @Test fun relationshipsPreserveDirectionParallelEdgesAndSelfLoopsWithoutInventingNodeProperties() {
        val page = MemgraphReadPage.from(MemgraphInspection.Relationships, edgeColumns, listOf(
            listOf("7", "1", "\"FOLLOWS\"", "2", "{weight: 0.5}"),
            listOf("8", "1", "\"FOLLOWS\"", "2", "{}"),
            listOf("9", "2", "\"SELF\"", "2", "{}"),
        ))
        assertEquals(listOf("1", "2"), page.nodes.map { it.id })
        assertTrue(page.nodes.none { it.loaded || it.properties != null || it.labels != null })
        assertEquals(listOf("7", "8", "9"), page.relationships.map { it.id })
        assertEquals("FOLLOWS", page.relationships.first().type)
        assertEquals("{weight: 0.5}", page.relationships.first().properties)
        assertEquals("2", page.relationships.last().source)
        assertEquals("2", page.relationships.last().target)
    }

    @Test fun nodesKeepFullPropertiesAndRejectUnknownOrAmbiguousIdentity() {
        val columns = listOf("node_id", "labels", "properties")
        val row = listOf("5", "[\"Person\"]", "{text: \"line\\nvalue\"}")
        val node = MemgraphReadPage.from(MemgraphInspection.Nodes, columns, listOf(row)).nodes.single()
        assertTrue(node.loaded)
        assertEquals(row[2], node.properties)
        assertFailsWith<IllegalArgumentException> { MemgraphReadPage.from(MemgraphInspection.Nodes, columns, listOf(row, row)) }
        assertFailsWith<IllegalArgumentException> { MemgraphReadPage.from(MemgraphInspection.Nodes, columns.reversed(), listOf(row)) }
        for (identity in listOf("-1", "01", "1;DELETE", "9223372036854775808")) {
            assertFailsWith<IllegalArgumentException> { MemgraphReadPage.from(MemgraphInspection.Nodes, columns, listOf(row.toMutableList().apply { set(0, identity) })) }
        }
        assertFailsWith<IllegalArgumentException> { MemgraphReadPage.from(MemgraphInspection.Nodes, columns, List(501) { row }) }
        assertFailsWith<IllegalStateException> { MemgraphReadPage.from(MemgraphInspection.Labels, listOf("label"), emptyList()) }
    }
}
