package dev.shibasis.reaktor.graph.step

import dev.shibasis.reaktor.graph.Reaktor
import dev.shibasis.reaktor.graph.Workflow
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.graph.intercept
import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.Exchange
import dev.shibasis.reaktor.service.contract.Interceptor
import dev.shibasis.reaktor.service.contract.Next
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

private class Checkout(graph: Graph, ran: MutableList<String>) : Workflow(graph, "test.checkout", "Checkout"), Inline<String, String> {
    override val input by input<String>()
    val charge by step(input, effect = Effect.ProductionReversibleWrite) { ran += "charge"; if (it == "declined") fail("card declined") else it.length }
    val apology by step(input, effect = Effect.ReadOnly, on = charge.failed) { ran += "apology"; "sorry about $it" }
    val receipt by step(charge, effect = Effect.ReadOnly) { ran += "receipt"; "paid $it" }
    override val output by either(receipt, apology)
}

private class Moderation(graph: Graph) : Workflow(graph, "test.moderation", "Moderation"), Inline<String, String> {
    override val input by input<String>()
    val cached by step(input, effect = Effect.LiveRead) { if (it == "seen") "allow (cached)" else null }
    val fast by step(input, effect = Effect.ReadOnly, on = cached.skipped) { if ("spam" in it) "remove (fast)" else null }
    val careful by step(input, effect = Effect.LiveRead, on = cached.skipped and fast.skipped) { "allow (careful)" }
    override val output by either(cached, fast, careful)
}

private class Desk(val name: String)

private class Ticket(val desk: Desk, val number: Int)

private class Queueing(graph: Graph) : Workflow(graph, "test.queueing", "Queueing"), Inline<Desk, String> {
    override val input by input<Desk>()
    val ticket by step(input, effect = Effect.ProductionReversibleWrite, attributes = mapOf("ingress" to "queue:desks")) { Ticket(it, 7) }
    override val output by step(ticket, effect = Effect.ReadOnly) { "${it.desk.name} #${it.number}" }
}

private class Refused(reason: String) : Exception(reason)

private class Strict(graph: Graph, written: MutableList<String>) : Workflow(graph, "test.strict", "Strict"), Inline<String, String> {
    override val input by input<String>()
    val checked by step(input, effect = Effect.ReadOnly) { if (it.isBlank()) throw Refused("a name is required") else it.trim() }
    val saved by step(checked, effect = Effect.ProductionReversibleWrite) { written += it; it }
    override val output = saved
}

private class Session(val user: String) : AbstractCoroutineContextElement(Session) {
    companion object Key : CoroutineContext.Key<Session>
}

private class Greeting(graph: Graph, order: MutableList<String>) : Workflow(graph, "test.greeting", "Greeting"), Inline<String, String> {
    override val input by input<String>()
    val first by step(input, effect = Effect.ReadOnly) { order += "first"; currentCoroutineContext()[Session]?.user ?: "nobody" }
    val second by step(input, effect = Effect.ReadOnly) { order += "second"; it }
    val third by step(input, effect = Effect.ReadOnly) { order += "third"; "!" }
    override val output by step(first, second, third, effect = Effect.ReadOnly) { who, word, mark -> "$word, $who$mark" }
}

private class Slow(graph: Graph, after: Boolean) : Workflow(graph, "test.slow", "Slow"), Inline<String, String> {
    override val input by input<String>()
    val lookup by step(input, effect = Effect.LiveRead, timeout = 10.milliseconds) { delay(1.seconds); it }
    val fallback by step(input, effect = Effect.ReadOnly, on = if (after) lookup.timedOut else lookup.skipped) { "cached $it" }
    override val output by either(lookup, fallback)
}

private class Twice(graph: Graph) : Workflow(graph, "test.twice", "Twice"), Inline<String, String> {
    override val input by input<String>()
    val other by input<String>()
    override val output by step(input, other, effect = Effect.ReadOnly) { a, b -> a + b }
}

private class Inbox(graph: Graph, retried: Boolean) : Workflow(graph, "test.inbox", "Inbox"), Inline<String, String> {
    override val input by input<String>()
    val entry by step<String, String>(input, effect = Effect.ProductionReversibleWrite) { throw Refused("inbox down") }
    val lost by step(effect = Effect.ReadOnly, on = entry.failed and input.matches("is not retried") { !retried }) { "no entry" }
    private val entries by either(entry, lost)
    override val output by step(input, entries, effect = Effect.ProductionReversibleWrite) { title, entry -> "$title pushed with $entry" }
}

private class Till(graph: Graph, checkout: Checkout) : BasicNode(graph) {
    override val label = "till"
    val pay by consumes(checkout)
}

private class Seen(private val log: MutableList<String>) : Interceptor {
    override suspend fun <In, R> intercept(exchange: Exchange<In, R>, next: Next<In, R>): R {
        log += "${exchange.contract} ${exchange.name} ${exchange.effect}"
        return next(exchange)
    }
}

class InlineTest {
    init { Reaktor.web() }

