package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.graph.consumes
import dev.shibasis.reaktor.graph.intercept
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.graph.remote
import dev.shibasis.reaktor.portgraph.graph.connect
import dev.shibasis.reaktor.service.contract.Call
import dev.shibasis.reaktor.service.contract.CallAttributes
import dev.shibasis.reaktor.service.contract.CallFailure
import dev.shibasis.reaktor.service.contract.Compatibility
import dev.shibasis.reaktor.service.contract.ContractDrift
import dev.shibasis.reaktor.service.contract.OutcomeUnknown
import dev.shibasis.reaktor.service.contract.Rejected
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.service.contract.Unavailable
import dev.shibasis.reaktor.service.contract.over
import dev.shibasis.reaktor.tooling.atlas.AtlasApi
import dev.shibasis.reaktor.tooling.changes.ChangesApi
import dev.shibasis.reaktor.tooling.checks.ChecksApi
import dev.shibasis.reaktor.tooling.kernel.KernelApi
import dev.shibasis.reaktor.tooling.kernel.KernelHello
import dev.shibasis.reaktor.tooling.kernel.KernelInvocation
import dev.shibasis.reaktor.tooling.operation.*
import dev.shibasis.reaktor.tooling.runs.RunsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import org.koin.dsl.koinApplication

class KernelClient internal constructor(private val connection: AgentWorkspaceConnection) : AutoCloseable {
    private val mutex = Mutex()
    private var state: Connected? = null

    suspend fun invoke(invocation: Invocation): Outcome = try {
        val attributes = buildMap<String, Any?> {
            put(OperationGate.CallerAttribute, invocation.caller)
            invocation.requestId?.let { put(OperationGate.RequestIdAttribute, it) }
        }
        withContext(CallAttributes(attributes)) {
            val current = mutex.withLock {
                val incarnation = connection.incarnation
                state?.takeIf { it.incarnation == incarnation } ?: Connected(incarnation).also {
                    try { withContext(CallAttributes(mapOf(OperationGate.CallerAttribute to invocation.caller))) { it.verify() } } catch (failure: Throwable) { it.close(); throw failure }
                    state?.close()
                    state = it
                }
            }
            val result = when (invocation.operation.value) {
                "operation.catalog" -> current.call(current.kernel.catalog, OperationJson.decodeFromJsonElement(CatalogQuery.serializer(), invocation.arguments))
                "operation.describe" -> current.call(current.kernel.describe, OperationJson.decodeFromJsonElement(DescribeRequest.serializer(), invocation.arguments))
                else -> current.calls[invocation.operation]?.let { port ->
                    val call = port.call.call
                    val request = OperationJson.decodeFromJsonElement(call.request, invocation.arguments)
                    OperationJson.encodeToJsonElement(call.response, port.call(request))
                } ?: current.call(current.kernel.invoke, KernelInvocation(invocation.operation, invocation.arguments))
            }
            Outcome.Completed(result)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Rejected) {
        failure.problemAs<Outcome>() ?: Outcome.Failed(failure.message ?: "Rejected", "rejected")
    } catch (failure: SerializationException) {
        Outcome.Failed("Invalid arguments for ${invocation.operation}: ${failure.message}", "invalid-arguments")
    } catch (failure: CallFailure) {
        Outcome.Failed(failure.message ?: "Contract call failed", when (failure) {
            is OutcomeUnknown -> "outcome-unknown"
            is ContractDrift -> "contract-drift"
            else -> "unavailable"
        })
    }

    override fun close() { state?.close(); state = null }

    private inner class Connected(val incarnation: String) : AutoCloseable {
        private val dependencies = koinApplication {}
        private var fingerprints: Map<String, String> = emptyMap()
        private val graph = Graph(dependencyAdapter = KoinDependencyAdapter(dependencies)).intercept(object : dev.shibasis.reaktor.service.contract.Interceptor {
            override val sides get() = setOf(dev.shibasis.reaktor.service.contract.Side.Client)
            override suspend fun <In, R> intercept(exchange: dev.shibasis.reaktor.service.contract.Exchange<In, R>, next: dev.shibasis.reaktor.service.contract.Next<In, R>): R {
                fingerprints[exchange.contract]?.let { exchange.metadata[dev.shibasis.reaktor.service.contract.Metadata.Contract] = "${exchange.contract}@$it" }
                return next(exchange)
            }
        })
        val kernel = KernelApi()
        private val apis = listOf(kernel, AtlasApi(), ChangesApi(), ChecksApi(), RunsApi(), PlansApi())
        val calls = linkedMapOf<OperationId, Consumer>()

        init {
            for (api in apis) {
                val provider = graph.remote(api over connection.contractLink())
                for (operation in api.operations.filterIsInstance<Call<*, *>>()) {
                    @Suppress("UNCHECKED_CAST")
                    val consumer = Consumer(graph, operation as Call<Any?, Any?>)
                    graph.attach(consumer)
                    connect(consumer, provider)
                    calls[OperationId("${api.id.substringAfterLast('.')}.${operation.name}")] = consumer
                }
            }
        }

        suspend fun verify() {
            val hello = OperationJson.decodeFromJsonElement(KernelHello.serializer(), call(kernel.hello, Unit))
            if (hello.incarnation != incarnation || hello.workspaceRoot != connection.workspaceRoot)
                throw ContractDrift("The owner identity changed during connection", 409)
            for (api in apis) {
                val shape = hello.contracts.singleOrNull { it.id == api.id }
                    ?: throw ContractDrift("The owner does not serve ${api.id}", 409)
                val compatibility = Compatibility.between(api.shape, shape)
                if (compatibility.verdict == dev.shibasis.reaktor.service.contract.Verdict.Incompatible)
                    throw ContractDrift("Incompatible ${api.id}: ${compatibility.issues.joinToString()}", 409)
            }
            fingerprints = hello.contracts.associate { it.id to it.fingerprint }
        }

        suspend fun <In, Out> call(operation: Call<In, Out>, request: In): JsonElement {
            val port = calls.getValue(OperationId("${operation.service.id.substringAfterLast('.')}.${operation.name}"))
            @Suppress("UNCHECKED_CAST")
            val response = port.call(request) as Out
            return OperationJson.encodeToJsonElement(operation.response, response)
        }

        override fun close() { try { graph.close() } finally { dependencies.close() } }
    }

    private class Consumer(graph: Graph, operation: Call<Any?, Any?>) : BasicNode(graph) {
        override val label = "Kernel client ${operation.id}"
        val call by consumes(operation)
    }
}
