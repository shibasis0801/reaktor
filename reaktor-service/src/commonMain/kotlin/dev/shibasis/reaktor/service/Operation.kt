package dev.shibasis.reaktor.service

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlin.js.JsExport

@JsExport
@Serializable
data class ServiceContract(val id: String, val version: Int = 1) {
    companion object {
        val Unnamed = ServiceContract("")
    }
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
) {
    val portType: String
        get() = operationPortType(operation, request.name, response.name)
}

fun operationPortType(operation: String, request: String, response: String): String =
    "op:$operation:$request->$response"

fun operationPortType(operation: String, request: KSerializer<*>, response: KSerializer<*>): String =
    operationPortType(operation, request.descriptor.serialName, response.descriptor.serialName)

fun KSerializer<*>.schemaRef(): SchemaRef = SchemaRef(descriptor.serialName, descriptor.fingerprint())

fun SerialDescriptor.fingerprint(): String {
    val canonical = StringBuilder()
    canonicalize(this, canonical, mutableSetOf())
    var hash = -3750763034362895579L
    canonical.forEach { char ->
        hash = hash xor char.code.toLong()
        hash *= 1099511628211L
    }
    return hash.toULong().toString(16).padStart(16, '0')
}

private fun canonicalize(descriptor: SerialDescriptor, out: StringBuilder, seen: MutableSet<String>) {
    out.append(descriptor.serialName).append('|').append(descriptor.kind).append(if (descriptor.isNullable) "?" else "")
    if (!seen.add(descriptor.serialName) || descriptor.kind is PolymorphicKind.OPEN) return
    val ordered = descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT
    out.append('{')
    (0 until descriptor.elementsCount).forEach { index ->
        out.append(descriptor.getElementName(index))
        if (ordered && descriptor.isElementOptional(index)) out.append('=')
        out.append(':')
        canonicalize(descriptor.getElementDescriptor(index), out, seen)
        out.append(',')
    }
    out.append('}')
}
