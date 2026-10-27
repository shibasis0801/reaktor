package dev.shibasis.reaktor.service.contract

import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.TraceContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

suspend fun <In, Out> activity(
    contract: String,
    name: String,
    request: In,
    effect: Effect,
    identity: Map<String, String>,
    interceptors: List<Installed> = emptyList(),
    execute: suspend (In) -> Out,
): Out {
    val context = currentCoroutineContext()
    val parent = context[ServiceCall]
    val attributes = CallAttributes.ambient() + parent?.attributes.orEmpty() + context[CallAttributes]?.values.orEmpty() + identity
    val call = ServiceCall(parent?.trace?.child() ?: TraceContext.root(), parent?.trace?.spanId, attributes)
    val exchange = Exchange<In, Out>(Family.Call, Side.Server, contract, name, null, null, request,
        Metadata(), attributes.toMutableMap(), call, context[ExchangeScope]?.exchange?.deadline, effect)
    val capture = context[ActivityCaptureScope]?.capture
    val index = context[ActivityChildren]?.next() ?: 0
    val key = ActivityCaptureKey(call.trace.traceId, call.parentSpanId, index, contract, name)
    return withContext(call + ExchangeScope(exchange) + ActivityChildren()) {
        Interceptors.ordered(Interceptors.process.at(0), interceptors).proceed(exchange) {
            if (capture == null) execute(it.request) else capture.invoke(key, it.request, execute)
        }
    }
}
