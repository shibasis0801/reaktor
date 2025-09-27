@file:JsModule("hono/request")
@file:JsNonModule

package dev.shibasis.reaktor.cloudflare.hono

import kotlin.js.Promise
import org.khronos.webgl.ArrayBuffer
import org.w3c.dom.FormData
import org.w3c.files.Blob
import org.w3c.fetch.Request

/**
 * Wrapper around the raw [Request] enriched with router metadata and helpers.
 */
external open class HonoRequest<P : String = String, I> {
    val raw: Request
    var routeIndex: Int
    val path: String
    var bodyCache: dynamic

    fun param(key: String): String?
    fun param(): dynamic
    fun query(key: String): String?
    fun query(): dynamic
    fun queries(key: String): Array<String>?
    fun queries(): dynamic
    fun header(name: String): String?
    fun header(): dynamic
    fun parseBody(options: dynamic = definedExternally): Promise<dynamic>
    fun <T> json(): Promise<T>
    fun text(): Promise<String>
    fun arrayBuffer(): Promise<ArrayBuffer>
    fun blob(): Promise<Blob>
    fun formData(): Promise<FormData>
    fun addValidatedData(target: String, data: dynamic)
    fun valid(target: String): dynamic
    val url: String
    val method: String
    val matchedRoutes: Array<RouterRoute>
    val routePath: String
}
