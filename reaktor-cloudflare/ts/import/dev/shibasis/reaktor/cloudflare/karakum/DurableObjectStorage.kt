// Automatically generated - do not modify!

package dev.shibasis.reaktor.cloudflare.karakum

external interface DurableObjectStorage {
fun get(key: String): js.promise.Promise<Any?>
fun put(key: String, value: Any?): js.promise.Promise<js.core.Void>
fun delete(key: String): js.promise.Promise<Boolean>
fun deleteAll(): js.promise.Promise<js.core.Void>
fun list(options: DurableObjectListOptions = definedExternally): js.promise.Promise<Any?>
}
