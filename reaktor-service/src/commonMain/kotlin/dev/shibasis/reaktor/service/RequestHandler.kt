package dev.shibasis.reaktor.service

import dev.shibasis.reaktor.core.framework.kSerializer
import dev.shibasis.reaktor.io.network.RoutePattern
import kotlinx.serialization.KSerializer
import kotlin.js.JsExport
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

typealias RequestHandlerBlock<In, Out> =
        suspend RequestHandler<In, Out>.(In) -> Out

@JsExport
sealed class RequestHandler<In: Request, Out: Response>(
    endpoint: ServiceEndpoint,
    val requestSerializer: KSerializer<In>,
    val responseSerializer: KSerializer<Out>,
    val handler: RequestHandlerBlock<In, Out>
) {
    var endpoint: ServiceEndpoint = endpoint
        private set

    val transport: ServiceTransport
        get() = endpoint.transport

    val method: HttpMethod
        get() = endpoint.method ?: error("RequestHandler '${endpoint.operation}' is not bound to HTTP")

    val route: String
        get() = endpoint.address

    val routePattern by lazy { RoutePattern.from(route) }

    inline fun url(request: In, vararg extraPathParams: Pair<String, String>): String =
        routePattern.fill(request.pathParams + extraPathParams)

    suspend operator fun invoke(request: In): Out = handler(request)

    fun bindOperation(operation: String): RequestHandler<In, Out> = apply {
        endpoint = endpoint.copy(operation = operation)
    }

    fun bindProperty(propertyName: String): RequestHandler<In, Out> =
        bindOperation(serviceOperationKey(requestSerializer, propertyName))

    @JsExport.Ignore
    @Suppress("UNCHECKED_CAST")
    protected fun <H : RequestHandler<In, Out>> bindServiceProperty(
        thisRef: Service,
        property: KProperty<*>,
    ): ReadOnlyProperty<Service, H> {
        bindProperty(property.name)
        if (this !in thisRef.handlers) {
            thisRef.handlers += this
        }
        val handler = this as H
        return ReadOnlyProperty { _, _ -> handler }
    }

    interface Factory {
        fun <In: Request, Out: Response> create(
            route: String,
            operation: String,
            requestSerializer: KSerializer<In>,
            responseSerializer: KSerializer<Out>,
            block: RequestHandlerBlock<In, Out>
        ): RequestHandler<In, Out>

        operator fun <In: Request, Out: Response> invoke(
            route: String,
            requestSerializer: KSerializer<In>,
            responseSerializer: KSerializer<Out>,
            block: RequestHandlerBlock<In, Out>
        ): RequestHandler<In, Out> = create(route, route, requestSerializer, responseSerializer, block)
    }
}

fun serviceOperationKey(
    requestSerializer: KSerializer<*>,
    propertyName: String,
): String = "${requestSerializer.descriptor.serialName}.$propertyName"

inline fun <reified In: Request, reified Out: Response> RequestHandler.Factory.create(
    route: String,
    operation: String = route,
    noinline fn: RequestHandlerBlock<In, Out>
) = create(
    route,
    operation,
    kSerializer<In>(),
    kSerializer<Out>(),
    fn
)
