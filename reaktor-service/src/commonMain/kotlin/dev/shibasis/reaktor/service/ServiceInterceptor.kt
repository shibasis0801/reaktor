package dev.shibasis.reaktor.service

enum class ServiceExecutionPhase {
    CLIENT,
    SERVER,
}

enum class InterceptorStage {
    CLIENT_APPLICATION,
    CLIENT_TRANSPORT,
    SERVER_TRANSPORT,
    SERVER_APPLICATION,
    CONNECTION_SETUP;

    companion object {
        val All: Set<InterceptorStage> = values().toSet()
    }
}

class InterceptorContext<In : Request, Out : Response>(
    val phase: ServiceExecutionPhase,
    val stage: InterceptorStage,
    val handler: RequestHandler<In, Out>,
    val request: In,
) {
    val operation: String get() = handler.endpoint.operation
    val method: String? get() = handler.endpoint.method?.name
    val path: String get() = handler.route
    val headers: MutableMap<String, String> get() = request.headers
    val query: MutableMap<String, String> get() = request.queryParams
    val attributes: MutableMap<String, Any?> get() = request.attributes
    val environment: Environment get() = request.environment
}

interface ServiceInterceptor {
    val stages: Set<InterceptorStage>
        get() = InterceptorStage.All

    suspend fun <In : Request, Out : Response> intercept(chain: ServiceChain<In, Out>): Out
}

abstract class ServiceChain<In : Request, Out : Response> {
    abstract val phase: ServiceExecutionPhase
    abstract val stage: InterceptorStage
    abstract val handler: RequestHandler<In, Out>
    abstract val request: In
    abstract val context: InterceptorContext<In, Out>

    val attributes: MutableMap<String, Any?> get() = context.attributes

    suspend fun proceed(): Out = proceed(request)

    abstract suspend fun proceed(request: In): Out
}

private class StageBoundServiceInterceptor(
    private val delegate: ServiceInterceptor,
    override val stages: Set<InterceptorStage>,
) : ServiceInterceptor {
    override suspend fun <In : Request, Out : Response> intercept(chain: ServiceChain<In, Out>): Out =
        delegate.intercept(chain)
}

internal fun ServiceInterceptor.boundTo(stages: Set<InterceptorStage>): ServiceInterceptor =
    StageBoundServiceInterceptor(this, stages)

internal class DefaultServiceChain<In : Request, Out : Response>(
    override val phase: ServiceExecutionPhase,
    override val stage: InterceptorStage,
    override val handler: RequestHandler<In, Out>,
    override val request: In,
    private val interceptors: List<ServiceInterceptor>,
    private val index: Int,
    private val terminal: suspend (In) -> Out,
) : ServiceChain<In, Out>() {
    override val context: InterceptorContext<In, Out> =
        InterceptorContext(
            phase = phase,
            stage = stage,
            handler = handler,
            request = request,
        )

    override suspend fun proceed(request: In): Out {
        val interceptorIndex = nextInterceptorIndex(index)
        return interceptors.getOrNull(interceptorIndex)?.intercept(
            DefaultServiceChain(
                phase = phase,
                stage = stage,
                handler = handler,
                request = request,
                interceptors = interceptors,
                index = interceptorIndex + 1,
                terminal = terminal,
            ),
        ) ?: terminal(request)
    }

    private fun nextInterceptorIndex(start: Int): Int {
        var current = start
        while (current < interceptors.size && stage !in interceptors[current].stages) {
            current += 1
        }
        return current
    }
}

suspend fun <In : Request, Out : Response> runInterceptorChain(
    phase: ServiceExecutionPhase,
    stage: InterceptorStage,
    handler: RequestHandler<In, Out>,
    request: In,
    interceptors: List<ServiceInterceptor>,
    terminal: suspend (In) -> Out,
): Out = DefaultServiceChain(
    phase = phase,
    stage = stage,
    handler = handler,
    request = request,
    interceptors = interceptors,
    index = 0,
    terminal = terminal,
).proceed()
