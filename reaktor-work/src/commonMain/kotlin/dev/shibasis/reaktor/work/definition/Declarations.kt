@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.work.definition

import dev.shibasis.reaktor.auth.kernel.AuthRequirement
import dev.shibasis.reaktor.graph.Workflow
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.service.contract.Effect
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

typealias Declaration<N> = PropertyDelegateProvider<Workflow, ReadOnlyProperty<Workflow, N>>

typealias StepBody<R> = suspend StepScope.(List<Any?>) -> R

inline fun <reified T : Any> Workflow.input(): Declaration<InputNode<T>> = input(serializer<T>())

inline fun <reified R : Any> Workflow.step(
    effect: Effect,
    on: Condition = Condition.Always,
    timeout: Duration = 2.minutes,
    noinline reconcile: (suspend StepScope.() -> R?)? = null,
    noinline undo: (suspend StepScope.(R) -> Unit)? = null,
    noinline body: suspend StepScope.() -> R,
): Declaration<StepNode<R>> =
    step(emptyList(), serializer<R>(), effect, on, timeout, reconcile?.let { spread(it) }, undo, spread(body))

inline fun <A : Any, reified R : Any> Workflow.step(
    a: Output<A>,
    effect: Effect,
    on: Condition = Condition.Always,
    timeout: Duration = 2.minutes,
    noinline reconcile: (suspend StepScope.(A) -> R?)? = null,
    noinline undo: (suspend StepScope.(R) -> Unit)? = null,
    noinline body: suspend StepScope.(A) -> R,
): Declaration<StepNode<R>> =
    step(listOf(a), serializer<R>(), effect, on, timeout, reconcile?.let { spread(it) }, undo, spread(body))

inline fun <A : Any, B : Any, reified R : Any> Workflow.step(
    a: Output<A>,
    b: Output<B>,
    effect: Effect,
    on: Condition = Condition.Always,
    timeout: Duration = 2.minutes,
    noinline reconcile: (suspend StepScope.(A, B) -> R?)? = null,
    noinline undo: (suspend StepScope.(R) -> Unit)? = null,
    noinline body: suspend StepScope.(A, B) -> R,
): Declaration<StepNode<R>> =
    step(listOf(a, b), serializer<R>(), effect, on, timeout, reconcile?.let { spread(it) }, undo, spread(body))

inline fun <A : Any, B : Any, C : Any, reified R : Any> Workflow.step(
    a: Output<A>,
    b: Output<B>,
    c: Output<C>,
    effect: Effect,
    on: Condition = Condition.Always,
    timeout: Duration = 2.minutes,
    noinline reconcile: (suspend StepScope.(A, B, C) -> R?)? = null,
    noinline undo: (suspend StepScope.(R) -> Unit)? = null,
    noinline body: suspend StepScope.(A, B, C) -> R,
): Declaration<StepNode<R>> =
    step(listOf(a, b, c), serializer<R>(), effect, on, timeout, reconcile?.let { spread(it) }, undo, spread(body))

inline fun <A : Any, B : Any, C : Any, D : Any, reified R : Any> Workflow.step(
    a: Output<A>,
    b: Output<B>,
    c: Output<C>,
    d: Output<D>,
    effect: Effect,
    on: Condition = Condition.Always,
    timeout: Duration = 2.minutes,
    noinline reconcile: (suspend StepScope.(A, B, C, D) -> R?)? = null,
    noinline undo: (suspend StepScope.(R) -> Unit)? = null,
    noinline body: suspend StepScope.(A, B, C, D) -> R,
): Declaration<StepNode<R>> =
    step(listOf(a, b, c, d), serializer<R>(), effect, on, timeout, reconcile?.let { spread(it) }, undo, spread(body))

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

fun <T : Any> Workflow.either(vararg options: Output<T>, on: Condition = Condition.Always): Declaration<EitherNode<T>> {
    require(options.size >= 2) { "Either needs two or more options" }
    return declare { graph, name -> EitherNode(graph, this, name, options.toList(), on) }
}

@PublishedApi
internal fun <T : Any> Workflow.input(serializer: KSerializer<T>): Declaration<InputNode<T>> =
    declare { graph, name -> InputNode(graph, this, name, serializer) }

@PublishedApi
internal fun <R : Any> Workflow.step(
    inputs: List<Output<*>>,
    serializer: KSerializer<R>,
    effect: Effect,
    on: Condition,
    timeout: Duration,
    reconcile: StepBody<R?>?,
    undo: (suspend StepScope.(R) -> Unit)?,
    body: StepBody<R>,
): Declaration<StepNode<R>> =
    declare { graph, name -> StepNode(graph, this, name, serializer, inputs, on, effect, timeout, body, reconcile, undo) }

@PublishedApi
internal fun <T : Any> Workflow.signal(serializer: KSerializer<T>, timeout: Duration?, sender: AuthRequirement?, on: Condition): Declaration<SignalNode<T>> =
    declare { graph, name -> SignalNode(graph, this, name, serializer, timeout, sender, on) }

@PublishedApi
internal fun <R> spread(body: suspend StepScope.() -> R): StepBody<R> = { body() }

@PublishedApi
internal fun <A, R> spread(body: suspend StepScope.(A) -> R): StepBody<R> = { values -> body(values[0] as A) }

@PublishedApi
internal fun <A, B, R> spread(body: suspend StepScope.(A, B) -> R): StepBody<R> =
    { values -> body(values[0] as A, values[1] as B) }

@PublishedApi
internal fun <A, B, C, R> spread(body: suspend StepScope.(A, B, C) -> R): StepBody<R> =
    { values -> body(values[0] as A, values[1] as B, values[2] as C) }

@PublishedApi
internal fun <A, B, C, D, R> spread(body: suspend StepScope.(A, B, C, D) -> R): StepBody<R> =
    { values -> body(values[0] as A, values[1] as B, values[2] as C, values[3] as D) }

private fun <N : Output<*>> Workflow.declare(create: (Graph, String) -> N): Declaration<N> =
    PropertyDelegateProvider { _, property ->
        val node = childGraph.Node { graph -> create(graph, property.name) }
        node.wire()
        ReadOnlyProperty { _, _ -> node }
    }
