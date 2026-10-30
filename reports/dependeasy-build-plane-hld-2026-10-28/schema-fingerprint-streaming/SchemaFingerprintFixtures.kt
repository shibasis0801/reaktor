package dev.shibasis.reaktor.service

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.SealedSerializationApi
import kotlinx.serialization.builtins.*
import kotlinx.serialization.descriptors.*

@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class, SealedSerializationApi::class)
fun fingerprintCases(): Map<String, SerialDescriptor> {
    val text = String.serializer().descriptor
    val optional = buildClassSerialDescriptor("Optional") {
        element("required", text)
        element("optional", text, isOptional = true)
    }
    val recursiveBase = buildClassSerialDescriptor("Recursive") { element("next", text) }
    val recursive = object : SerialDescriptor by recursiveBase {
        override fun getElementDescriptor(index: Int): SerialDescriptor = this
    }
    val first = buildClassSerialDescriptor("Repeated") { element("text", text) }
    val second = buildClassSerialDescriptor("Repeated") { element("number", Int.serializer().descriptor) }
    return linkedMapOf(
        "text" to text,
        "nullable" to String.serializer().nullable.descriptor,
        "list" to ListSerializer(String.serializer()).descriptor,
        "map" to MapSerializer(Int.serializer(), String.serializer().nullable).descriptor,
        "optional" to optional,
        "object" to buildSerialDescriptor("Singleton", StructureKind.OBJECT) { element("value", text, isOptional = true) },
        "enum" to buildSerialDescriptor("Direction", SerialKind.ENUM) { element("NORTH", text); element("SOUTH", text) },
        "open" to PolymorphicSerializer(Any::class).descriptor,
        "sealed" to buildSerialDescriptor("Choice", PolymorphicKind.SEALED) { element("case", optional) },
        "unicode" to buildClassSerialDescriptor("Schema.Σ🧭") { element("naïve🧭", text) },
        "recursive" to recursive,
        "repeatedName" to buildClassSerialDescriptor("Parent") { element("first", first); element("second", second) },
    )
}
