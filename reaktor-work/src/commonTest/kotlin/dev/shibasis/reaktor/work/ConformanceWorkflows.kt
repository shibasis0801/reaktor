package dev.shibasis.reaktor.work

import dev.shibasis.reaktor.auth.kernel.AuthRequirement
import dev.shibasis.reaktor.auth.kernel.PermissionRef
import dev.shibasis.reaktor.graph.Workflow
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.step.StepNode
import dev.shibasis.reaktor.graph.step.either
import dev.shibasis.reaktor.graph.step.input
import dev.shibasis.reaktor.graph.step.step
import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.work.definition.Durable
import dev.shibasis.reaktor.work.definition.call
import dev.shibasis.reaktor.work.definition.each
import dev.shibasis.reaktor.work.definition.loop
import dev.shibasis.reaktor.work.definition.signal
import dev.shibasis.reaktor.work.definition.timer
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation

class TestClock(var millis: Long = 1_700_000_000_000) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(millis)

    fun advance(duration: Duration) {
        millis += duration.inWholeMilliseconds
    }
}

class Greeting(graph: Graph) : Workflow(graph, "test.greeting", "Greeting"), Durable<String, String> {
    var calls = 0
    override val input by input<String>()
    val shout by step(input, effect = Effect.ReadOnly) { name -> calls++; name.uppercase() }
    val greet by step(shout, effect = Effect.LocalEphemeral) { loud -> "Hello, $loud" }
    override val output = greet
}

class Diamond(graph: Graph) : Workflow(graph, "test.diamond", "Diamond"), Durable<Int, Int> {
    override val input by input<Int>()
    val left by step(input, effect = Effect.ReadOnly) { it + 1 }
    val right by step(input, effect = Effect.ReadOnly) { it * 2 }
    val join by step(left, right, effect = Effect.ReadOnly) { l, r -> l + r }
    override val output = join
}

class Route(graph: Graph) : Workflow(graph, "test.route", "Route"), Durable<Int, String> {
    override val input by input<Int>()
    val small by step(input, effect = Effect.ReadOnly, on = input.matches("below ten") { it < 10 }) { "small $it" }
    val large by step(input, effect = Effect.ReadOnly, on = !input.matches("below ten") { it < 10 }) { "large $it" }
    val answer by either(small, large)
    override val output = answer
}

class Fallback(graph: Graph) : Workflow(graph, "test.fallback", "Fallback"), Durable<Unit, String> {
    var down = true
    override val input by input<Unit>()
    val primary by step(input, effect = Effect.ReadOnly) { if (down) fail("primary is down") else "primary" }
    val backup by step(input, effect = Effect.ReadOnly, on = primary.failed) { "backup" }
    val answer by either(primary, backup)
    override val output = answer
}

class Lookup(graph: Graph) : Workflow(graph, "test.lookup", "Lookup"), Durable<String, String> {
    override val input by input<String>()
    val cached by step(input, effect = Effect.LiveRead) { key -> if (key == "warm") "cached $key" else null }
    val computed by step(input, effect = Effect.ReadOnly, on = cached.skipped) { "computed $it" }
    override val output by either(cached, computed)
}

class Saga(graph: Graph) : Workflow(graph, "test.saga", "Saga"), Durable<Unit, String> {
    val undone = mutableListOf<String>()
    override val input by input<Unit>()
    val reserve by step(input, effect = Effect.ProductionReversibleWrite, undo = { undone += "reserve:$it" }) { "seat" }
    val hold by step(reserve, effect = Effect.ProductionReversibleWrite, undo = { undone += "hold:$it" }) { "hold-$it" }
    val charge by step(hold, effect = Effect.ProductionReversibleWrite) { if (it.isNotEmpty()) fail("card declined") else "paid" }
    override val output = charge
}

class Booking(graph: Graph) : Workflow(graph, "test.booking", "Booking"), Durable<String, String> {
    val undone = mutableListOf<String>()
    override val input by input<String>()
    val reserve by step(input, effect = Effect.ProductionReversibleWrite, undo = { undone += it }) { "room for $it" }
    val confirm by signal<Boolean>()
    val finish by step(reserve, confirm, effect = Effect.ProductionReversibleWrite) { room, _ -> "booked $room" }
    override val output = finish
}

