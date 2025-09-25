@file:JsModule("hono/router")
@file:JsNonModule

package dev.shibasis.reaktor.hono

/**
 * Router abstraction used by Hono. Custom routers may implement this interface.
 */
external interface Router<T> {
    /** Human readable router name. */
    var name: String

    /** Registers a handler for the given HTTP [method] and [path]. */
    fun add(method: String, path: String, handler: T)

    /** Matches the most appropriate handler for the requested [method] and [path]. */
    fun match(method: String, path: String): dynamic
}

/** Constant representing the "ALL" catch-all method. */
external val METHOD_NAME_ALL: String

/** Constant representing the lowercase "all" catch-all method. */
external val METHOD_NAME_ALL_LOWERCASE: String

/** Array containing the canonical HTTP methods supported by Hono. */
external val METHODS: Array<String>
