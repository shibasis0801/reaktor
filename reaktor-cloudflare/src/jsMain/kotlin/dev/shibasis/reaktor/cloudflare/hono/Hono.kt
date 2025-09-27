@file:JsModule("hono")
@file:JsNonModule

package dev.shibasis.reaktor.cloudflare.hono

import org.w3c.fetch.Request
import org.w3c.fetch.RequestInit

/** Options accepted when creating a [Hono] application. */
external interface HonoOptions<E : Env> {
    var strict: Boolean?
    var router: Router<dynamic>?
    var getPath: GetPath<E>?
}

/** Options passed to the custom getPath implementation. */
external interface GetPathOptions<E : Env> {
    var env: dynamic
}

/** Signature for custom path resolvers. */
typealias GetPath<E> = (request: Request, options: GetPathOptions<E>?) -> String

/** Handler invoked when mounting third party frameworks. */
typealias MountApplicationHandler = (request: Request, vararg args: Any?) -> dynamic

/** Optional configuration accepted by [Hono.mount]. */
external interface MountOptions {
    var optionHandler: ((context: Context<Env, String, Input>) -> Any?)?
    var replaceRequest: dynamic /* ((Request) -> Request)? | Boolean? */
}

/** Lightweight Kotlin binding for the Hono application instance. */
external open class Hono<E : Env = BlankEnv, S : Schema = BlankSchema, BasePath : String = String>(
    options: HonoOptions<E> = definedExternally,
) {
    fun get(handler: H<E, BasePath, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>
    fun get(vararg handlers: H<E, BasePath, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>
    fun get(path: String, vararg handlers: H<E, String, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>

    fun post(handler: H<E, BasePath, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>
    fun post(vararg handlers: H<E, BasePath, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>
    fun post(path: String, vararg handlers: H<E, String, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>

    fun put(handler: H<E, BasePath, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>
    fun put(vararg handlers: H<E, BasePath, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>
    fun put(path: String, vararg handlers: H<E, String, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>

    fun delete(handler: H<E, BasePath, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>
    fun delete(vararg handlers: H<E, BasePath, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>
    fun delete(path: String, vararg handlers: H<E, String, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>

    fun options(handler: H<E, BasePath, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>
    fun options(vararg handlers: H<E, BasePath, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>
    fun options(path: String, vararg handlers: H<E, String, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>

    fun patch(handler: H<E, BasePath, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>
    fun patch(vararg handlers: H<E, BasePath, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>
    fun patch(path: String, vararg handlers: H<E, String, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>

    fun all(handler: H<E, BasePath, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>
    fun all(vararg handlers: H<E, BasePath, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>
    fun all(path: String, vararg handlers: H<E, String, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>

    fun on(method: dynamic, path: dynamic, vararg handlers: H<E, String, Input, HandlerResponse<dynamic>>): Hono<E, S, BasePath>
    fun use(vararg handlers: MiddlewareHandler<E, BasePath, Input>): Hono<E, S, BasePath>
    fun use(path: String, vararg handlers: MiddlewareHandler<E, String, Input>): Hono<E, S, BasePath>

    fun route(path: String, app: Hono<*, *, *>): Hono<E, MergeSchemaPath<S, MergePath<BasePath, String>>, BasePath>
    fun basePath(path: String): Hono<E, S, MergePath<BasePath, String>>
    fun onError(handler: ErrorHandler<E>): Hono<E, S, BasePath>
    fun notFound(handler: NotFoundHandler<E>): Hono<E, S, BasePath>
    fun mount(path: String, applicationHandler: MountApplicationHandler, options: MountOptions = definedExternally): Hono<E, S, BasePath>

    fun fetch(request: Request, env: dynamic = definedExternally, executionCtx: ExecutionContext? = definedExternally): dynamic
    fun request(input: dynamic, requestInit: RequestInit? = definedExternally, env: dynamic = definedExternally, executionCtx: ExecutionContext? = definedExternally): dynamic
    fun fire()

    val routes: Array<RouterRoute>
    val router: Router<dynamic>
    val getPath: GetPath<E>
}
