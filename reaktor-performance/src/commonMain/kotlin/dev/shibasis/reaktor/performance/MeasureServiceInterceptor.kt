package dev.shibasis.reaktor.performance

import dev.shibasis.reaktor.service.InterceptorStage
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.ServiceChain
import dev.shibasis.reaktor.service.ServiceInterceptor

class MeasureServiceInterceptor(private val scope: ReaktorPerformanceScope = ReaktorPerformanceScope()) : ServiceInterceptor {
    override val stages = setOf(InterceptorStage.CLIENT_APPLICATION)

    override suspend fun <In : Request, Out : Response> intercept(chain: ServiceChain<In, Out>): Out {
        val operation = chain.context.operation
        return ReaktorMeasure.trace(operation.take(64).ifBlank { "service" }, scope.copy(operation = operation)) { chain.proceed() }
    }
}
