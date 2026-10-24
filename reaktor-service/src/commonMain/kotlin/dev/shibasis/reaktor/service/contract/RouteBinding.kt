package dev.shibasis.reaktor.service.contract

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.service.Environment
import dev.shibasis.reaktor.service.HttpMethod
import dev.shibasis.reaktor.service.Request
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class Outgoing(val path: String, val query: List<Pair<String, String>>, val body: String?)

class Inbound(
    val pathParams: Map<String, String> = emptyMap(),
    val query: Map<String, List<String>> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val attributes: Map<String, Any?> = emptyMap(),
)

class Outbound(val status: Int, val headers: Map<String, String>, val body: String)

private val HttpMethod.carriesBody: Boolean get() = this == HttpMethod.POST || this == HttpMethod.PUT || this == HttpMethod.PATCH

fun <In> Route.encode(serializer: KSerializer<In>, request: In): Outgoing {
    if (request is Request) return Outgoing(
        request.pathParams.entries.fold(path) { filled, (name, value) -> filled.replace("{$name}", value) },
        request.queryParams.toList(),
        if (method.carriesBody) json.encodeToString(serializer, request) else null,
    )
    val tree = json.encodeToJsonElement(serializer, request)
    val fields = tree as? JsonObject ?: JsonObject(emptyMap())
    val parameters = parameters.toSet()
    val path = parameters.fold(path) { filled, name ->
        val value = fields[name]?.scalar() ?: throw IllegalArgumentException("Route $method $path needs '$name', which ${serializer.descriptor.serialName} does not carry as a value")
        filled.replace("{$name}", value.encodeURLParameter())
    }
    if (method.carriesBody) return Outgoing(path, emptyList(), json.encodeToString(JsonElement.serializer(), tree))
    val query = fields.filterKeys { it !in parameters }.flatMap { (name, value) ->
        when (value) {
            is JsonNull -> emptyList()
            is JsonPrimitive -> listOf(name to value.content)
            is JsonArray -> if (value.all { it is JsonPrimitive && it !is JsonNull }) value.map { name to (it as JsonPrimitive).content }
                else listOf(name to value.toString())
            is JsonObject -> listOf(name to value.toString())
        }
    }
    return Outgoing(path, query, null)
}

fun <In> Route.decode(serializer: KSerializer<In>, inbound: Inbound): In {
    val text = inbound.body?.takeIf { it.isNotBlank() }
    val carried = runCatching { json.decodeFromString(serializer, text ?: "{}") }.getOrNull()?.takeIf { it is Request }
    return (carried ?: merged(serializer, text, inbound)).also { (it as? Request)?.receive(inbound) }
}

private fun <In> merged(serializer: KSerializer<In>, text: String?, inbound: Inbound): In {
    val descriptor = serializer.descriptor
    val body = text?.let { json.parseToJsonElement(it) }
    val fields = LinkedHashMap<String, JsonElement>()
    (body as? JsonObject)?.let(fields::putAll)
    if (descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT) {
        for (index in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(index)
            val element = descriptor.getElementDescriptor(index)
            val path = inbound.pathParams[name]
            val query = inbound.query[name].orEmpty()
            when {
                path != null -> fields[name] = coerce(listOf(path), element)
                name !in fields && query.isNotEmpty() -> fields[name] = coerce(query, element)
            }
        }
    }
    val tree = if (body != null && body !is JsonObject && fields.isEmpty()) body else JsonObject(fields)
    return json.decodeFromJsonElement(serializer, tree)
}

private fun Request.receive(inbound: Inbound) {
    pathParams.putAll(inbound.pathParams)
    inbound.query.forEach { (name, values) -> values.firstOrNull()?.let { queryParams[name] = it } }
    inbound.headers.forEach { (name, value) ->
        if (name == Environment.Header) environment = Environment(value)
        headers[name] = value
    }
    attributes.putAll(inbound.attributes)
}

private fun JsonElement.scalar(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

private fun coerce(values: List<String>, descriptor: SerialDescriptor): JsonElement {
    val target = if (descriptor.isInline) descriptor.getElementDescriptor(0) else descriptor
    return when (target.kind) {
        StructureKind.LIST -> JsonArray(values.flatMap { value ->
            if (values.size == 1 && value.trimStart().startsWith("[")) (json.parseToJsonElement(value) as JsonArray).toList()
            else listOf(scalar(value, target.getElementDescriptor(0)))
        })
        else -> scalar(values.last(), target)
    }
}

private fun scalar(value: String, descriptor: SerialDescriptor): JsonElement {
    val target = if (descriptor.isInline) descriptor.getElementDescriptor(0) else descriptor
    if (value == "null" && target.isNullable) return JsonNull
    return when (target.kind) {
        PrimitiveKind.BOOLEAN -> value.toBooleanStrictOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG ->
            value.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> value.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
        PrimitiveKind.STRING, PrimitiveKind.CHAR, SerialKind.ENUM, SerialKind.CONTEXTUAL -> JsonPrimitive(value)
        else -> runCatching { json.parseToJsonElement(value) }.getOrElse { JsonPrimitive(value) }
    }
}
