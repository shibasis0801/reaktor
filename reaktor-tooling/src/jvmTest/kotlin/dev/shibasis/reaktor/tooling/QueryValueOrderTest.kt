package dev.shibasis.reaktor.tooling

import dev.shibasis.reaktor.tooling.database.QueryValueOrder
import kotlin.test.*

class QueryValueOrderTest {
    @Test fun numericOrderingPreservesPrecisionAndNullIsLastInBothDirections() {
        val values = listOf("10", null, "2", "9007199254740993", "9007199254740992")
        assertEquals(listOf("2", "10", "9007199254740992", "9007199254740993", null), values.sortedWith(QueryValueOrder.comparator("Nullable(UInt64)")))
        assertEquals(listOf("9007199254740993", "9007199254740992", "10", "2", null), values.sortedWith(QueryValueOrder.comparator("bigint", true)))
        assertEquals(listOf("1.0000000000000000001", "1.0000000000000000002"),
            listOf("1.0000000000000000002", "1.0000000000000000001").sortedWith(QueryValueOrder.comparator("numeric")))
    }
    @Test fun malformedNumericValuesCannotMakeTheComparatorNonTransitive() {
        val comparator = QueryValueOrder.comparator("double precision")
        val values = listOf(null, "2", "10", "1a", "NaN", "Infinity", "", "-1")
        for (a in values) for (b in values) for (c in values) {
            if (comparator.compare(a,b) <= 0 && comparator.compare(b,c) <= 0) assertTrue(comparator.compare(a,c) <= 0)
        }
        assertEquals(listOf("false", "true", null), listOf("true", null, "false").sortedWith(QueryValueOrder.comparator("bool")))
    }
}
