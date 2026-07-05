package dev.shibasis.reaktor.service

import kotlinx.serialization.KSerializer
import kotlin.js.JsExport
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

@JsExport
class PostHandler<In: Request, Out: Response>(
    route: String,
    operation: String = route,
    requestSerializer: KSerializer<In>,
    responseSerializer: KSerializer<Out>,
    handler: RequestHandlerBlock<In, Out>
): RequestHandler<In, Out>(
    ServiceEndpoint.http(HttpMethod.POST, route, operation), requestSerializer, responseSerializer, handler
) {
    @JsExport.Ignore
    operator fun provideDelegate(
        thisRef: Service,
        property: KProperty<*>,
    ): ReadOnlyProperty<Service, PostHandler<In, Out>> =
        bindServiceProperty(thisRef, property)

    companion object: Factory {
        override fun <In : Request, Out : Response> create(
            route: String,
            operation: String,
            requestSerializer: KSerializer<In>,
            responseSerializer: KSerializer<Out>,
            block: RequestHandlerBlock<In, Out>
        ) = PostHandler(
            route,
            operation,
            requestSerializer,
            responseSerializer,
            block
        )
    }
}
