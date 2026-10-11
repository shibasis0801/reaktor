package dev.shibasis.reaktor.graph.core

import kotlin.reflect.KClass

/** An immutable activation configuration. Recipes remain in the graph that owns the service. */
data class GraphProfile<M : Any> private constructor(
    val default: M,
    private val modes: Map<String, M>,
    private val values: Map<Pair<KClass<*>, String>, Any>,
) {
    constructor(default: M) : this(default, emptyMap(), emptyMap())

    fun mode(key: String): M = modes[key] ?: default

    fun selecting(selections: Map<String, M>): GraphProfile<M> = copy(modes = modes + selections)

    inline fun <reified T : Any> replacing(key: String, value: T): GraphProfile<M> = replacing(T::class, key, value)

    fun <T : Any> replacing(type: KClass<T>, key: String, value: T): GraphProfile<M> =
        copy(values = values + ((type to key) to value))

    inline fun <reified T : Any> create(key: String, factory: (M) -> T): T =
        replacement(T::class, key) ?: factory(mode(key))

    fun <T : Any> replacement(type: KClass<T>, key: String): T? {
        @Suppress("UNCHECKED_CAST")
        return values[type to key] as T?
    }
}
