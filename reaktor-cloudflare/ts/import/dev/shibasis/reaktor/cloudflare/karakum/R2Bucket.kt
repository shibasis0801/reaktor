// Automatically generated - do not modify!

package dev.shibasis.reaktor.cloudflare.karakum

external interface R2Bucket {
fun head(key: String): js.promise.Promise<R2Object?>
fun get(key: String, options: Any? = definedExternally): js.promise.Promise<Any?>
fun put(key: String, value: Any?, options: Any? = definedExternally): js.promise.Promise<R2Object?>
fun delete(keys: String): js.promise.Promise<js.core.Void>

fun delete(keys: js.array.ReadonlyArray<String>): js.promise.Promise<js.core.Void>
fun list(options: Any? = definedExternally): js.promise.Promise<Any?>
}
