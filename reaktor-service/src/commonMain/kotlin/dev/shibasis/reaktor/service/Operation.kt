package dev.shibasis.reaktor.service

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlin.js.JsExport

@JsExport
enum class ServiceExecutionPhase {
    CLIENT,
    SERVER,
}

@JsExport
@Serializable
enum class Interaction { RequestReply }

@JsExport
@Serializable
data class SchemaRef(val name: String, val fingerprint: String)

@JsExport
@Serializable
data class HttpBinding(val method: HttpMethod, val route: String)

@JsExport
@Serializable
data class OperationDescriptor(
    val contract: String,
    val version: Int,
    val operation: String,
    val interaction: Interaction,
    val phase: ServiceExecutionPhase,
    val request: SchemaRef,
    val response: SchemaRef,
    val http: HttpBinding?,
)

fun KSerializer<*>.schemaRef(): SchemaRef = SchemaRef(descriptor.serialName, descriptor.fingerprint())

fun SerialDescriptor.fingerprint(): String =
    canonicalize(this, -3750763034362895579L, mutableSetOf())
        .toULong().toString(16).padStart(16, '0')

// Preserve the canonical UTF-16 sequence while hashing it without a complete intermediate string.
private fun canonicalize(descriptor: SerialDescriptor, initial: Long, seen: MutableSet<String>): Long {
    var hash = initial.appendFingerprint(descriptor.serialName)
        .appendFingerprint('|').appendFingerprint(descriptor.kind.toString())
    if (descriptor.isNullable) hash = hash.appendFingerprint('?')
    if (!seen.add(descriptor.serialName) || descriptor.kind is PolymorphicKind.OPEN) return hash
    val ordered = descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT
    hash = hash.appendFingerprint('{')
    for (index in 0 until descriptor.elementsCount) {
        hash = hash.appendFingerprint(descriptor.getElementName(index))
        if (ordered && descriptor.isElementOptional(index)) hash = hash.appendFingerprint('=')
        hash = hash.appendFingerprint(':')
        hash = canonicalize(descriptor.getElementDescriptor(index), hash, seen)
        hash = hash.appendFingerprint(',')
    }
    return hash.appendFingerprint('}')
}

private fun Long.appendFingerprint(value: Char): Long =
    (this xor value.code.toLong()) * 1099511628211L

private fun Long.appendFingerprint(value: String): Long {
    var hash = this
    for (index in value.indices) hash = hash.appendFingerprint(value[index])
    return hash
}
