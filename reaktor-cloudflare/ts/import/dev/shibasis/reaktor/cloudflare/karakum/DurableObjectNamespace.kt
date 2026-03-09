// Automatically generated - do not modify!

package dev.shibasis.reaktor.cloudflare.karakum

external interface DurableObjectNamespace {
fun newUniqueId(options: Any? = definedExternally): DurableObjectId
fun idFromName(name: String): DurableObjectId
fun idFromString(id: String): DurableObjectId
fun get(id: DurableObjectId, options: DurableObjectGetOptions = definedExternally): DurableObjectStub
fun getByName(name: String): DurableObjectStub
}
