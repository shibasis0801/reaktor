// Automatically generated - do not modify!

package dev.shibasis.reaktor.cloudflare.karakum

external interface DurableObjectState {
val id: DurableObjectId
val storage: DurableObjectStorage
fun waitUntil(promise: js.promise.Promise<Any?>): Unit
fun blockConcurrencyWhile(callback: () -> js.promise.Promise<Any?>): js.promise.Promise<Any?>
}
