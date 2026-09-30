package dev.shibasis.reaktor.tooling.database

import kotlin.test.*

class PrefixInventoryTest {
    @Test fun paginatedInventoryDeduplicatesKeysAndKeepsUnknownSizeDistinctFromZero() {
        val index = PrefixInventory("root/")
        index.addPage(listOf(PrefixEntry("root/a/file", 100), PrefixEntry("root/direct", 0)), "next", true)
        assertFalse(index.snapshot().complete)
        index.addPage(emptyList(), "another", true)
        index.addPage(listOf(PrefixEntry("root/a/file", 200), PrefixEntry("root/b/key", expiresAtSeconds = 1000)), null, false)
        val result = index.snapshot()
        assertTrue(result.complete)
        assertEquals(3, result.count)
        assertEquals(200, result.knownBytes)
        assertEquals(1, result.unknownSizes)
        assertEquals(1, result.expiringKeys)
        assertEquals(3, result.groups.size)
        assertEquals(3, result.pages)
    }
    @Test fun invalidCursorScopeAndBudgetCannotReplaceAcceptedObservations() {
        val index = PrefixInventory("root/")
        index.addPage(listOf(PrefixEntry("root/first", 1)), "next", true)
        val old = index.snapshot()
        assertFailsWith<IllegalArgumentException> { index.addPage(emptyList(), "next", true) }
        assertFailsWith<IllegalArgumentException> { index.addPage(emptyList(), null, true) }
        assertFailsWith<IllegalArgumentException> { index.addPage(listOf(PrefixEntry("other/key", 10)), null, false) }
        assertFailsWith<IllegalArgumentException> { index.addPage(listOf(PrefixEntry("root/huge", Long.MAX_VALUE)), null, false) }
        assertEquals(old, index.snapshot())
    }
}
