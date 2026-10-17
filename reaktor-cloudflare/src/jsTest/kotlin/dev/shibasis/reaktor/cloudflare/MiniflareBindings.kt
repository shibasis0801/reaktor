@file:JsModule("miniflare")
package dev.shibasis.reaktor.cloudflare

import kotlin.js.Promise

internal external class Miniflare(options: dynamic) {
    fun getD1Database(name: String): Promise<RawD1Database>
    fun dispose(): Promise<Unit>
}
