package dev.shibasis.reaktor.cloudflare.hono

import kotlin.js.Promise
import org.w3c.fetch.Request
import org.w3c.fetch.Response

/**
 * Normalizes the return value of [Hono.fetch] into a [Promise].
 */
fun Hono<*, *, *>.fetchAsync(
    request: Request,
    env: dynamic = undefined,
    executionCtx: ExecutionContext? = null,
): Promise<Response> = Promise.resolve(fetch(request, env, executionCtx)).unsafeCast()

/**
 * Normalizes the return value of [Hono.request] into a [Promise].
 */
fun Hono<*, *, *>.requestAsync(
    input: dynamic,
    init: org.w3c.fetch.RequestInit? = null,
    env: dynamic = undefined,
    executionCtx: ExecutionContext? = null,
): Promise<Response> = Promise.resolve(request(input, init, env, executionCtx)).unsafeCast()

private val undefined: Nothing?
    get() = js("undefined")

private inline fun <T> Promise<dynamic>.unsafeCast(): Promise<T> = kotlin.js.unsafeCast(this)
