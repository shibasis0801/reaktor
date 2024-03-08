package dev.shibasis.flatinvoker.core.serialization

import dev.shibasis.flatinvoker.core.FlexBuffer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.serializer

/*
Focus should not be just on compact encoding
But should optimise for speed at minimum size.
Speed/Flexibility is more important than encoding size.

For size, I need to understand IndirectFields, TypedVectors, etc

The encode/decode must be the same algorithm as the one used by the JsonParser of FlexBuffers
100% parity needed, so that they can interop

Building this, I will be able to understand FlexBuffers in depth
And then write a series of articles
*/
class FlexEncoder: AbstractEncoder() {
    override val serializersModule = EmptySerializersModule()
    val flexBuffer = FlexBuffer.Create()

    // need to keep these in a stack
    var vectorStart: ULong = 0u
    var mapStart: ULong = 0u
    var inVector = false

    var fieldName: String = ""
    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        fieldName = descriptor.getElementName(index)
        return super.encodeElement(descriptor, index)
    }

    override fun encodeValue(value: Any) {
        val fieldName = if (inVector) null else this.fieldName
        when (value) {
            is String -> FlexBuffer.String(flexBuffer, fieldName, value)
            is Int -> FlexBuffer.Int(flexBuffer, fieldName, value.toLong())
            is Long -> FlexBuffer.Int(flexBuffer, fieldName, value)
            is Double -> FlexBuffer.Double(flexBuffer, fieldName, value)
            is Boolean -> FlexBuffer.Bool(flexBuffer, fieldName, value)
        }
    }

    override fun encodeNull() {
        FlexBuffer.Null(flexBuffer, null)
    }

    override fun beginCollection(
        descriptor: SerialDescriptor,
        collectionSize: Int
    ): CompositeEncoder {
        vectorStart = FlexBuffer.StartVector(flexBuffer, null)
        inVector = true
        return this
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        when(descriptor.kind) {
            StructureKind.CLASS, StructureKind.MAP, StructureKind.OBJECT -> {
                mapStart = FlexBuffer.StartMap(flexBuffer, null)
                inVector = false
                return this
            }
            // according to code, StructureKind.LIST should never come here, instead beginCollection
            else -> return super.beginStructure(descriptor)
        }
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        when(descriptor.kind) {
            StructureKind.CLASS, StructureKind.MAP, StructureKind.OBJECT -> {
                FlexBuffer.EndMap(flexBuffer, mapStart)
                inVector = true
            }
            StructureKind.LIST -> {
                FlexBuffer.EndVector(flexBuffer, vectorStart)
                inVector = false
            }
            else -> {}
        }
    }
}

fun<T> encodeToFlexBuffer(serializer: SerializationStrategy<T>, value: T): Long {
    val encoder = FlexEncoder()
    encoder.encodeSerializableValue(serializer, value)
    return encoder.flexBuffer
}

inline fun <reified T> encodeToFlexBuffer(value: T) = encodeToFlexBuffer(serializer(), value)