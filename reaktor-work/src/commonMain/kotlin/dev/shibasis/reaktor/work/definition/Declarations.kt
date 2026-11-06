package dev.shibasis.reaktor.work.definition

import dev.shibasis.reaktor.auth.kernel.AuthRequirement
import dev.shibasis.reaktor.graph.Workflow
import dev.shibasis.reaktor.graph.step.Condition
import dev.shibasis.reaktor.graph.step.Declaration
import dev.shibasis.reaktor.graph.step.Output
import dev.shibasis.reaktor.graph.step.declare
import kotlin.time.Duration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

inline fun <reified T : Any> Workflow.signal(
    timeout: Duration? = null,
    sender: AuthRequirement? = null,
    on: Condition = Condition.Always,
): Declaration<SignalNode<T>> = signal(serializer<T>(), timeout, sender, on)

fun Workflow.timer(after: Duration, on: Condition = Condition.Always): Declaration<TimerNode> =
    declare { graph, name -> TimerNode(graph, this, name, after, on) }

fun <I : Any, O : Any> Workflow.call(target: Durable<I, O>, input: Output<I>, on: Condition = Condition.Always): Declaration<CallNode<I, O>> =
    declare { graph, name -> CallNode(graph, this, name, target, input, on) }

fun <I : Any, O : Any> Workflow.each(target: Durable<I, O>, items: Output<List<I>>, on: Condition = Condition.Always): Declaration<EachNode<I, O>> =
    declare { graph, name -> EachNode(graph, this, name, target, items, on) }

fun <I : Any, O : Any> Workflow.loop(
    target: Durable<I, O>,
    seed: Output<I>,
    limit: Int,
    on: Condition = Condition.Always,
    next: (O) -> I?,
): Declaration<LoopNode<I, O>> = declare { graph, name -> LoopNode(graph, this, name, target, seed, limit, next, on) }

@PublishedApi
internal fun <T : Any> Workflow.signal(serializer: KSerializer<T>, timeout: Duration?, sender: AuthRequirement?, on: Condition): Declaration<SignalNode<T>> =
    declare { graph, name -> SignalNode(graph, this, name, serializer, timeout, sender, on) }
