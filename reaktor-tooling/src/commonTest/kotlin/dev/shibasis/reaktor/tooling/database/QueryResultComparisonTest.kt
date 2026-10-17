package dev.shibasis.reaktor.tooling.database

import kotlin.test.*

class QueryResultComparisonTest {
    private val columns = listOf(QueryColumn("id", "int"), QueryColumn("value", "text", true))

    @Test fun keysAlignReorderedRowsAndExposeAddedRemovedAndChangedValues() {
        val before = listOf(listOf("1", "one"), listOf("2", "two"), listOf("3", "three"))
        val after = listOf(listOf("3", "THREE"), listOf("1", "one"), listOf("4", "four"))
        val result = compareQueryResults(columns, before, columns, after, listOf(ResultColumnId("id", 0)))
        assertEquals(listOf(RowDifference.Unchanged, RowDifference.Removed, RowDifference.Changed, RowDifference.Added), result.rows.map { it.difference })
        assertEquals(1, result.rows[0].afterIndex)
        assertEquals(listOf(1), result.rows[2].changedColumns)
        assertFalse(result.schemaChanged)
    }

    @Test fun nullStringNullAndMissingColumnNeverCompareEqual() {
        val before: List<List<String?>> = listOf(listOf("1", null))
        val after: List<List<String?>> = listOf(listOf("1", "null"))
        assertEquals(RowDifference.Changed, compareQueryResults(columns, before, columns, after).rows.single().difference)
        val missing = compareQueryResults(columns, before, columns.take(1), listOf(listOf("1")))
        assertTrue(missing.schemaChanged)
        assertEquals(listOf(1), missing.rows.single().changedColumns)
    }

    @Test fun duplicateLabelsAreComparedByOccurrenceAndTypeChangesAreVisible() {
        val duplicate = listOf(QueryColumn("value", "int"), QueryColumn("value", "text"))
        val result = compareQueryResults(duplicate, listOf(listOf("1", "same")),
            duplicate, listOf(listOf("1", "different")))
        assertEquals(listOf(0, 1), result.columns.map { it.id.occurrence })
        assertEquals(listOf(1), result.rows.single().changedColumns)
        val changedType = compareQueryResults(columns, listOf(listOf("1", "same")),
            listOf(QueryColumn("id", "bigint"), columns[1]), listOf(listOf("1", "same")))
        assertTrue(changedType.schemaChanged)
        assertEquals(listOf(0), changedType.rows.single().changedColumns)
    }

    @Test fun ambiguousOrMissingKeysAreRejectedRatherThanSilentlyPaired() {
        val rows = listOf(listOf("1", "a"), listOf("1", "b"))
        assertFailsWith<IllegalArgumentException> { compareQueryResults(columns, rows, columns, rows, listOf(ResultColumnId("id", 0))) }
        assertFailsWith<IllegalArgumentException> { compareQueryResults(columns, rows, columns, rows, listOf(ResultColumnId("missing", 0))) }
        assertEquals(2, compareQueryResults(columns, rows, columns, rows, columns.comparisonIds()).rows.size)
        assertTrue(compareQueryResults(columns, rows, columns, rows, partial = true).partial)
    }

    @Test fun explicitPositionMatchingDoesNotPretendToFindRecordIdentity() {
        val result = compareQueryResults(columns, listOf(listOf("1", "a")), columns, listOf(listOf("2", "b")))
        assertTrue(result.keyColumns.isEmpty())
        assertEquals(RowDifference.Changed, result.rows.single().difference)
        assertFailsWith<IllegalArgumentException> { compareQueryResults(columns, listOf(listOf("1")), columns, emptyList()) }
    }
}
