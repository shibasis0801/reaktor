package dev.shibasis.reaktor.graph

import dev.shibasis.reaktor.graph.core.Digraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DigraphTest {
    private val chain = Digraph(listOf("a", "b", "c", "d", "lone"), listOf("a" to "b", "b" to "c", "a" to "c", "c" to "d"))

    @Test
    fun ranksFollowTheLongestPrerequisiteChain() {
        assertEquals(mapOf("a" to 0, "b" to 1, "c" to 2, "d" to 3, "lone" to 0), chain.ranks)
    }

    @Test
    fun reductionDropsOnlyImpliedEdges() {
        assertEquals(listOf("a" to "b", "b" to "c", "c" to "d"), chain.reduced)
        assertEquals(listOf("a" to "c"), chain.implied)
    }

    @Test
    fun reachabilityRunsBothWays() {
        assertEquals(setOf("b", "c", "d"), chain.below("a"))
        assertEquals(setOf("a", "b"), chain.above("c"))
        assertTrue(chain.reaches("a", "d"))
        assertFalse(chain.reaches("d", "a"))
        assertTrue(chain.below("lone").isEmpty())
    }

    @Test
    fun cyclesShareARankAndKeepTheirEdges() {
        val looped = Digraph(emptyList(), listOf("start" to "x", "x" to "y", "y" to "x", "y" to "end", "self" to "self"))
        assertEquals(setOf(setOf("x", "y"), setOf("self")), looped.cycles().toSet())
        assertTrue(looped.inCycle("x"))
        assertFalse(looped.inCycle("start"))
        assertEquals(looped.ranks.getValue("x"), looped.ranks.getValue("y"))
        assertEquals(2, looped.ranks.getValue("end"))
        assertTrue(("x" to "y") in looped.reduced && ("y" to "x") in looped.reduced)
        assertEquals(setOf("y", "end"), looped.below("x"))
        assertEquals(setOf("start", "y"), looped.above("x"))
    }

    @Test
    fun columnsOrderGroupsByWhatTheirMembersReach() {
        val plan = Digraph(emptyList(), listOf("a" to "x", "x" to "b", "b" to "c", "q" to "c"))
        assertEquals(listOf(0, 1, 2, 0), plan.columns(listOf(listOf("a"), listOf("b"), listOf("c"), listOf("q"))))
        assertEquals(listOf(0, 0), plan.columns(listOf(listOf("a", "c"), listOf("b"))))
    }
}
