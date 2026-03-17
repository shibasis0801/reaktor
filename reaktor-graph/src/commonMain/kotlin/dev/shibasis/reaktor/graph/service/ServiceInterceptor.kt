package dev.shibasis.reaktor.graph.service

enum class ServiceExecutionPhase {
    CLIENT,
    SERVER,
}

interface ServiceInterceptor {
    suspend fun <In : Request, Out : Response> intercept(chain: ServiceChain<In, Out>): Out
}

abstract class ServiceChain<In : Request, Out : Response> {
    abstract val phase: ServiceExecutionPhase
    abstract val handler: RequestHandler<In, Out>
    abstract val request: In

    suspend fun proceed(): Out = proceed(request)

    abstract suspend fun proceed(request: In): Out
}

internal class DefaultServiceChain<In : Request, Out : Response>(
    override val phase: ServiceExecutionPhase,
    override val handler: RequestHandler<In, Out>,
    override val request: In,
    private val interceptors: List<ServiceInterceptor>,
    private val index: Int,
    private val terminal: suspend (In) -> Out,
) : ServiceChain<In, Out>() {
    override suspend fun proceed(request: In): Out =
        interceptors.getOrNull(index)?.intercept(
            DefaultServiceChain(
                phase = phase,
                handler = handler,
                request = request,
                interceptors = interceptors,
                index = index + 1,
                terminal = terminal,
            ),
        ) ?: terminal(request)
}