    @Test fun aWorkflowRunsInItsCallerAndAnswersWithTheBranchThatRan() = runTest {
        val graph = Graph(label = "worker")
        val ran = mutableListOf<String>()
        val checkout = graph.Node { Checkout(it, ran) }

        assertEquals("paid 4", checkout("book"))
        assertEquals(listOf("charge", "receipt"), ran)
        ran.clear()
        assertEquals("sorry about declined", checkout("declined"))
        assertEquals(listOf("charge", "apology"), ran)
        graph.close()
    }

    @Test fun aStepWithNothingToGiveEndsSkippedSoTheNextTierAnswers() = runTest {
        val graph = Graph(label = "worker")
        val moderation = graph.Node { Moderation(it) }

        assertEquals("allow (cached)", moderation("seen"))
        assertEquals("remove (fast)", moderation("spam offer"))
        assertEquals("allow (careful)", moderation("hello"))
        graph.close()
    }

    @Test fun aFailureNoStepHandlesReachesTheCallerAndStopsTheSteps() = runTest {
        val graph = Graph(label = "worker")
        val written = mutableListOf<String>()
        val strict = graph.Node { Strict(it, written) }

        val refused = assertFailsWith<Refused> { strict(" ") }
        assertEquals("a name is required", refused.message)
        assertEquals(emptyList(), written)
        assertEquals("Ada", strict(" Ada "))
        assertEquals(listOf("Ada"), written)
        graph.close()
    }

    @Test fun stepsRunOneAfterAnotherInDeclarationOrderWithTheCallersContext() = runTest {
        val graph = Graph(label = "worker")
        val order = mutableListOf<String>()
        val greeting = graph.Node { Greeting(it, order) }

        assertEquals("hello, ada!", withContext(Session("ada")) { greeting("hello") })
        assertEquals(listOf("first", "second", "third"), order)
        assertEquals("hi, nobody!", greeting("hi"))
        graph.close()
    }

    @Test fun eachStepIsACallTheGraphsInterceptorsSee() = runTest {
        val graph = Graph(label = "worker")
        val log = mutableListOf<String>()
        graph.intercept(Seen(log))
        val checkout = graph.Node { Checkout(it, mutableListOf()) }

        checkout("declined")
        assertEquals(listOf("test.checkout charge ProductionReversibleWrite", "test.checkout apology ReadOnly"), log)
        graph.close()
    }

    @Test fun aStepThatRunsOutOfTimeFailsTheCallUnlessAStepHandlesIt() = runTest {
        val graph = Graph(label = "worker")
        val handled = graph.Node { Slow(it, after = true) }
        val unhandled = graph.Node { Slow(it, after = false) }

        assertEquals("cached menu", handled("menu"))
        val failure = assertFailsWith<StepFailed> { unhandled("menu") }
        assertTrue("took longer than 10ms" in failure.reason, failure.reason)
        graph.close()
    }

    @Test fun valuesThatCannotBeStoredPassBetweenStepsThatRunInline() = runTest {
        val graph = Graph(label = "worker")
        val queueing = graph.Node { Queueing(it) }

        assertEquals("front #7", queueing(Desk("front")))
        assertEquals(false, queueing.ticket.storable)
        assertEquals("queue:desks", graph.shape().nodes.single { it.label == "ticket" }.attributes["ingress"])
        assertEquals(true, graph.Node { Checkout(it, mutableListOf()) }.charge.storable)
        graph.close()
    }

    @Test fun aHandledFailureWhoseHandlerDoesNotApplyEndsTheRunWithThatFailure() = runTest {
        val graph = Graph(label = "worker")
        val tolerant = graph.Node { Inbox(it, retried = false) }
        val strict = graph.Node { Inbox(it, retried = true) }

        assertEquals("hello pushed with no entry", tolerant("hello"))
        val failure = assertFailsWith<Refused> { strict("hello") }
        assertEquals("inbox down", failure.message)
        graph.close()
    }

    @Test fun nodesRunAnInlineWorkflowThroughPortsTheMapDraws() = runTest {
        val graph = Graph(label = "worker")
        val ran = mutableListOf<String>()
        val checkout = graph.Node { Checkout(it, ran) }
        val front = graph.Node { Till(it, checkout) }
        val back = graph.Node { Till(it, checkout) }

        assertEquals("paid 4", front.pay("book"))
        assertEquals("sorry about declined", back.pay("declined"))
        assertEquals(listOf("charge", "receipt", "charge", "apology"), ran)
        val wires = graph.shape().wires.filter { it.provider == checkout.id.toString() }
        assertEquals(setOf(front.id.toString(), back.id.toString()), wires.map { it.consumer }.toSet())
        assertTrue(wires.all { it.type == "inline:test.checkout" && it.providerPort == "runs" })
        graph.close()
    }

    @Test fun aWorkflowWithASecondInputCannotRunInline() = runTest {
        val graph = Graph(label = "worker")
        val twice = graph.Node { Twice(it) }

        val refused = assertFailsWith<IllegalArgumentException> { twice("a") }
        assertTrue("'other'" in refused.message.orEmpty(), refused.message)
        graph.close()
    }
}
