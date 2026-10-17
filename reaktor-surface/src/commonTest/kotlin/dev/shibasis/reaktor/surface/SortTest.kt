package dev.shibasis.reaktor.surface

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SortTest {
    @Test
    fun aColumnSortsAscendingThenDescendingThenNotAtAll() {
        val ascending = null.next("status")
        assertEquals(Sort("status", descending = false), ascending)
        val descending = ascending.next("status")
        assertEquals(Sort("status", descending = true), descending)
        assertNull(descending.next("status"))
    }

    @Test
    fun anotherColumnStartsAscending() {
        assertEquals(Sort("size", descending = false), Sort("status", descending = true).next("size"))
        assertEquals(Sort("size", descending = false), Sort("status", descending = false).next("size"))
    }
}
