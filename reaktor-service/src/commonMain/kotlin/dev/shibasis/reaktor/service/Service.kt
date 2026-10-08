package dev.shibasis.reaktor.service

import dev.shibasis.reaktor.core.framework.kSerializer
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.io.network.http
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlin.js.JsExport
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.ktor.http.HttpMethod as KtorMethod
import kotlinx.coroutines.withTimeoutOrNull

@JsExport
abstract class Service(
    baseUrl: String = "",
    httpClient: HttpClient? = null
) {
    private val suppliedHttpClient = httpClient
    val httpClient: HttpClient get() = suppliedHttpClient ?: http

    val handlers = arrayListOf<RequestHandler<*, *>>()
    val baseUrl: String = baseUrl.trimEnd('/')

    open val contract: ServiceContract get() = ServiceContract.Unnamed

    @JsExport.Ignore
    val operations: List<OperationDescriptor> get() = handlers.map { it.descriptor(contract) }
    private val interceptors = arrayListOf<ServiceInterceptor>()

    fun use(vararg interceptor: ServiceInterceptor): Service = apply {
        interceptors += interceptor
    }

    @JsExport.Ignore
    fun use(
        stages: Set<InterceptorStage>,
        vararg interceptor: ServiceInterceptor,
    ): Service = apply {
        interceptors += interceptor.map { it.boundTo(stages) }
    }

    /**
     * The chain this service runs, its own plus anything installed process-wide.
     *
     * Global interceptors exist for one reason: an observer that has to be attached to every
     * service instance by hand is an observer that misses the one nobody remembered. DevTools
     * installs its traffic tap here, so a call is captured because it crossed a boundary rather
     * than because someone wired the service up.
     */
    protected open fun serviceInterceptors(): List<ServiceInterceptor> =
        if (globalInterceptors.isEmpty()) interceptors else globalInterceptors + interceptors

    private suspend fun <In : Request, Out : Response> invokeWithInterceptors(
        phase: ServiceExecutionPhase,
        stage: InterceptorStage,
        handler: RequestHandler<In, Out>,
        request: In,
        terminal: suspend (In) -> Out,
    ): Out = DefaultServiceChain(
        phase = phase,
        stage = stage,
        handler = handler,
        request = request,
        interceptors = serviceInterceptors(),
        index = 0,
        terminal = terminal,
    ).proceed()

    companion object {
        private val globals = arrayListOf<ServiceInterceptor>()

        /** Interceptors every service in this process runs, outermost first. */
        val globalInterceptors: List<ServiceInterceptor> get() = globals

        /** Installs a process-wide interceptor and hands back the undo. */
        @JsExport.Ignore
        fun installGlobal(interceptor: ServiceInterceptor): () -> Unit {
            globals += interceptor
            return { globals -= interceptor }
        }
    }

    fun <In : Request, Out: Response> server(
        factory: RequestHandler.Factory,
        endpoint: String,
        operation: String? = null,
        requestSerializer: KSerializer<In>,
        responseSerializer: KSerializer<Out>,
        block: RequestHandlerBlock<In, Out>
    ): RequestHandler<In, Out> {
        lateinit var created: RequestHandler<In, Out>
        created = factory.create(baseUrl + endpoint, operation ?: endpoint, requestSerializer, responseSerializer) { request ->
            val caller = currentCoroutineContext()[ServiceCall]
            caller?.attributes?.forEach { (key, value) -> if (key !in request.attributes) request.attributes[key] = value }
            val parent = TraceContext.parse(request.headers.header(TraceContext.Header)) ?: caller?.trace
            ServiceCall.sessionFromBaggage(request.headers.header("baggage"))?.let {
                request.attributes[ServiceCall.SessionAttribute] = it
            }
            val call = ServiceCall(parent?.child() ?: TraceContext.root(), parent?.spanId, request.attributes)
            request.attributes[ServiceCall.Attribute] = call
            withContext(call) {
                invokeWithInterceptors(ServiceExecutionPhase.SERVER, InterceptorStage.SERVER_APPLICATION, created, request) { intercepted ->
                    block(created, intercepted)
                }
            }
        }
        created.pinned = operation != null
        created.owner = this
        handlers += created
        return created
    }

    fun <In : Request, Out : Response> client(
        factory: RequestHandler.Factory,
        route: String,
        operation: String? = null,
        requestSerializer: KSerializer<In>,
        responseSerializer: KSerializer<Out>
    ): RequestHandler<In, Out> {
        lateinit var created: RequestHandler<In, Out>
        created = factory.create(route, operation ?: route, requestSerializer, responseSerializer) { request ->
            val caller = currentCoroutineContext()[ServiceCall]
            withContext(ServiceCall(caller?.trace?.child() ?: TraceContext.root(), caller?.trace?.spanId, request.attributes + caller?.attributes.orEmpty())) {
                invokeWithInterceptors(ServiceExecutionPhase.CLIENT, InterceptorStage.CLIENT_APPLICATION, created, request) { intercepted ->
                    val fullUrl = baseUrl + created.url(intercepted)
                    val ktorMethod = created.method.toKtorMethod()

                    val response = withTimeoutOrNull(ClientDeadline) { persistently(ktorMethod) {
                        httpClient.request(fullUrl) {
                            method = ktorMethod
                            timeout {
                                connectTimeoutMillis = ClientConnectTimeout.inWholeMilliseconds
                                socketTimeoutMillis = ClientIdleTimeout.inWholeMilliseconds
                            }
                            headers.append(Environment.Header, intercepted.environment.name)
                            intercepted.headers.forEach { (k, v) -> headers.append(k, v) }
                            intercepted.queryParams.forEach { (k, v) -> url.parameters.append(k, v) }

                            when (method) {
                                KtorMethod.Post, KtorMethod.Put, KtorMethod.Patch -> {
                                    contentType(ContentType.Application.Json)
                                    setBody(json.encodeToString(requestSerializer, intercepted))
                                }
                            }
                        }
                    } } ?: throw ServiceUnanswered(ClientDeadline)

                    val body = response.bodyAsText()
                    val decoded = try {
                        json.decodeFromString(responseSerializer, body)
                    } catch (unreadable: IllegalArgumentException) {
                        throw ServiceStatusException(response.status.value, unreadable)
                    }
                    decoded.applyTransportMetadata(
                        headers = response.headers.entries().associate { (key, values) -> key to values.joinToString(", ") },
                        statusCode = StatusCode(response.status.value),
                    )
                    decoded
                }
            }
        }
        created.phase = ServiceExecutionPhase.CLIENT
        created.pinned = operation != null
        created.owner = this
        handlers += created
        return created
    }
}