class Flaky(graph: Graph) : Workflow(graph, "test.flaky", "Flaky"), Durable<Unit, Int> {
    val keys = mutableListOf<String>()
    val checkpoints = mutableListOf<String?>()
    override val input by input<Unit>()
    val read by step(input, effect = Effect.ReadOnly) {
        keys += effectKey
        checkpoints += checkpoint
        if (attempt < 2) {
            checkpoint("halfway")
            error("blip")
        }
        attempt
    }
    override val output = read
}

class Push(graph: Graph) : Workflow(graph, "test.push", "Push"), Durable<Unit, String> {
    var broken = true
    override val input by input<Unit>()
    val push by step(input, effect = Effect.SourceWrite) { if (broken) error("connection reset") else "pushed" }
    override val output = push
    override val operatorRetry = AuthRequirement()
}

class Upload(graph: Graph) : Workflow(graph, "test.upload", "Upload"), Durable<String, String> {
    var bodies = 0
    var reconciles = 0
    var stored: String? = null
    override val input by input<String>()
    val upload: StepNode<String> by step(input, effect = Effect.ProductionReversibleWrite, timeout = 1.seconds,
        reconcile = { reconciles++; stored }) { name ->
        bodies++
        stored = "stored:$name"
        awaitCancellation()
    }
    override val output = upload
}

class Approval(graph: Graph) : Workflow(graph, "test.approval", "Approval"), Durable<String, String> {
    override val input by input<String>()
    val decision by signal<Boolean>(timeout = 1.hours, sender = AuthRequirement(permissions = setOf(PermissionRef(name = "deploy.approve"))))
    val ship by step(input, decision, effect = Effect.ProductionReversibleWrite, on = decision.matches("approved") { it }) { name, _ -> "shipped $name" }
    val hold by step(input, effect = Effect.ReadOnly, on = decision.timedOut or decision.matches("rejected") { !it }) { "held $it" }
    val outcome by either(ship, hold)
    override val output = outcome
}

class Cooldown(graph: Graph) : Workflow(graph, "test.cooldown", "Cooldown"), Durable<Unit, String> {
    override val input by input<Unit>()
    val pause by timer(10.minutes)
    val rested by step(input, pause, effect = Effect.ReadOnly) { _, _ -> "rested" }
    override val output = rested
}

class Slow(graph: Graph) : Workflow(graph, "test.slow", "Slow"), Durable<Unit, String> {
    val gate = CompletableDeferred<Unit>()
    override val input by input<Unit>()
    val work by step(input, effect = Effect.ProductionReversibleWrite) {
        gate.await()
        "late"
    }
    override val output = work
}

class Twice(graph: Graph) : Workflow(graph, "test.twice", "Twice"), Durable<Int, Int> {
    override val input by input<Int>()
    val doubled by step(input, effect = Effect.ReadOnly) { it * 2 }
    override val output = doubled
}

class Caller(graph: Graph, child: Twice) : Workflow(graph, "test.caller", "Caller"), Durable<Int, Int> {
    override val input by input<Int>()
    val called by call(child, input)
    val plusOne by step(called, effect = Effect.ReadOnly) { it + 1 }
    override val output = plusOne
}

class Fanout(graph: Graph, child: Twice) : Workflow(graph, "test.fanout", "Fan-out"), Durable<List<Int>, Int> {
    override val input by input<List<Int>>()
    val doubled by each(child, input)
    val sum by step(doubled, effect = Effect.ReadOnly) { it.sum() }
    override val output = sum
}

class Decrement(graph: Graph) : Workflow(graph, "test.decrement", "Decrement"), Durable<Int, Int> {
    override val input by input<Int>()
    val less by step(input, effect = Effect.ReadOnly) { it - 1 }
    override val output = less
}

class Countdown(graph: Graph, child: Decrement) : Workflow(graph, "test.countdown", "Countdown"), Durable<Int, Int> {
    override val input by input<Int>()
    val zero by loop(child, input, limit = 10) { if (it > 0) it else null }
    override val output = zero
}
