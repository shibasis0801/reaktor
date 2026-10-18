package dev.shibasis.reaktor.tooling.operation

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@OptIn(ExperimentalSerializationApi::class)
fun SerialDescriptor.jsonSchema(): JsonObject = schema(this, emptySet())

@OptIn(ExperimentalSerializationApi::class)
fun SerialDescriptor.subject(): String? =
    (0 until elementsCount).firstOrNull { index -> getElementAnnotations(index).any { it is Subject } }?.let(::getElementName)

@OptIn(ExperimentalSerializationApi::class)
private fun schema(descriptor: SerialDescriptor, seen: Set<String>): JsonObject {
    val name = descriptor.serialName.removeSuffix("?")
    val base = when {
        name == "kotlinx.serialization.json.JsonElement" -> JsonObject(emptyMap())
        name == "kotlinx.serialization.json.JsonObject" -> type("object")
        name == "kotlinx.serialization.json.JsonArray" -> type("array")
        descriptor.isInline -> schema(descriptor.getElementDescriptor(0), seen)
        else -> when (descriptor.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> type("string")
            PrimitiveKind.BOOLEAN -> type("boolean")
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> type("integer")
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> type("number")
            SerialKind.ENUM -> buildJsonObject {
                put("type", "string")
                putJsonArray("enum") { (0 until descriptor.elementsCount).forEach { add(descriptor.getElementName(it)) } }
            }
            StructureKind.LIST -> buildJsonObject { put("type", "array"); put("items", schema(descriptor.getElementDescriptor(0), seen)) }
            StructureKind.MAP -> buildJsonObject { put("type", "object"); put("additionalProperties", schema(descriptor.getElementDescriptor(1), seen)) }
            StructureKind.CLASS, StructureKind.OBJECT -> if (name in seen) type("object") else properties(descriptor, seen + name)
            PolymorphicKind.SEALED -> if (name in seen) type("object") else buildJsonObject {
                val variants = descriptor.getElementDescriptor(1)
                put("oneOf", JsonArray((0 until variants.elementsCount).map { index ->
                    val variant = variants.getElementDescriptor(index)
                    discriminated(properties(variant, seen + name + variant.serialName), variant.serialName)
                }))
            }
            else -> JsonObject(emptyMap())
        }
    }
    return described(base, descriptor.annotations)
}

@OptIn(ExperimentalSerializationApi::class)
private fun properties(descriptor: SerialDescriptor, seen: Set<String>): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        (0 until descriptor.elementsCount).forEach { index ->
            val element = descriptor.getElementDescriptor(index)
            val property = described(schema(element, seen), descriptor.getElementAnnotations(index))
            put(descriptor.getElementName(index), if (element.isNullable && !descriptor.isElementOptional(index)) nullable(property) else property)
        }
    }
    val required = (0 until descriptor.elementsCount).filterNot(descriptor::isElementOptional).map(descriptor::getElementName)
    if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
    put("additionalProperties", false)
}

private fun discriminated(variant: JsonObject, serialName: String): JsonObject {
    val properties = buildJsonObject {
        putJsonObject("type") { put("const", serialName) }
        (variant["properties"] as? JsonObject)?.forEach { (key, value) -> put(key, value) }
    }
    val required = listOf(JsonPrimitive("type")) + (variant["required"] as? JsonArray).orEmpty()
    return JsonObject(variant + ("properties" to properties) + ("required" to JsonArray(required)))
}

private fun nullable(property: JsonObject): JsonObject {
    val type = property["type"] as? JsonPrimitive ?: return property
    return JsonObject(property + ("type" to JsonArray(listOf(type, JsonPrimitive("null")))))
}

private fun described(schema: JsonObject, annotations: List<Annotation>): JsonObject =
    annotations.filterIsInstance<Describe>().firstOrNull()?.let { JsonObject(schema + ("description" to JsonPrimitive(it.text))) } ?: schema

private fun type(name: String) = buildJsonObject { put("type", name) }
