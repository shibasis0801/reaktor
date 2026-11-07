@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.graph.step

import dev.shibasis.reaktor.graph.Workflow
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.service.contract.Effect
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.reflect.typeOf

typealias Declaration<N> = PropertyDelegateProvider<Workflow, ReadOnlyProperty<Workflow, N>>

typealias StepBody<R> = suspend StepScope.(List<Any?>) -> R

inline fun <reified T : Any> Workflow.input(): Declaration<InputNode<T>> = storedAs<T>().let { input(it, nameOf<T>(it)) }

inline fun <reified R : Any> Workflow.step(
    effect: Effect,
    on: Condition = Condition.Always,
    timeout: Duration = 2.minutes,
    attributes: Map<String, String> = emptyMap(),
    noinline reconcile: (suspend StepScope.() -> R?)? = null,
    noinline undo: (suspend StepScope.(R) -> Unit)? = null,
    noinline body: suspend StepScope.() -> R?,
): Declaration<StepNode<R>> =
    storedAs<R>().let { format -> step(emptyList(), format, nameOf<R>(format), effect, on, timeout, attributes, reconcile?.let { spread(it) }, undo, spread(body)) }

inline fun <A : Any, reified R : Any> Workflow.step(
    a: Output<A>,
    effect: Effect,
    on: Condition = Condition.Always,
    timeout: Duration = 2.minutes,
    attributes: Map<String, String> = emptyMap(),
    noinline reconcile: (suspend StepScope.(A) -> R?)? = null,
    noinline undo: (suspend StepScope.(R) -> Unit)? = null,
    noinline body: suspend StepScope.(A) -> R?,
): Declaration<StepNode<R>> =
    storedAs<R>().let { format -> step(listOf(a), format, nameOf<R>(format), effect, on, timeout, attributes, reconcile?.let { spread(it) }, undo, spread(body)) }

inline fun <A : Any, B : Any, reified R : Any> Workflow.step(
    a: Output<A>,
    b: Output<B>,
    effect: Effect,
    on: Condition = Condition.Always,
    timeout: Duration = 2.minutes,
    attributes: Map<String, String> = emptyMap(),
    noinline reconcile: (suspend StepScope.(A, B) -> R?)? = null,
    noinline undo: (suspend StepScope.(R) -> Unit)? = null,
    noinline body: suspend StepScope.(A, B) -> R?,
): Declaration<StepNode<R>> =
    storedAs<R>().let { format -> step(listOf(a, b), format, nameOf<R>(format), effect, on, timeout, attributes, reconcile?.let { spread(it) }, undo, spread(body)) }

inline fun <A : Any, B : Any, C : Any, reified R : Any> Workflow.step(
    a: Output<A>,
    b: Output<B>,
    c: Output<C>,
    effect: Effect,
    on: Condition = Condition.Always,
    timeout: Duration = 2.minutes,
    attributes: Map<String, String> = emptyMap(),
    noinline reconcile: (suspend StepScope.(A, B, C) -> R?)? = null,
    noinline undo: (suspend StepScope.(R) -> Unit)? = null,
    noinline body: suspend StepScope.(A, B, C) -> R?,
): Declaration<StepNode<R>> =
    storedAs<R>().let { format -> step(listOf(a, b, c), format, nameOf<R>(format), effect, on, timeout, attributes, reconcile?.let { spread(it) }, undo, spread(body)) }

inline fun <A : Any, B : Any, C : Any, D : Any, reified R : Any> Workflow.step(
    a: Output<A>,
    b: Output<B>,
    c: Output<C>,
    d: Output<D>,
    effect: Effect,
    on: Condition = Condition.Always,
    timeout: Duration = 2.minutes,
    attributes: Map<String, String> = emptyMap(),
    noinline reconcile: (suspend StepScope.(A, B, C, D) -> R?)? = null,
    noinline undo: (suspend StepScope.(R) -> Unit)? = null,
    noinline body: suspend StepScope.(A, B, C, D) -> R?,
): Declaration<StepNode<R>> =
    storedAs<R>().let { format -> step(listOf(a, b, c, d), format, nameOf<R>(format), effect, on, timeout, attributes, reconcile?.let { spread(it) }, undo, spread(body)) }

fun <T : Any> Workflow.either(vararg options: Output<T>, on: Condition = Condition.Always): Declaration<EitherNode<T>> {
    require(options.size >= 2) { "Either needs two or more options" }
    return declare { graph, name -> EitherNode(graph, this, name, options.toList(), on) }
}

fun <N : Output<*>> Workflow.declare(create: (Graph, String) -> N): Declaration<N> =
    PropertyDelegateProvider { _, property ->
        val node = childGraph.Node { graph -> create(graph, property.name) }
        node.wire()
        ReadOnlyProperty { _, _ -> node }
    }

@PublishedApi
internal fun <T : Any> Workflow.input(format: KSerializer<T>?, typeName: String): Declaration<InputNode<T>> =
    declare { graph, name -> InputNode(graph, this, name, format, typeName) }

@PublishedApi
internal inline fun <reified T : Any> storedAs(): KSerializer<T>? = try { serializer<T>() } catch (unsupported: SerializationException) { null }

@PublishedApi
internal inline fun <reified T : Any> nameOf(format: KSerializer<T>?): String = format?.descriptor?.serialName ?: typeOf<T>().toString()

@PublishedApi
internal fun <R : Any> Workflow.step(
    inputs: List<Output<*>>,
    format: KSerializer<R>?,
    typeName: String,
    effect: Effect,
    on: Condition,
    timeout: Duration,
    attributes: Map<String, String>,
    reconcile: StepBody<R?>?,
    undo: (suspend StepScope.(R) -> Unit)?,
    body: StepBody<R?>,
): Declaration<StepNode<R>> =
    declare { graph, name -> StepNode(graph, this, name, format, typeName, inputs, on, effect, timeout, attributes, body, reconcile, undo) }

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
