package dev.shibasis.reaktor.service.contract

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.serialization.Serializable

typealias Next<In, R> = suspend (Exchange<In, R>) -> R

interface Interceptor {
    val name: String get() = this::class.simpleName ?: "Interceptor"
    val position: Int get() = Position.Application
    val sides: Set<Side> get() = Side.Both
    val families: Set<Family> get() = AllFamilies

    suspend fun <In, R> intercept(exchange: Exchange<In, R>, next: Next<In, R>): R

    fun admits(exchange: Exchange<*, *>): Boolean = exchange.side in sides && exchange.family in families
}

object Position {
    const val Telemetry = 100
    const val Deadline = 200
    const val Retry = 300
    const val Security = 400
    const val Application = 500
}

private val AllFamilies: Set<Family> = Family.entries.toSet()

enum class InterceptorScope { Process, Graph, Node, Endpoint }

@Serializable
data class InterceptorShape(
    val name: String,
    val position: Int,
    val scope: String,
    val sides: List<String>,
    val families: List<String>,
)

class Installed(val interceptor: Interceptor, val scope: InterceptorScope, val depth: Int, val sequence: Int) {
    val shape: InterceptorShape
        get() = InterceptorShape(
            name = interceptor.name,
            position = interceptor.position,
            scope = scope.name,
            sides = interceptor.sides.map { it.name }.sorted(),
            families = interceptor.families.map { it.name }.sorted(),
        )
}

class Interceptors(val scope: InterceptorScope = InterceptorScope.Endpoint) {
    private val installed = atomic(emptyList<Interceptor>())

    val all: List<Interceptor> get() = installed.value

    fun add(vararg interceptor: Interceptor) {
        installed.update { it + interceptor }
    }

    fun remove(interceptor: Interceptor) {
        installed.update { current -> current.filterNot { it === interceptor } }
    }

    fun at(depth: Int): List<Installed> = all.mapIndexed { index, it -> Installed(it, scope, depth, index) }

    companion object {
        val process = Interceptors(InterceptorScope.Process)

        fun install(interceptor: Interceptor): () -> Unit {
            process.add(interceptor)
            return { process.remove(interceptor) }
        }

        fun ordered(vararg layers: List<Installed>): List<Installed> =
            layers.flatMap { it }.sortedWith(
                compareBy<Installed>({ it.interceptor.position }, { it.scope.ordinal }, { it.depth }, { it.sequence }),
            )
    }
}

fun interface InterceptorSource {
    fun interceptors(): List<Installed>

    companion object {
        val None = InterceptorSource { emptyList() }
    }
}

fun Iterable<Interceptor>.installedAs(scope: InterceptorScope, depth: Int = 0): List<Installed> =
    mapIndexed { index, it -> Installed(it, scope, depth, index) }

internal suspend fun <In, R> List<Installed>.proceed(exchange: Exchange<In, R>, terminal: Next<In, R>): R {
    val chain = filter { it.interceptor.admits(exchange) }.map { it.interceptor }
    suspend fun step(index: Int, current: Exchange<In, R>): R =
        if (index == chain.size) terminal(current) else chain[index].intercept(current) { step(index + 1, it) }
    return step(0, exchange)
}
