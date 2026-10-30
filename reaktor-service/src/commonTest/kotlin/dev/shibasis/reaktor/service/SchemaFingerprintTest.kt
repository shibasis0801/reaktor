package dev.shibasis.reaktor.service

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.SealedSerializationApi
import kotlinx.serialization.builtins.*
import kotlinx.serialization.descriptors.*

import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaFingerprintTest {
    @Test
    fun preservesExistingWireFingerprints() {
        val expected = mapOf(
            "enum" to "2270d82c6e835611",
            "list" to "6269174595fd1fb1",
            "map" to "c900201d0bd5161d",
            "nullable" to "1ced7f5698c6cf9e",
            "object" to "a0733c3c77b59492",
            "open" to "59451562f0326448",
            "optional" to "e944486a08a7f813",
            "recursive" to "c1f00d4b56262e3c",
            "repeatedName" to "35e989c1d6da3655",
            "sealed" to "7621d3dd8c38f7d2",
            "text" to "bc3c9293aac8e578",
            "unicode" to "a100722f82e8ad87",
        )
        val cases = fingerprintCases()
        assertEquals(expected.keys, cases.keys)
        for ((name, descriptor) in cases) {
            assertEquals(expected.getValue(name), descriptor.fingerprint(), name)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class, SealedSerializationApi::class)
private fun fingerprintCases(): Map<String, SerialDescriptor> {
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
