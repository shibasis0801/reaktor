@file:JsModule("hono/context")
@file:JsNonModule
@file:Suppress("FunctionName", "PropertyName", "NOTHING_TO_INLINE")

package dev.shibasis.reaktor.cloudflare.hono

import kotlin.js.Promise
import org.w3c.fetch.Request
import org.w3c.fetch.Response
import org.w3c.fetch.ResponseInit

/** Data payload accepted by Context helpers. */
typealias Data = Any?

/** Renderer responsible for producing the final response content. */
typealias Renderer = (content: dynamic) -> dynamic

/** Layout factory invoked by Context.render. */
typealias Layout<T> = (props: T) -> Any?

/** Bag of props inferred for the currently registered renderer. */
typealias PropsForRenderer = dynamic

/** Options accepted by [Context.header]. */
external interface SetHeadersOptions {
    var append: Boolean?
}

/** Function used to mutate response headers. */
external interface SetHeaders {
    @JsName("call")
    operator fun invoke(name: String, value: String? = definedExternally, options: SetHeadersOptions = definedExternally)
}

/** Factory used to construct a [Response] object. */
external interface NewResponse {
    @JsName("call")
    operator fun invoke(data: Data?, status: Int? = definedExternally, headers: dynamic = definedExternally): Response

    @JsName("call")
    operator fun invoke(data: Data?, init: ResponseInit): Response
}

/** Builder used by [Context.body]. */
external interface BodyRespond {
    @JsName("call")
    operator fun invoke(data: Data?, status: Int? = definedExternally, headers: dynamic = definedExternally): Response

    @JsName("call")
    operator fun invoke(data: Data?, init: ResponseInit): Response
}

/** Builder used by [Context.text]. */
external interface TextRespond {
    @JsName("call")
    operator fun invoke(text: String, status: Int? = definedExternally, headers: dynamic = definedExternally): Response

    @JsName("call")
    operator fun invoke(text: String, init: ResponseInit): Response
}

/** Builder used by [Context.json]. */
external interface JsonRespond {
    @JsName("call")
    operator fun invoke(value: Any?, status: Int? = definedExternally, headers: dynamic = definedExternally): Response

    @JsName("call")
    operator fun invoke(value: Any?, init: ResponseInit): Response
}

/** Builder used by [Context.html]. */
external interface HtmlRespond {
    @JsName("call")
    operator fun invoke(html: String, status: Int? = definedExternally, headers: dynamic = definedExternally): dynamic

    @JsName("call")
    operator fun invoke(html: String, init: ResponseInit): dynamic
}

/** Redirect helper used by [Context.redirect]. */
external interface RedirectRespond {
    @JsName("call")
    operator fun invoke(location: dynamic, status: Int = definedExternally): Response
}

/** Options accepted when constructing a [Context]. */
external interface ContextOptions<E : Env> {
    var env: dynamic
    var executionCtx: dynamic
    var notFoundHandler: NotFoundHandler<E>?
    var matchResult: dynamic
    var path: String?
}

/**
 * Runtime context exposed to handlers and middleware.
 */
external open class Context<E : Env = Env, P : String = String, I : Input = Input>(
    request: Request,
    options: ContextOptions<E> = definedExternally,
) {
    var env: dynamic
    var finalized: Boolean
    var error: dynamic
    val req: HonoRequest<P, dynamic>
    val event: FetchEventLike
    val executionCtx: ExecutionContext
    var res: Response?
    var render: Renderer
    fun setLayout(layout: Layout<PropsForRenderer>): Layout<PropsForRenderer>
    fun getLayout(): Layout<PropsForRenderer>?
    fun setRenderer(renderer: Renderer)
    val header: SetHeaders
    fun status(status: Int)
    fun set(key: String, value: Any?)
    fun <T> get(key: String): T
    val `var`: dynamic
    val newResponse: NewResponse
    val body: BodyRespond
    val text: TextRespond
    val json: JsonRespond
    val html: HtmlRespond
    val redirect: RedirectRespond
    fun notFound(): dynamic
}

/** Execution context associated with the current request. */
external interface ExecutionContext {
    fun waitUntil(promise: Promise<dynamic>)
    fun passThroughOnException()
    val props: dynamic
}
