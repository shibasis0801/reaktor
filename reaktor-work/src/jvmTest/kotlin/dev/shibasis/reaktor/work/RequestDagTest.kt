package dev.shibasis.reaktor.work

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import kotlin.test.*

class RequestDagTest {
    @Test fun diamondRunsBranchesConcurrentlyAndSharesOnlyWithinOneRun() = runTest {
        val app = koinApplication {}
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(app))
        try {
            val dag = RequestDag(graph, "diamond", 1)
            var sourceCalls = 0
            val source = dag.step("source") { ++sourceCalls }
            val bEntered = CompletableDeferred<Unit>()
            val cEntered = CompletableDeferred<Unit>()
            val b = dag.step("b", listOf(source)) { bEntered.complete(Unit); cEntered.await(); get(source) + 1 }
            val c = dag.step("c", listOf(source)) { cEntered.complete(Unit); bEntered.await(); get(source) + 2 }
            val join = dag.step("join", listOf(b, c)) { get(b) + get(c) }
            assertEquals(5, dag.run(join, parallelism = 2))
            assertEquals(1, sourceCalls)
            assertEquals(7, dag.run(join, parallelism = 2))
            assertEquals(2, sourceCalls)
            assertFailsWith<IllegalArgumentException> { dag.step("late") { "mutation" } }
        } finally { graph.close(); app.close() }
    }

    @Test fun branchDoesNotRunUnselectedEffectsAndRejectsUndeclaredReads() = runTest {
        val app = koinApplication {}
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(app))
        try {
            val dag = RequestDag(graph, "branch", 1)
            val condition = dag.step("condition") { true }
            val yes = dag.step("yes") { "selected" }
            var wrongEffect = false
            val no = dag.step("no") { wrongEffect = true; "wrong" }
            val branch = dag.branch("branch", condition, yes, no)
            val invalid = dag.step("invalid") { get(yes) }
            assertEquals("selected", dag.run(branch, parallelism = 1))
            assertFalse(wrongEffect)
            assertFailsWith<IllegalArgumentException> { dag.run(invalid) }
        } finally { graph.close(); app.close() }
    }
}