inline fun <reified In : Request, reified Out: Response> Service.server(
    factory: RequestHandler.Factory,
    endpoint: String,
    operation: String? = null,
    noinline block: RequestHandlerBlock<In, Out>
) = server(factory, endpoint, operation, kSerializer<In>(), kSerializer<Out>(), block)

inline fun <reified In : Request, reified Out: Response> Service.client(
    factory: RequestHandler.Factory,
    endpoint: String,
    operation: String? = null,
) = client(factory, endpoint, operation, kSerializer<In>(), kSerializer<Out>())

inline fun <reified In: Request, reified Out: Response> Service.GetHandler(
    endpoint: String,
    operation: String? = null,
    noinline block: RequestHandlerBlock<In, Out>
) = server(GetHandler.Companion, endpoint, operation, block) as GetHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.GetHandler(
    endpoint: String,
    operation: String? = null,
) = client<In, Out>(GetHandler.Companion, endpoint, operation) as GetHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.PostHandler(
    endpoint: String,
    operation: String? = null,
    noinline block: RequestHandlerBlock<In, Out>
) = server(PostHandler.Companion, endpoint, operation, block) as PostHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.PostHandler(
    endpoint: String,
    operation: String? = null,
) = client<In, Out>(PostHandler.Companion, endpoint, operation) as PostHandler<In, Out>


