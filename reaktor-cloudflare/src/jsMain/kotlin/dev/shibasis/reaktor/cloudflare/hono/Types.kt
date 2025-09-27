@file:JsModule("hono")
@file:JsNonModule
@file:Suppress("FunctionName", "PropertyName", "UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")

package dev.shibasis.reaktor.cloudflare.hono

import kotlin.js.Promise
import org.w3c.fetch.Response

/**
 * Marker type for runtime bindings exposed by the host environment.
 */
external interface Bindings

/**
 * Marker type for values stored on the [Context] variable map.
 */
external interface Variables

/**
 * Representation of the request environment used by Hono.
 */
external interface Env {
    /** Optional runtime bindings (secrets, KV namespaces, databases, ...). */
    var Bindings: Bindings?

    /** Optional per-request variables. */
    var Variables: Variables?
}

/** Empty schema marker used by Hono. */
typealias BlankSchema = Schema

/** Empty environment marker used by Hono. */
typealias BlankEnv = Env

/** Empty input marker used by Hono. */
typealias BlankInput = Input

/**
 * Schema information for request/response payloads.
 *
 * Hono only uses structural typing, therefore the Kotlin binding models it as [dynamic].
 */
typealias Schema = dynamic

/**
 * Supported response formats returned by typed handlers.
 */
typealias ResponseFormat = String

/**
 * Container describing the shape of request input/output.
 */
external interface Input {
    /** Optional input payload schema. */
    @JsName("in")
    var `in`: dynamic

    /** Optional output payload schema. */
    var out: dynamic

    /** Optional serialization hint for the typed response. */
    var outputFormat: ResponseFormat?
}

/**
 * Signature for the "next" continuation used by middleware.
 */
typealias Next = () -> Promise<Unit>

/**
 * Description of a registered route inside the router.
 */
external interface RouterRoute {
    var basePath: String
    var path: String
    var method: String
    var handler: dynamic
}

/**
 * Union of values that can be produced by a handler.
 */
typealias HandlerResponse<O> = Any /* Response | TypedResponse<O, *, *> | Promise<Response | TypedResponse<O, *, *>> */

/**
 * Typed handler invoked for matching requests.
 */
typealias Handler<E, P, I, R> = (Context<E, P, I>, Next) -> R

/**
 * Typed middleware handler that can short-circuit or forward requests.
 */
typealias MiddlewareHandler<E, P, I> = (Context<E, P, I>, Next) -> Promise<dynamic /* Response? */>

/**
 * Union of handler/middleware entries accepted by registration functions.
 */
typealias H<E, P, I, R> = Any /* Handler<E, P, I, R> | MiddlewareHandler<E, P, I> */

/**
 * Handler responsible for 404 responses.
 */
typealias NotFoundHandler<E> = (Context<E, *, *>) -> Promise<Response>?

/**
 * Rich error object used by Hono when throwing HTTP aware exceptions.
 */
external interface HTTPResponseError : kotlin.js.Error {
    fun getResponse(): Response
}

/**
 * Global error handler used to customize error responses.
 */
typealias ErrorHandler<E> = (err: dynamic, context: Context<E, *, *>) -> dynamic

/**
 * Marker for merged schema path results.
 */
typealias MergeSchemaPath<S, Path> = dynamic

/**
 * Marker for merged routing paths.
 */
typealias MergePath<BasePath, Path> = String

/**
 * Marker used by the strongly typed router DSL.
 */
typealias ToSchema<M, P, I, R> = dynamic

/**
 * Marker representing a merged typed response across handler chains.
 */
typealias MergeTypedResponse<R> = dynamic

/**
 * Marker used when extracting string keys from schema definitions.
 */
typealias ExtractStringKey<S> = String

/**
 * Marker used when intersecting environment constraints.
 */
typealias IntersectNonAnyTypes<T> = dynamic

/**
 * Marker used when mapping schema paths after middleware registration.
 */
typealias ChangePathOfSchema<S, Path> = dynamic

/**
 * Typed HTTP response emitted by helpers such as [Context.json].
 */
external interface TypedResponse<Body, Status, Source> : Response

/**
 * Abstract host-specific fetch event.
 */
abstract external class FetchEventLike {
    abstract val request: org.w3c.fetch.Request
    abstract fun respondWith(promise: dynamic /* Response | Promise<Response> */)
    abstract fun passThroughOnException()
    abstract fun waitUntil(promise: Promise<dynamic>)
}

/**
 * Bag of validation targets passed to validator middleware.
 */
typealias ValidationTargets = dynamic

/**
 * Marker describing the schema inferred for a handler input.
 */
typealias InputToDataByTarget<T, Target> = dynamic

/**
 * Marker representing form values.
 */
typealias FormValue = Any

/**
 * Marker representing parsed form values.
 */
typealias ParsedFormValue = Any

/**
 * Options accepted by the typed client helpers.
 */
external interface ClientRequestOptions {
    var fetch: ((input: dynamic, init: dynamic) -> Promise<Response>)?
}

/**
 * Typed inference helpers exported by Hono's client utilities.
 */
typealias InferRequestType<App> = dynamic

/**
 * Typed inference helpers exported by Hono's client utilities.
 */
typealias InferResponseType<App> = dynamic
