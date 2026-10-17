package dev.shibasis.reaktor.work

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.registerProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

class DagValue<T : Any> internal constructor(
    internal val dag: RequestDag,
    val id: String,
    internal val port: ProviderPort<RequestStep<T>>,
)

class RequestStep<T : Any> internal constructor(
    internal val dependencies: List<DagValue<*>>,
    internal val eager: Boolean,
    internal val execute: suspend DagInputs.() -> T,
)

class DagInputs internal constructor(
    private val allowed: Set<DagValue<*>>,
    private val values: Map<DagValue<*>, Any>,
    private val resolve: suspend (DagValue<*>) -> Any,
) {
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> get(value: DagValue<T>): T {
        require(value in allowed) { "A step may only read its declared inputs" }
        return (values[value] ?: resolve(value)) as T
    }
}

/** Request lifetime only. Durable plans must persist explicit node results and control state. */
class RequestDag(graph: Graph, val definitionId: String, val version: Int) : BasicNode(graph) {
    private val values = mutableListOf<DagValue<*>>()
    private var sealed = false
    init { require(definitionId.isNotBlank() && version > 0) }

    fun <T : Any> step(
        id: String, dependencies: List<DagValue<*>> = emptyList(), execute: suspend DagInputs.() -> T,
    ): DagValue<T> = add(id, dependencies, eager = true, execute)

    fun <T : Any> branch(id: String, condition: DagValue<Boolean>, yes: DagValue<T>, no: DagValue<T>): DagValue<T> =
        add(id, listOf(condition, yes, no), eager = false) { if (get(condition)) get(yes) else get(no) }

    private fun <T : Any> add(
        id: String, dependencies: List<DagValue<*>>, eager: Boolean, execute: suspend DagInputs.() -> T,
    ): DagValue<T> {
        require(!sealed && id.isNotBlank() && values.none { it.id == id } && values.size < 1024)
        // Inputs must already be declared in this definition: cycles and foreign run inputs cannot enter.
        require(dependencies.all { it.dag === this && it in values })
        val value = DagValue(this, id, registerProvider(id, RequestStep(dependencies, eager, execute)))
        values += value
        return value
    }

    suspend fun <T : Any> run(target: DagValue<T>, parallelism: Int = 4): T = coroutineScope {
        require(target.dag === this@RequestDag && parallelism in 1..64)
        sealed = true
        val memo = mutableMapOf<DagValue<*>, Deferred<Any>>()
        val mutex = Mutex()
        val permits = Semaphore(parallelism)
        val runScope = this
        lateinit var resolve: suspend (DagValue<*>) -> Any
        resolve = { value ->
            val result = mutex.withLock {
                memo.getOrPut(value) {
                    runScope.async(start = CoroutineStart.LAZY) {
                        val operation = value.port.impl
                        val inputs = if (operation.eager) operation.dependencies.map { input ->
                            async { input to resolve(input) }
                        }.awaitAll().toMap() else emptyMap()
                        val context = DagInputs(operation.dependencies.toSet(), inputs, resolve)
                        // A lazy branch awaits its selected dependency outside a permit to avoid deadlock.
                        if (!operation.eager) value.port.suspended { context.execute() }
                        else permits.withPermit { value.port.suspended { context.execute() } }
                    }
                }
            }
            result.await()
        }
        @Suppress("UNCHECKED_CAST")
        (resolve(target) as T)
    }
}