inline fun <reified In: Request, reified Out: Response> Service.PutHandler(
    endpoint: String,
    operation: String? = null,
    noinline block: RequestHandlerBlock<In, Out>
) = server(PutHandler.Companion, endpoint, operation, block) as PutHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.PutHandler(
    endpoint: String,
    operation: String? = null,
) = client<In, Out>(PutHandler.Companion, endpoint, operation) as PutHandler<In, Out>


inline fun <reified In: Request, reified Out: Response> Service.DeleteHandler(
    endpoint: String,
    operation: String? = null,
    noinline block: RequestHandlerBlock<In, Out>
) = server(DeleteHandler.Companion, endpoint, operation, block) as DeleteHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.DeleteHandler(
    endpoint: String,
    operation: String? = null,
) = client<In, Out>(DeleteHandler.Companion, endpoint, operation) as DeleteHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.PatchHandler(
    endpoint: String,
    operation: String? = null,
    noinline block: RequestHandlerBlock<In, Out>
) = server(PatchHandler.Companion, endpoint, operation, block) as PatchHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.PatchHandler(
    endpoint: String,
    operation: String? = null,
) = client<In, Out>(PatchHandler.Companion, endpoint, operation) as PatchHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.OptionsHandler(
    endpoint: String,
    operation: String? = null,
    noinline block: RequestHandlerBlock<In, Out>
) = server(OptionsHandler.Companion, endpoint, operation, block) as OptionsHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.OptionsHandler(
    endpoint: String,
    operation: String? = null,
) = client<In, Out>(OptionsHandler.Companion, endpoint, operation) as OptionsHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.HeadHandler(
    endpoint: String,
    operation: String? = null,
    noinline block: RequestHandlerBlock<In, Out>
) = server(HeadHandler.Companion, endpoint, operation, block) as HeadHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.HeadHandler(
    endpoint: String,
    operation: String? = null,
) = client<In, Out>(HeadHandler.Companion, endpoint, operation) as HeadHandler<In, Out>

class ServiceStatusException(val status: Int, cause: Throwable? = null) : IllegalStateException("The server answered $status in a shape this client cannot read", cause)

class ServiceUnanswered(deadline: Duration) : IllegalStateException("No answer within $deadline")

private val ClientDeadline = 25.seconds
private val ClientConnectTimeout = 10.seconds
private val ClientIdleTimeout = 30.seconds
private const val ClientAttempts = 3
private val ClientBackoff = listOf(400.milliseconds, 1200.milliseconds)
private val ClientRetryAfterCap = 4.seconds
private val Idempotent = setOf(KtorMethod.Get, KtorMethod.Head, KtorMethod.Put, KtorMethod.Delete, KtorMethod.Options)
private val Unprocessed = setOf(429, 503)
private val Transient = setOf(408, 500, 502, 504, 520, 521, 522, 523, 524, 525, 526, 527, 530)

internal suspend fun persistently(method: KtorMethod, send: suspend () -> HttpResponse): HttpResponse {
    val idempotent = method in Idempotent
    var attempt = 1
    while (true) {
        val response = try {
            send()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (attempt >= ClientAttempts || !(idempotent || failure is ConnectTimeoutException)) throw failure
            delay(backoff(attempt, null))
            attempt += 1
            continue
        }
        val status = response.status.value
        val retry = status in Unprocessed || (idempotent && status in Transient)
        if (!retry || attempt >= ClientAttempts) return response
        delay(backoff(attempt, response.headers["Retry-After"]))
        attempt += 1
    }
}

private fun backoff(attempt: Int, retryAfter: String?): Duration {
    val asked = retryAfter?.trim()?.toIntOrNull()?.seconds?.coerceAtMost(ClientRetryAfterCap)
    val base = ClientBackoff[(attempt - 1).coerceIn(0, ClientBackoff.lastIndex)]
    return asked ?: (base * Random.nextDouble(0.7, 1.3))
}
