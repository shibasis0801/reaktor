package dev.shibasis.reaktor.service.contract

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.service.HttpMethod
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.TraceContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class Served<S : Service>(val service: S) {
    val interceptors: Interceptors = Interceptors()

    var scope: InterceptorSource = InterceptorSource.None
    var identity: () -> Map<String, Any?> = { emptyMap() }

    fun intercept(vararg interceptor: Interceptor): Served<S> = apply { interceptors.add(*interceptor) }

    fun chain(): List<Installed> = Interceptors.ordered(Interceptors.process.at(0), scope.interceptors(), interceptors.at(0))

    val calls: List<Call<*, *>> get() = service.operations.filterIsInstance<Call<*, *>>()

    val streams: List<Stream<*, *>> get() = service.operations.filterIsInstance<Stream<*, *>>()

    suspend fun <In, Out> call(
        operation: Call<In, Out>,
        request: In,
        metadata: Metadata = Metadata(),
        attributes: Map<String, Any?> = emptyMap(),
    ): Out {
        val own = answering(operation)
        val exchange = incoming<In, Out>(Family.Call, own, request, metadata, attributes)
        return withContext(exchange.call + ExchangeScope(exchange)) {
            chain().proceed(exchange) { current ->
                if (current.expired) throw Unavailable("${current.subject} arrived after its deadline", 504)
                own(current.request)
            }
        }
    }

    fun <In, Out> stream(
        operation: Stream<In, Out>,
        request: In,
        metadata: Metadata = Metadata(),
        attributes: Map<String, Any?> = emptyMap(),
    ): Flow<Out> = flow {
        val own = answering(operation)
        val exchange = incoming<In, Flow<Out>>(Family.Stream, own, request, metadata, attributes)
        val opened = withContext(exchange.call + ExchangeScope(exchange)) {
            chain().proceed(exchange) { current -> own(current.request) }
        }
        emitAll(opened)
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun handle(operation: Operation<*, *>, inbound: Inbound): Outbound {
        val call = operation as? Call<Any?, Any?>
            ?: return failure(405, "${operation.id} is a ${operation.family.name.lowercase()}, not a call")
        val request = try {
            call.binding.decode(call.request, inbound)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unreadable: Throwable) {
            return failure(400, "The request for ${call.id} did not parse: ${unreadable.message ?: "malformed"}")
        }
        return try {
            val response = call(call, request, Metadata(inbound.headers), inbound.attributes)
            Outbound(statusOf(response, call.status), headersOf(response), json.encodeToString(call.response, response))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            failure.reply(call.id).let { reply ->
                Outbound(reply.status, reply.headers() + (ContentType to Json), json.encodeToString(JsonElement.serializer(), reply.body))
            }
        }
    }

    suspend fun handle(name: String, inbound: Inbound): Outbound =
        service.operation(name)?.let { handle(it, inbound) } ?: failure(404, "${service.id} has no operation '$name'")

    fun find(method: HttpMethod, path: String): Pair<Call<*, *>, Map<String, String>>? =
        calls.firstNotNullOfOrNull { call -> call.binding.match(method, path)?.let { call to it } }

    suspend fun dispatch(method: HttpMethod, path: String, inbound: Inbound): Outbound {
        val (call, parameters) = find(method, path) ?: return failure(404, "${service.id} has no route for $method $path")
        return handle(call, Inbound(inbound.pathParams + parameters, inbound.query, inbound.headers, inbound.body, inbound.attributes))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <O : Operation<*, *>> answering(operation: O): O =
        service.operation(operation.name)?.takeIf { it.family == operation.family } as? O
            ?: throw Rejected(404, "${service.id} does not answer ${operation.family.name.lowercase()} '${operation.name}'")

    private suspend fun <In, R> incoming(
        family: Family,
        operation: Operation<In, *>,
        request: In,
        metadata: Metadata,
        attributes: Map<String, Any?>,
    ): Exchange<In, R> {
        val local = currentCoroutineContext()[ServiceCall]
        val parent = TraceContext.parse(metadata[Metadata.Traceparent]) ?: local?.trace
        val session = ServiceCall.sessionFromBaggage(metadata[Metadata.Baggage])
        val all = local?.attributes.orEmpty() + identity() + attributes +
            (session?.let { mapOf(ServiceCall.SessionAttribute to it) } ?: emptyMap())
        val call = ServiceCall(parent?.child() ?: TraceContext.root(), parent?.spanId, all)
        val carried = (request as? Request)?.attributes?.apply { putAll(all); put(ServiceCall.Attribute, call) }
        return Exchange(
            family = family,
            side = Side.Server,
            contract = service.id,
            name = operation.name,
            operation = operation,
            channel = null,
            request = request,
            metadata = metadata,
            attributes = carried ?: all.toMutableMap(),
            call = call,
            deadline = metadata[Metadata.Deadline]?.toLongOrNull()?.let { Clock.System.now() + it.milliseconds },
        )
    }

    companion object {
        const val ContentType = "content-type"
        const val Json = "application/json"

        fun failure(status: Int, message: String, problem: JsonElement? = null): Outbound =
            Outbound(status, mapOf(ContentType to Json), json.encodeToString(JsonElement.serializer(), Reply.of(status, message, problem).body))

        private fun headersOf(response: Any?): Map<String, String> {
            val carried = (response as? Response)?.transportHeaders.orEmpty()
            return if (carried.keys.any { it.equals(ContentType, ignoreCase = true) }) carried else carried + (ContentType to Json)
        }

        private fun Reply.headers(): Map<String, String> =
            retryAfter?.let { mapOf(Metadata.RetryAfter to it.inWholeSeconds.coerceAtLeast(1).toString()) }.orEmpty()
    }
}

class LocalLink(override val served: Served<*>, deadline: Duration = DefaultDeadline) : Link(deadline) {
    constructor(service: Service, deadline: Duration = DefaultDeadline) : this(Served(service), deadline)

    override val transport: String get() = "local"

    override suspend fun <In, Out> transmit(operation: Call<In, Out>, exchange: Exchange<In, Out>): Out =
        served.call(operation, exchange.request, exchange.metadata.copy(), forwarded(exchange))

    override fun <In, Out> open(operation: Stream<In, Out>, exchange: Exchange<In, Flow<Out>>): Flow<Out> =
        served.stream(operation, exchange.request, exchange.metadata.copy(), forwarded(exchange))

    private fun forwarded(exchange: Exchange<*, *>): Map<String, Any?> =
        exchange.attributes.filterKeys { it.startsWith(Forwarded) }

    companion object {
        const val Forwarded = "forward."
    }
}
